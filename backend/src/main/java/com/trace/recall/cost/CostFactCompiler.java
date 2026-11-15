package com.trace.recall.cost;

import com.trace.recall.common.ApiException;
import com.trace.recall.common.Json;
import com.trace.recall.common.JdbcSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 成本事实编译器（编排核心）。
 *
 * 仅当一条金额同时满足以下条件才发布为 CONFIRMED 成本事实：
 * 1) 归因结论 ATTRIBUTED（DIRECT/ALLOCATED），规则与依据齐备；
 * 2) 期间校验 OK；合法跨期成本可发布，但同时保留应计期间、入账期间与期间差异标记；
 * 3) 未被未解决的重复计量结论占用（EXACT_DUPLICATE / 未配对贷项 / 支付冲突）；
 * 4) 外币凭证的汇率来源与适用日期按会计规则可验证；否则本币金额强制留空并列 FX_MISSING，
 *    绝不使用系统当前汇率补齐。
 *
 * 归属依据不足、重复关系未解、关键凭证缺失、应计依据缺失、汇率缺失、支付冲突 → PENDING 待核。
 * 每条事实同步生成审计投影（凭证行/归因规则版本/期间判断/重复结论/质量状态）。
 */
@Service
public class CostFactCompiler {

    private final JdbcSupport db;
    private final AttributionService attributionService;
    private final PeriodAssessmentService periodService;
    private final DuplicateDetectionService duplicateService;

    public CostFactCompiler(JdbcSupport db, AttributionService attributionService,
                            PeriodAssessmentService periodService, DuplicateDetectionService duplicateService) {
        this.db = db;
        this.attributionService = attributionService;
        this.periodService = periodService;
        this.duplicateService = duplicateService;
    }

    @Transactional
    public Map<String, Object> compile(long workspaceId) {
        Map<String, Object> ws = db.queryOne("""
                SELECT id, workspace_code, status, accounting_rule_code, rule_book_version,
                       selected_release_codes, period_id
                FROM cost_workspace WHERE id = ?
                """, workspaceId);
        if (ws == null) {
            throw ApiException.notFound("成本工作区不存在: " + workspaceId);
        }
        if ("PUBLISHED".equals(ws.get("status")) || "SUPERSEDED".equals(ws.get("status"))) {
            throw ApiException.conflict("工作区已发布/已被替代，结果只读保留；口径修订请创建新工作区，不得改写");
        }

        // 清空上一次编译产物（凭证保留），保证可重复编译
        db.update("DELETE FROM audit_view_version WHERE workspace_id = ?", workspaceId);
        db.update("DELETE FROM cost_fact WHERE workspace_id = ?", workspaceId);
        db.update("DELETE FROM duplicate_finding WHERE workspace_id = ?", workspaceId);
        db.update("DELETE FROM period_assessment WHERE workspace_id = ?", workspaceId);
        db.update("DELETE FROM attribution_result WHERE workspace_id = ?", workspaceId);

        Set<String> scopeBatches = loadScopeBatches(ws);
        boolean crossPeriodAllowed = Boolean.TRUE.equals(db.jdbc().queryForObject(
                "SELECT allows_cross_period FROM accounting_rule WHERE rule_code = ?",
                Boolean.class, ws.get("accountingRuleCode")));

        // 1) 归因
        List<Map<String, Object>> attributions = attributionService.evaluate(
                workspaceId, String.valueOf(ws.get("ruleBookVersion")), scopeBatches);
        Map<Long, List<Map<String, Object>>> attributionByVoucher = attributions.stream()
                .collect(Collectors.groupingBy(a -> ((Number) a.get("voucherId")).longValue()));
        Map<Long, String> attributedBatches = new HashMap<>();
        attributions.stream().filter(a -> "ATTRIBUTED".equals(a.get("attributionStatus")))
                .forEach(a -> attributedBatches.put(((Number) a.get("voucherId")).longValue(),
                        String.valueOf(a.get("batchCode"))));

        // 2) 期间校验
        List<Map<String, Object>> periods = periodService.assess(
                workspaceId, String.valueOf(ws.get("accountingRuleCode")), crossPeriodAllowed, attributedBatches);
        Map<Long, Map<String, Object>> periodByVoucher = periods.stream()
                .collect(Collectors.toMap(p -> ((Number) p.get("voucherId")).longValue(), p -> p));

        // 3) 重复计量
        List<Map<String, Object>> findings = duplicateService.detect(workspaceId);
        Map<Long, List<Map<String, Object>>> findingsByVoucher = indexFindings(workspaceId);

        List<Map<String, Object>> vouchers = db.query(
                "SELECT * FROM cost_voucher WHERE workspace_id = ? ORDER BY id", workspaceId);

        int confirmed = 0;
        int pending = 0;
        BigDecimal confirmedBaseTotal = BigDecimal.ZERO;
        List<Map<String, Object>> factViews = new ArrayList<>();

        for (Map<String, Object> v : vouchers) {
            long voucherId = ((Number) v.get("id")).longValue();
            List<Map<String, Object>> voucherAttributions =
                    attributionByVoucher.getOrDefault(voucherId, List.of());
            Map<String, Object> period = periodByVoucher.get(voucherId);
            List<Map<String, Object>> voucherFindings = findingsByVoucher.getOrDefault(voucherId, List.of());

            // 一条凭证若产生多个分摊行，则每个批次一条事实；否则一条汇总事实
            List<Map<String, Object>> allocated = voucherAttributions.stream()
                    .filter(a -> "ATTRIBUTED".equals(a.get("attributionStatus"))).toList();
            if (allocated.isEmpty()) {
                Map<String, Object> fact = buildFact(workspaceId, ws, v, null,
                        voucherAttributions.isEmpty() ? null : voucherAttributions.get(0),
                        period, voucherFindings);
                factViews.add(fact);
            } else {
                for (Map<String, Object> a : allocated) {
                    factViews.add(buildFact(workspaceId, ws, v, a, a, period, voucherFindings));
                }
            }
        }

        for (Map<String, Object> f : factViews) {
            long factId = persistFact(workspaceId, ws, f);
            writeAuditProjection(workspaceId, factId, f);
            if ("CONFIRMED".equals(f.get("qualityStatus"))) {
                confirmed++;
                if (f.get("baseNetAmount") != null) {
                    confirmedBaseTotal = confirmedBaseTotal.add((BigDecimal) f.get("baseNetAmount"));
                }
            } else {
                pending++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("voucherCount", vouchers.size());
        summary.put("confirmedCount", confirmed);
        summary.put("pendingCount", pending);
        summary.put("confirmedBaseTotal", confirmedBaseTotal);
        summary.put("findingCount", findings.size());
        summary.put("openFindingCount", findings.stream().filter(f -> !"RESOLVED".equals(f.get("status"))).count());
        db.update("UPDATE cost_workspace SET status = 'COMPILED', compiled_at = now(), summary = ?::jsonb WHERE id = ?",
                Json.stringify(summary), workspaceId);
        summary.put("workspaceId", workspaceId);
        return summary;
    }

    private Map<String, Object> buildFact(long ws, Map<String, Object> workspace, Map<String, Object> v,
                                          Map<String, Object> allocatedAttribution,
                                          Map<String, Object> anyAttribution,
                                          Map<String, Object> period,
                                          List<Map<String, Object>> findings) {
        List<String> pendingReasons = new ArrayList<>();
        String classification = allocatedAttribution != null
                ? String.valueOf(allocatedAttribution.get("classification"))
                : (anyAttribution != null ? String.valueOf(anyAttribution.get("classification")) : "RELATED_ONLY");
        String batch = allocatedAttribution != null ? String.valueOf(allocatedAttribution.get("batchCode")) : null;

        // 归因门槛
        boolean attributed = allocatedAttribution != null
                || (anyAttribution != null && "ATTRIBUTED".equals(anyAttribution.get("attributionStatus")));
        if (!attributed) {
            pendingReasons.add("ATTRIBUTION_INSUFFICIENT");
            if (Boolean.FALSE.equals(v.get("accrualBasisVerified"))) {
                pendingReasons.add("ACCRUAL_BASIS_MISSING");
            }
        } else if (Boolean.FALSE.equals(v.get("accrualBasisVerified"))) {
            pendingReasons.add("ACCRUAL_BASIS_MISSING");
        }

        // 期间门槛
        String accrualPeriod = null;
        String postingPeriod = null;
        boolean periodDiff = false;
        if (period != null) {
            accrualPeriod = (String) period.get("accrualPeriod");
            postingPeriod = (String) period.get("postingPeriod");
            periodDiff = Boolean.TRUE.equals(period.get("periodDiffFlag"));
            if ("PENDING".equals(period.get("assessmentStatus"))) {
                if (!pendingReasons.contains("ACCRUAL_BASIS_MISSING")) {
                    pendingReasons.add("ACCRUAL_BASIS_MISSING");
                }
            }
        } else {
            pendingReasons.add("VOUCHER_MISSING");
        }

        // 重复计量门槛：未解决或冲突结论占用该凭证
        List<Long> findingIds = findings.stream().map(f -> ((Number) f.get("id")).longValue()).toList();
        boolean blockedByDuplicate = findings.stream().anyMatch(f -> {
            String t = String.valueOf(f.get("findingType"));
            String st = String.valueOf(f.get("status"));
            if ("RESOLVED".equals(st)) {
                return false;
            }
            return "EXACT_DUPLICATE".equals(t) || "MULTI_PARTY_TRANSFER".equals(t)
                    || "SPLIT_BILL".equals(t) || "CREDIT_NOTICE_UNPAIRED".equals(t)
                    || "REVERSAL_UNPAIRED".equals(t) || "PAYMENT_CONFLICT".equals(t);
        });
        if (blockedByDuplicate) {
            pendingReasons.add("DUPLICATE_UNRESOLVED");
        }
        if (findings.stream().anyMatch(f -> "PAYMENT_CONFLICT".equals(f.get("findingType")))) {
            pendingReasons.add("PAYMENT_CONFLICT");
        }

        // 外汇门槛：外币必须有可验证汇率来源/日期，否则本币金额强制留空
        BigDecimal baseAmount = allocatedAttribution != null
                ? (BigDecimal) allocatedAttribution.get("allocatedBaseAmount")
                : (BigDecimal) v.get("baseNetAmount");
        String currency = String.valueOf(v.get("currency"));
        boolean foreign = !"CNY".equals(currency);
        boolean fxVerifiable = !foreign
                || (Boolean.TRUE.equals(v.get("fxVerified"))
                        && v.get("exchangeRate") != null
                        && v.get("fxRateSource") != null
                        && v.get("fxRateDate") != null);
        if (foreign && !fxVerifiable) {
            pendingReasons.add("FX_MISSING");
            baseAmount = null; // 强制留空，绝不用当前汇率补齐
        }

        String qualityStatus = pendingReasons.isEmpty() ? "CONFIRMED" : "PENDING";
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("voucherId", v.get("id"));
        fact.put("voucher", v);
        fact.put("batchCode", batch);
        fact.put("classification", classification);
        fact.put("currency", currency);
        fact.put("originalNetAmount", v.get("netAmount"));
        fact.put("taxAmount", v.get("taxAmount"));
        fact.put("baseNetAmount", baseAmount);
        fact.put("fxRateSource", v.get("fxRateSource"));
        fact.put("accrualPeriod", accrualPeriod);
        fact.put("postingPeriod", postingPeriod);
        fact.put("periodDiffFlag", periodDiff);
        fact.put("qualityStatus", qualityStatus);
        fact.put("pendingReasons", pendingReasons.stream().distinct().toList());
        fact.put("attribution", anyAttribution);
        fact.put("period", period);
        fact.put("findings", findings);
        fact.put("findingIds", findingIds);
        fact.put("ruleBookVersion", workspace.get("ruleBookVersion"));
        fact.put("accountingRuleCode", workspace.get("accountingRuleCode"));
        return fact;
    }

    private long persistFact(long ws, Map<String, Object> workspace, Map<String, Object> f) {
        Map<String, Object> v = (Map<String, Object>) f.get("voucher");
        long no = db.jdbc().queryForObject(
                "SELECT coalesce(max(id), 0) + 1 FROM cost_fact WHERE workspace_id = ?", Long.class, ws);
        String factCode = "CF-" + String.format("%04d", no);
        return db.insertReturningId("""
                INSERT INTO cost_fact(workspace_id, fact_code, voucher_id, batch_code, classification,
                    voucher_type, currency, original_net_amount, tax_amount, base_net_amount, fx_rate_source,
                    accrual_period, posting_period, period_diff_flag, quality_status, pending_reasons,
                    attribution_rule_version, accounting_rule_code, duplicate_finding_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb)
                RETURNING id
                """, ws, factCode, f.get("voucherId"), f.get("batchCode"), f.get("classification"),
                v.get("voucherType"), f.get("currency"), f.get("originalNetAmount"), f.get("taxAmount"),
                f.get("baseNetAmount"), f.get("fxRateSource"), f.get("accrualPeriod"), f.get("postingPeriod"),
                f.get("periodDiffFlag"), f.get("qualityStatus"), Json.stringify(f.get("pendingReasons")),
                f.get("ruleBookVersion"), f.get("accountingRuleCode"), Json.stringify(f.get("findingIds")));
    }

    private void writeAuditProjection(long ws, long factId, Map<String, Object> f) {
        Map<String, Object> v = (Map<String, Object>) f.get("voucher");
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("fact", Map.of(
                "qualityStatus", f.get("qualityStatus"),
                "classification", f.get("classification"),
                "batchCode", f.get("batchCode") == null ? "" : f.get("batchCode"),
                "accrualPeriod", f.get("accrualPeriod") == null ? "" : f.get("accrualPeriod"),
                "postingPeriod", f.get("postingPeriod") == null ? "" : f.get("postingPeriod"),
                "periodDiffFlag", f.get("periodDiffFlag"),
                "pendingReasons", f.get("pendingReasons")));
        projection.put("voucherLine", Map.of(
                "voucherCode", v.get("voucherCode"),
                "voucherType", v.get("voucherType"),
                "externalDocNo", v.get("externalDocNo"),
                "lineItemNo", v.get("lineItemNo"),
                "contractNo", v.get("contractNo") == null ? "" : v.get("contractNo"),
                "fileHash", v.get("fileHash"),
                "sourceSystem", v.get("sourceSystem"),
                "currency", v.get("currency"),
                "netAmount", v.get("netAmount"),
                "fxVerified", v.get("fxVerified")));
        projection.put("attributionRuleVersion", f.get("ruleBookVersion"));
        projection.put("attribution", f.get("attribution") == null ? Map.of() : f.get("attribution"));
        projection.put("periodAssessment", f.get("period") == null ? Map.of() : f.get("period"));
        projection.put("duplicateFindings", f.get("findings"));
        db.update("""
                INSERT INTO audit_view_version(workspace_id, fact_id, view_version, projection)
                VALUES (?, ?, ?, ?::jsonb)
                """, ws, factId, f.get("ruleBookVersion"), Json.stringify(projection));
    }

    @SuppressWarnings("unchecked")
    private Set<String> loadScopeBatches(Map<String, Object> ws) {
        List<String> releaseCodes = (List<String>) ws.get("selectedReleaseCodes");
        Set<String> batches = new java.util.TreeSet<>();
        if (releaseCodes == null) {
            return batches;
        }
        for (String code : releaseCodes) {
            List<String> items = db.jdbc().queryForList("""
                    SELECT ri.batch_code
                    FROM release_item ri JOIN release_version rv ON rv.id = ri.release_id
                    WHERE rv.release_code = ?
                    """, String.class, code);
            batches.addAll(items);
        }
        return batches;
    }

    private Map<Long, List<Map<String, Object>>> indexFindings(long ws) {
        List<Map<String, Object>> findings = db.query(
                "SELECT id, finding_type, dedup_group, voucher_ids, status, description FROM duplicate_finding WHERE workspace_id = ?",
                ws);
        Map<Long, List<Map<String, Object>>> index = new HashMap<>();
        for (Map<String, Object> f : findings) {
            for (Object o : AttributionService.castList(f.get("voucherIds"))) {
                long voucherId = ((Number) o).longValue();
                index.computeIfAbsent(voucherId, k -> new ArrayList<>()).add(f);
            }
        }
        return index;
    }
}
