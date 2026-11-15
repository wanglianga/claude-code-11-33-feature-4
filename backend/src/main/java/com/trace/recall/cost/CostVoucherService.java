package com.trace.recall.cost;

import com.trace.recall.common.ApiException;
import com.trace.recall.common.Json;
import com.trace.recall.common.JdbcSupport;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 成本受理台 + 凭证身份簿。
 *
 * 登记原始凭证（发票/检测单/运输账单/仓储计费单/服务验收单/会计凭证/贷项/冲销），
 * 并以“外部单号 | 行项目 | 合同号 | 文件指纹”组成身份键生成去重候选。
 * 相似金额从不参与身份判定，绝不自动并单。
 */
@Service
public class CostVoucherService {

    private final JdbcSupport db;

    public CostVoucherService(JdbcSupport db) {
        this.db = db;
    }

    public long register(long workspaceId, Map<String, Object> v) {
        Long dup = db.jdbc().queryForObject(
                "SELECT count(*) FROM cost_voucher WHERE workspace_id = ? AND voucher_code = ?",
                Long.class, workspaceId, str(v, "voucherCode"));
        if (dup != null && dup > 0) {
            throw ApiException.conflict("工作区内凭证编号已存在: " + str(v, "voucherCode"));
        }
        BigDecimal net = dec(v, "netAmount");
        long voucherId = db.insertReturningId("""
                INSERT INTO cost_voucher(workspace_id, voucher_code, voucher_type, external_doc_no, line_item_no,
                    contract_no, source_system, file_name, file_hash, currency, gross_amount, tax_amount,
                    net_amount, exchange_rate, fx_rate_source, fx_rate_date, fx_verified, base_net_amount,
                    service_date, acceptance_date, posting_date, service_period_start, service_period_end,
                    target_batch_codes, target_node, evidence_grade, allocation_terms, accrual_basis_verified,
                    referenced_doc_no, referenced_line_no, service_no, payment_claim, extra)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?,
                        ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                RETURNING id
                """,
                workspaceId, str(v, "voucherCode"), str(v, "voucherType"), str(v, "externalDocNo"),
                str(v, "lineItemNo"), str(v, "contractNo"), str(v, "sourceSystem"), str(v, "fileName"),
                str(v, "fileHash"), str(v, "currency"), dec(v, "grossAmount"), dec(v, "taxAmount"),
                net, dec(v, "exchangeRate"), str(v, "fxRateSource"), date(v, "fxRateDate"),
                bool(v, "fxVerified"), dec(v, "baseNetAmount"),
                date(v, "serviceDate"), date(v, "acceptanceDate"), date(v, "postingDate"),
                date(v, "servicePeriodStart"), date(v, "servicePeriodEnd"),
                Json.stringify(list(v, "targetBatchCodes")), str(v, "targetNode"),
                strOrDefault(v, "evidenceGrade", "GRADE-B"),
                Json.stringify(mapOrDefault(v, "allocationTerms", Map.of())),
                bool(v, "accrualBasisVerified"), str(v, "referencedDocNo"), str(v, "referencedLineNo"),
                str(v, "serviceNo"),
                Json.stringify(mapOrDefault(v, "paymentClaim", Map.of())),
                Json.stringify(mapOrDefault(v, "extra", Map.of())));

        String identityKey = String.join("|",
                nz(str(v, "externalDocNo")), nz(str(v, "lineItemNo")),
                nz(str(v, "contractNo")), nz(str(v, "fileHash")));
        Long sameKey = db.jdbc().queryForObject(
                "SELECT count(*) FROM voucher_identity WHERE workspace_id = ? AND identity_key = ?",
                Long.class, workspaceId, identityKey);
        String group = sameKey != null && sameKey > 0 ? "DG-" + Math.abs(identityKey.hashCode()) : null;
        String status = group == null ? "UNIQUE" : "DUPLICATE_CANDIDATE";
        if (group != null) {
            db.update("UPDATE voucher_identity SET dedup_status = 'DUPLICATE_CANDIDATE', dedup_group = ? WHERE workspace_id = ? AND identity_key = ?",
                    group, workspaceId, identityKey);
        }
        db.update("""
                INSERT INTO voucher_identity(workspace_id, voucher_id, identity_key, dedup_status, dedup_group, note)
                VALUES (?, ?, ?, ?, ?, ?)
                """, workspaceId, voucherId, identityKey, status, group,
                "身份键=外部单号|行项目|合同号|文件指纹；相似金额不作为同一笔成本的依据");
        return voucherId;
    }

    public List<Map<String, Object>> listVouchers(long workspaceId) {
        return db.query("""
                SELECT id, workspace_id, voucher_code, voucher_type, external_doc_no, line_item_no, contract_no,
                       source_system, file_name, file_hash, currency, gross_amount, tax_amount, net_amount,
                       exchange_rate, fx_rate_source, fx_rate_date, fx_verified, base_net_amount,
                       service_date, acceptance_date, posting_date, service_period_start, service_period_end,
                       target_batch_codes, target_node, evidence_grade, allocation_terms, accrual_basis_verified,
                       referenced_doc_no, referenced_line_no, service_no, payment_claim, extra, registered_at
                FROM cost_voucher WHERE workspace_id = ? ORDER BY id
                """, workspaceId);
    }

    static String str(Map<String, Object> v, String key) {
        Object o = v.get(key);
        return o == null ? null : String.valueOf(o);
    }

    static String strOrDefault(Map<String, Object> v, String key, String def) {
        String s = str(v, key);
        return s == null || s.isBlank() ? def : s;
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }

    static BigDecimal dec(Map<String, Object> v, String key) {
        Object o = v.get(key);
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        return new BigDecimal(String.valueOf(o));
    }

    static LocalDate date(Map<String, Object> v, String key) {
        Object o = v.get(key);
        return o == null || String.valueOf(o).isBlank() ? null : LocalDate.parse(String.valueOf(o));
    }

    static boolean bool(Map<String, Object> v, String key) {
        Object o = v.get(key);
        return o != null && Boolean.parseBoolean(String.valueOf(o));
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Map<String, Object> v, String key) {
        Object o = v.get(key);
        return o instanceof List ? (List<Object>) o : List.of();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapOrDefault(Map<String, Object> v, String key, Map<String, Object> def) {
        Object o = v.get(key);
        return o instanceof Map ? (Map<String, Object>) o : def;
    }
}
