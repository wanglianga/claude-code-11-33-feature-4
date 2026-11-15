package com.trace.recall.cost;

import com.trace.recall.common.Json;
import com.trace.recall.common.JdbcSupport;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重复计量检测器。
 *
 * 识别可能重复占用的金额集合：
 * - EXACT_DUPLICATE：身份簿中外部单号+行项目+合同号+文件指纹完全一致（相似金额不并单）；
 * - CREDIT_NOTICE_UNPAIRED / REVERSAL_UNPAIRED：贷项通知/冲销凭证未关联原发票行 →
 *   进入待人工配对集合（OPEN），配对完成前贷项不得冲减任何批次范围；
 * - MULTI_PARTY_TRANSFER / SPLIT_BILL：同一服务编号被多方转付或拆分为多张账单，保留各方、不自动合并；
 * - PAYMENT_CONFLICT：同一检测服务由实验室账单与机构回报都声明已支付，
 *   但币种、税额或服务编号不一致 → 保留两个独立声明并标记支付状态冲突，不认定任何一方为真实付款。
 */
@Service
public class DuplicateDetectionService {

    private final JdbcSupport db;

    public DuplicateDetectionService(JdbcSupport db) {
        this.db = db;
    }

    public List<Map<String, Object>> detect(long workspaceId) {
        List<Map<String, Object>> findings = new ArrayList<>();
        findings.addAll(detectIdentityDuplicates(workspaceId));
        findings.addAll(detectUnpairedCreditAndReversal(workspaceId));
        findings.addAll(detectSplitAndMultiParty(workspaceId));
        findings.addAll(detectPaymentConflicts(workspaceId));
        return findings;
    }

    private List<Map<String, Object>> detectIdentityDuplicates(long ws) {
        List<Map<String, Object>> rows = db.query("""
                SELECT dedup_group, array_agg(voucher_id) AS voucher_ids
                FROM voucher_identity
                WHERE workspace_id = ? AND dedup_status = 'DUPLICATE_CANDIDATE'
                GROUP BY dedup_group
                """, ws);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object ids = toIdList(r.get("voucherIds"));
            out.add(save(ws, "EXACT_DUPLICATE", String.valueOf(r.get("dedupGroup")),
                    ids, "CONFLICT",
                    "身份键完全一致的凭证（外部单号/行项目/合同号/文件指纹），存在重复计入风险，仅保留一条进入事实"));
        }
        return out;
    }

    private List<Map<String, Object>> detectUnpairedCreditAndReversal(long ws) {
        List<Map<String, Object>> vouchers = db.query("""
                SELECT id, voucher_code, voucher_type, external_doc_no, referenced_doc_no, referenced_line_no,
                       net_amount, target_batch_codes
                FROM cost_voucher WHERE workspace_id = ? ORDER BY id
                """, ws);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> v : vouchers) {
            String type = String.valueOf(v.get("voucherType"));
            boolean isCredit = "CREDIT_NOTICE".equals(type);
            boolean isReversal = "REVERSAL_VOUCHER".equals(type);
            if (!isCredit && !isReversal) {
                continue;
            }
            String refDoc = v.get("referencedDocNo") == null ? null : String.valueOf(v.get("referencedDocNo"));
            String refLine = v.get("referencedLineNo") == null ? null : String.valueOf(v.get("referencedLineNo"));
            Long matched = null;
            if (refDoc != null && !refDoc.isBlank()) {
                matched = db.jdbc().queryForObject("""
                        SELECT count(*) FROM cost_voucher
                        WHERE workspace_id = ? AND external_doc_no = ?
                          AND (? IS NULL OR line_item_no = ?)
                        """, Long.class, ws, refDoc, refLine, refLine);
            }
            String findingType = isCredit ? "CREDIT_NOTICE_UNPAIRED" : "REVERSAL_UNPAIRED";
            if (matched != null && matched > 0) {
                out.add(save(ws, findingType, "PAIR-" + v.get("id"), List.of(v.get("id")), "RESOLVED",
                        (isCredit ? "贷项通知" : "冲销凭证") + " 已关联原发票 " + refDoc
                                + (refLine == null ? "" : " 行 " + refLine) + "，配对关系成立"));
            } else {
                out.add(save(ws, findingType, "PAIR-" + v.get("id"), List.of(v.get("id")), "OPEN",
                        (isCredit ? "贷项通知" : "冲销凭证") + " 未关联可验证的原发票行，纳入待人工配对集合；"
                                + "配对完成前不得将该金额冲减任何批次范围"));
            }
        }
        return out;
    }

    private List<Map<String, Object>> detectSplitAndMultiParty(long ws) {
        List<Map<String, Object>> rows = db.query("""
                SELECT service_no, array_agg(id) AS voucher_ids, count(*) AS cnt
                FROM cost_voucher
                WHERE workspace_id = ? AND service_no IS NOT NULL AND service_no <> ''
                  AND voucher_type NOT IN ('CREDIT_NOTICE','REVERSAL_VOUCHER')
                GROUP BY service_no HAVING count(*) > 1
                """, ws);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            List<Map<String, Object>> members = db.query("""
                    SELECT id, voucher_code, voucher_type, external_doc_no, source_system, net_amount, currency
                    FROM cost_voucher WHERE workspace_id = ? AND service_no = ? ORDER BY id
                    """, ws, r.get("serviceNo"));
            long systems = members.stream().map(m -> m.get("sourceSystem")).distinct().count();
            String type = systems > 1 ? "MULTI_PARTY_TRANSFER" : "SPLIT_BILL";
            out.add(save(ws, type, "SVC-" + r.get("serviceNo"), toIdList(r.get("voucherIds")), "OPEN",
                    "同一服务编号 " + r.get("serviceNo") + " 出现 " + r.get("cnt")
                            + " 张账单（" + (systems > 1 ? "多方转付" : "拆分账单")
                            + "），相似金额不自动并单，需人工核对是否重复占用"));
        }
        return out;
    }

    private List<Map<String, Object>> detectPaymentConflicts(long ws) {
        // 同一服务编号存在两个“已支付”声明，且币种/税额/服务编号任一不一致
        List<Map<String, Object>> rows = db.query("""
                SELECT service_no, array_agg(id) AS voucher_ids, count(*) AS cnt
                FROM cost_voucher
                WHERE workspace_id = ? AND service_no IS NOT NULL AND service_no <> ''
                  AND jsonb_exists(payment_claim, 'paid') AND (payment_claim ->> 'paid') = 'true'
                GROUP BY service_no HAVING count(*) > 1
                """, ws);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            List<Map<String, Object>> members = db.query("""
                    SELECT id, voucher_code, currency, tax_amount, service_no, source_system, payment_claim
                    FROM cost_voucher WHERE workspace_id = ? AND service_no = ? ORDER BY id
                    """, ws, r.get("serviceNo"));
            long currencies = members.stream().map(m -> m.get("currency")).distinct().count();
            long taxes = members.stream().map(m -> String.valueOf(m.get("taxAmount"))).distinct().count();
            if (currencies > 1 || taxes > 1) {
                out.add(save(ws, "PAYMENT_CONFLICT", "PAY-" + r.get("serviceNo"),
                        toIdList(r.get("voucherIds")), "CONFLICT",
                        "服务 " + r.get("serviceNo") + " 存在多方已支付声明但币种/税额不一致，"
                                + "保留两个独立声明并标记支付状态冲突，不认定任何一方为真实付款"));
            }
        }
        return out;
    }

    private Map<String, Object> save(long ws, String type, String group, Object ids, String status, String desc) {
        long id = db.insertReturningId("""
                INSERT INTO duplicate_finding(workspace_id, finding_type, dedup_group, voucher_ids, status, description)
                VALUES (?, ?, ?, ?::jsonb, ?, ?) RETURNING id
                """, ws, type, group, Json.stringify(ids), status, desc);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("findingType", type);
        row.put("dedupGroup", group);
        row.put("voucherIds", ids);
        row.put("status", status);
        row.put("description", desc);
        return row;
    }

    /** PostgreSQL array_agg 返回 java.sql.Array，统一转换为 List。 */
    private List<Object> toIdList(Object o) {
        if (o instanceof java.sql.Array sqlArray) {
            try {
                Object arr = sqlArray.getArray();
                return arr instanceof Object[] objects ? new ArrayList<>(List.of(objects)) : List.of();
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("读取聚合凭证 ID 失败", e);
            }
        }
        if (o instanceof Object[] objects) {
            return new ArrayList<>(List.of(objects));
        }
        if (o instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }
}
