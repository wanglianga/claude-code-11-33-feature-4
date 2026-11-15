package com.trace.recall.cost;

import com.trace.recall.common.Json;
import com.trace.recall.common.JdbcSupport;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 期间校验器。
 *
 * 依据服务发生日、验收日、入账日、关账状态与所选会计规则，确定应计期间、入账期间与归属依据。
 *
 * 强制规则：
 * - 应计期间取自服务发生日（缺失时取服务期间起始日），入账期间取自入账日；二者不同 → period_diff_flag；
 * - 服务发生在已关闭期间、验收/入账在当前期间，且会计规则允许跨期应计，
 *   同时服务对象、金额归因、应计依据均已证实 → 合法跨期（cross_period_legal=true），
 *   同时保留两个期间，编译器可发布并携带期间差异标记；
 * - 应计依据、服务对象或归因规则缺失 → assessment_status=PENDING，
 *   绝不能用当前入账日覆盖服务事实。
 */
@Service
public class PeriodAssessmentService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final JdbcSupport db;

    public PeriodAssessmentService(JdbcSupport db) {
        this.db = db;
    }

    public List<Map<String, Object>> assess(long workspaceId, String accountingRuleCode,
                                            boolean crossPeriodAllowed, Map<Long, String> attributedBatches) {
        Map<String, Object> ws = db.queryOne("""
                SELECT w.id, p.period_code, p.period_end, p.status AS period_status
                FROM cost_workspace w JOIN accounting_period p ON p.id = w.period_id
                WHERE w.id = ?
                """, workspaceId);
        LocalDate periodEnd = LocalDate.parse(String.valueOf(ws.get("periodEnd")));

        List<Map<String, Object>> vouchers = db.query("""
                SELECT id, voucher_code, service_date, acceptance_date, posting_date,
                       service_period_start, service_period_end, accrual_basis_verified, target_batch_codes
                FROM cost_voucher WHERE workspace_id = ? ORDER BY id
                """, workspaceId);

        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> v : vouchers) {
            long voucherId = ((Number) v.get("id")).longValue();
            LocalDate serviceDate = (LocalDate) v.get("serviceDate");
            LocalDate periodStart = (LocalDate) v.get("servicePeriodStart");
            LocalDate acceptanceDate = (LocalDate) v.get("acceptanceDate");
            LocalDate postingDate = (LocalDate) v.get("postingDate");
            LocalDate accrualDate = serviceDate != null ? serviceDate : periodStart;
            boolean basisVerified = Boolean.TRUE.equals(v.get("accrualBasisVerified"));
            boolean hasTarget = !AttributionService.castList(v.get("targetBatchCodes")).isEmpty();
            boolean attributed = attributedBatches.containsKey(voucherId);

            String accrualPeriod = accrualDate == null ? null : accrualDate.format(MONTH);
            String postingPeriod = postingDate == null ? null : postingDate.format(MONTH);
            boolean diff = accrualPeriod != null && postingPeriod != null && !accrualPeriod.equals(postingPeriod);

            List<String> missing = new java.util.ArrayList<>();
            if (accrualDate == null) {
                missing.add("缺少服务发生日/服务期间，无法确定应计期间");
            }
            if (!basisVerified) {
                missing.add("应计依据未证实");
            }
            if (!hasTarget) {
                missing.add("缺少服务对象标识");
            }
            if (!attributed) {
                missing.add("归因规则未给出可归因结论");
            }
            if (postingDate == null) {
                missing.add("缺少入账日期");
            }

            boolean crossPeriod = false;
            String reason;
            String status = "OK";
            if (!missing.isEmpty()) {
                status = "PENDING";
                reason = "期间归属依据不足：" + String.join("；", missing) + "。不得以入账日覆盖服务事实。";
            } else {
                boolean serviceInClosedPeriod = !accrualDate.isAfter(periodEnd);
                boolean postedAfterClose = postingDate.isAfter(periodEnd);
                if (diff && serviceInClosedPeriod && postedAfterClose) {
                    if (crossPeriodAllowed) {
                        crossPeriod = true;
                        reason = "服务发生在已关闭期间、入账在当前期间，会计规则允许跨期应计且三要素（对象/金额/应计依据）已证实，"
                                + "确认为合法跨期成本，同时保留应计期间 " + accrualPeriod + " 与入账期间 " + postingPeriod;
                    } else {
                        status = "PENDING";
                        reason = "存在跨期但所选会计规则不允许跨期应计，需人工裁定";
                    }
                } else if (diff) {
                    reason = "应计期间与入账期间不同，已分别保留并标记期间差异";
                } else {
                    reason = "应计期间与入账期间一致，期间归属完整";
                }
            }

            Map<String, Object> basis = new LinkedHashMap<>();
            basis.put("serviceDate", String.valueOf(serviceDate));
            basis.put("acceptanceDate", String.valueOf(acceptanceDate));
            basis.put("postingDate", String.valueOf(postingDate));
            basis.put("closedPeriodEnd", periodEnd.toString());
            basis.put("crossPeriodAllowedByRule", crossPeriodAllowed);
            basis.put("missing", missing);

            long id = db.insertReturningId("""
                    INSERT INTO period_assessment(workspace_id, voucher_id, accounting_rule_code, accrual_period,
                        posting_period, period_diff_flag, cross_period_legal, assessment_status, reason, basis)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id
                    """, workspaceId, voucherId, accountingRuleCode, accrualPeriod, postingPeriod, diff,
                    crossPeriod, status, reason, Json.stringify(basis));
            Map<String, Object> row = new LinkedHashMap<>(basis);
            row.put("id", id);
            row.put("voucherId", voucherId);
            row.put("accrualPeriod", accrualPeriod);
            row.put("postingPeriod", postingPeriod);
            row.put("periodDiffFlag", diff);
            row.put("crossPeriodLegal", crossPeriod);
            row.put("assessmentStatus", status);
            row.put("reason", reason);
            out.add(row);
        }
        return out;
    }
}
