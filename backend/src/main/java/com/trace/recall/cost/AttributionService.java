package com.trace.recall.cost;

import com.trace.recall.common.Json;
import com.trace.recall.common.JdbcSupport;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 归因规则册执行器。
 *
 * 对每条凭证行给出 DIRECT（直接归因）/ ALLOCATED（按规则分摊）/ RELATED_ONLY（仅相关不可归因）。
 * 强制约束：
 * - 一张账单关联多个批次范围但缺少重量/箱数/合同分摊条款时，必须 RELATED_ONLY，
 *   编译器绝不按批次数平均分摊；
 * - 服务对象不在所选已发布范围内、证据等级不足或缺少合同/时间窗依据时，不得直接归因；
 * - 每次判断记录命中的规则编号与规则册版本，供审计投影回查。
 */
@Service
public class AttributionService {

    private final JdbcSupport db;

    public AttributionService(JdbcSupport db) {
        this.db = db;
    }

    public List<Map<String, Object>> evaluate(long workspaceId, String ruleBookVersion, Set<String> scopeBatches) {
        Map<String, Object> book = db.queryOne(
                "SELECT rule_book_version, rules FROM attribution_rule_book WHERE rule_book_version = ?",
                ruleBookVersion);
        Map<String, Object> bookRules = AttributionService.asMap(book == null ? Map.of() : book.get("rules"));
        List<Map<String, Object>> rules = castList(bookRules.get("items"));
        // 口径可被规则册版本修订：V1 允许 GRADE-B 直接归因，V2 收紧为仅 GRADE-A
        String directMinGrade = String.valueOf(bookRules.getOrDefault("directMinGrade", "GRADE-B"));

        List<Map<String, Object>> vouchers = db.query("""
                SELECT id, voucher_code, voucher_type, contract_no, target_batch_codes, target_node,
                       evidence_grade, allocation_terms, accrual_basis_verified, service_date, base_net_amount,
                       net_amount, currency
                FROM cost_voucher WHERE workspace_id = ? ORDER BY id
                """, workspaceId);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> v : vouchers) {
            long voucherId = ((Number) v.get("id")).longValue();
            List<String> targets = castList(v.get("targetBatchCodes"));
            String grade = String.valueOf(v.get("evidenceGrade"));
            Map<String, Object> terms = asMap(v.get("allocationTerms"));
            boolean inScope = targets.stream().anyMatch(scopeBatches::contains);
            String hitRule = matchRule(rules, v, targets, terms, inScope);

            if (targets.isEmpty() || !inScope) {
                results.add(save(workspaceId, voucherId, ruleBookVersion, hitRule, "RELATED_ONLY", null,
                        null, "UNATTRIBUTABLE",
                        targets.isEmpty() ? "凭证缺少可验证的服务对象标识" : "服务对象不在所选已发布范围内，仅相关不可归因",
                        Map.of("targetBatches", targets, "inScope", inScope)));
                continue;
            }

            boolean directEligible = targets.size() == 1
                    && gradeMeets(grade, directMinGrade)
                    && Boolean.TRUE.equals(v.get("accrualBasisVerified"))
                    && v.get("contractNo") != null
                    && v.get("serviceDate") != null;
            if (directEligible) {
                Map<String, Object> basis = new LinkedHashMap<>();
                basis.put("targetBatches", targets);
                basis.put("evidenceGrade", grade);
                basis.put("contractNo", v.get("contractNo"));
                basis.put("serviceDate", String.valueOf(v.get("serviceDate")));
                basis.put("note", "服务对象唯一且在范围内，证据等级/合同/时间窗/应计依据齐备");
                results.add(save(workspaceId, voucherId, ruleBookVersion, hitRule, "DIRECT", targets.get(0),
                        (BigDecimal) v.get("baseNetAmount"), "ATTRIBUTED", "直接归因", basis));
                continue;
            }

            if (targets.size() > 1 && hasCompleteAllocationBasis(terms, targets)) {
                results.addAll(allocate(workspaceId, voucherId, ruleBookVersion, hitRule, v, targets, terms));
                continue;
            }

            if (targets.size() > 1) {
                results.add(save(workspaceId, voucherId, ruleBookVersion, hitRule, "RELATED_ONLY", null,
                        null, "UNATTRIBUTABLE",
                        "账单关联多个批次范围但缺少重量/箱数/合同分摊条款，禁止按批次数平均分摊",
                        Map.of("targetBatches", targets, "allocationTerms", terms)));
                continue;
            }

            // 单一对象但依据不齐（等级不足/缺合同/应计未证实/时间窗缺失）
            List<String> missing = new ArrayList<>();
            if (!gradeMeets(grade, directMinGrade)) {
                missing.add("证据等级不足(" + grade + "，口径要求 " + directMinGrade + ")");
            }
            if (!Boolean.TRUE.equals(v.get("accrualBasisVerified"))) {
                missing.add("应计依据未证实");
            }
            if (v.get("contractNo") == null) {
                missing.add("缺少合同条款");
            }
            if (v.get("serviceDate") == null) {
                missing.add("缺少服务时间窗");
            }
            results.add(save(workspaceId, voucherId, ruleBookVersion, hitRule, "RELATED_ONLY", null,
                    null, "UNATTRIBUTABLE", "直接归因条件不满足：" + String.join("、", missing),
                    Map.of("targetBatches", targets, "evidenceGrade", grade)));
        }
        return results;
    }

    private List<Map<String, Object>> allocate(long workspaceId, long voucherId, String bookVersion, String rule,
                                               Map<String, Object> v, List<String> targets,
                                               Map<String, Object> terms) {
        List<Map<String, Object>> shares = castList(terms.get("shares"));
        BigDecimal totalWeight = shares.stream()
                .map(s -> new BigDecimal(String.valueOf(((Map<?, ?>) s).get("weight"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal base = v.get("baseNetAmount") == null ? null : (BigDecimal) v.get("baseNetAmount");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> share : shares) {
            String batch = String.valueOf(share.get("batchCode"));
            if (!targets.contains(batch)) {
                continue;
            }
            BigDecimal weight = new BigDecimal(String.valueOf(share.get("weight")));
            BigDecimal ratio = weight.divide(totalWeight, 8, RoundingMode.HALF_UP);
            BigDecimal allocated = base == null ? null
                    : base.multiply(ratio).setScale(4, RoundingMode.HALF_UP);
            Map<String, Object> basis = new LinkedHashMap<>();
            basis.put("basis", terms.getOrDefault("basis", "WEIGHT"));
            basis.put("batchWeight", weight);
            basis.put("totalWeight", totalWeight);
            basis.put("ratio", ratio);
            basis.put("note", "按规则册分摊条款（重量/箱数/合同比例）计算，非按批次数平均");
            out.add(save(workspaceId, voucherId, bookVersion, rule, "ALLOCATED", batch, allocated,
                    "ATTRIBUTED", "按规则分摊", basis));
        }
        return out;
    }

    private boolean hasCompleteAllocationBasis(Map<String, Object> terms, List<String> targets) {
        if (terms == null || terms.isEmpty() || !terms.containsKey("shares")) {
            return false;
        }
        List<Map<String, Object>> shares = castList(terms.get("shares"));
        if (shares.size() != targets.size()) {
            return false;
        }
        for (String batch : targets) {
            boolean covered = shares.stream().anyMatch(s ->
                    batch.equals(String.valueOf(((Map<?, ?>) s).get("batchCode")))
                            && ((Map<?, ?>) s).get("weight") != null);
            if (!covered) {
                return false;
            }
        }
        return true;
    }

    private boolean gradeMeets(String grade, String minimum) {
        int rank = switch (grade) {
            case "GRADE-A" -> 1;
            case "GRADE-B" -> 2;
            default -> 3;
        };
        int minRank = switch (minimum) {
            case "GRADE-A" -> 1;
            case "GRADE-B" -> 2;
            default -> 3;
        };
        return rank <= minRank;
    }

    private String matchRule(List<Map<String, Object>> rules, Map<String, Object> voucher,
                             List<String> targets, Map<String, Object> terms, boolean inScope) {
        for (Map<String, Object> rule : rules) {
            Object types = rule.get("voucherTypes");
            if (types instanceof List && !((List<?>) types).contains(voucher.get("voucherType"))) {
                continue;
            }
            String condition = String.valueOf(rule.get("condition"));
            boolean matched = switch (condition) {
                case "SINGLE_TARGET_DIRECT" -> targets.size() == 1 && inScope;
                case "MULTI_TARGET_WITH_TERMS" -> targets.size() > 1 && hasCompleteAllocationBasis(terms, targets);
                case "MULTI_TARGET_NO_TERMS" -> targets.size() > 1 && !hasCompleteAllocationBasis(terms, targets);
                case "OUT_OF_SCOPE_OR_NO_TARGET" -> targets.isEmpty() || !inScope;
                default -> true;
            };
            if (matched) {
                return String.valueOf(rule.get("ruleCode"));
            }
        }
        return null;
    }

    private Map<String, Object> save(long workspaceId, long voucherId, String bookVersion, String ruleCode,
                                     String classification, String batch, BigDecimal allocated,
                                     String status, String reason, Map<String, Object> basis) {
        long id = db.insertReturningId("""
                INSERT INTO attribution_result(workspace_id, voucher_id, rule_book_version, rule_code,
                    classification, batch_code, allocated_base_amount, attribution_status, reason, basis)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id
                """, workspaceId, voucherId, bookVersion, ruleCode, classification, batch,
                allocated, status, reason, Json.stringify(basis));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("voucherId", voucherId);
        row.put("ruleBookVersion", bookVersion);
        row.put("ruleCode", ruleCode);
        row.put("classification", classification);
        row.put("batchCode", batch);
        row.put("allocatedBaseAmount", allocated);
        row.put("attributionStatus", status);
        row.put("reason", reason);
        row.put("basis", basis);
        return row;
    }

    @SuppressWarnings("unchecked")
    static <T> List<T> castList(Object o) {
        return o instanceof List ? (List<T>) o : List.of();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }
}
