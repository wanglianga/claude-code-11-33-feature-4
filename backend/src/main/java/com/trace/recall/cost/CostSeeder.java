package com.trace.recall.cost;

import com.trace.recall.common.Json;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 成本域主数据播种器（幂等，晚于基线 DemoSeeder 执行）。
 * 仅播种会计期间、会计规则与归因规则册；演示工作区与凭证由 CostDemoScenarioService 按需生成。
 */
@Component
@Order(20)
public class CostSeeder implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public CostSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM accounting_period", Long.class);
        if (count != null && count > 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO accounting_period(period_code, period_name, period_start, period_end, closed_at, status)
                VALUES ('AP-2026-10', '2026 年 10 月会计期间（已关账）', '2026-10-01', '2026-10-31',
                        '2026-11-05T18:00:00+08:00', 'CLOSED'),
                       ('AP-2026-11', '2026 年 11 月会计期间（当前）', '2026-11-01', '2026-11-30', NULL, 'OPEN')
                """);

        jdbc.update("""
                INSERT INTO accounting_rule(rule_code, rule_name, allows_cross_period, fx_policy, period_basis,
                    effective_at, supersedes_rule_code, status)
                VALUES (?, ?, ?, ?::jsonb, 'ACCRUAL', '2026-01-01T00:00:00+08:00', NULL, 'ACTIVE'),
                       (?, ?, ?, ?::jsonb, 'ACCRUAL', '2026-01-01T00:00:00+08:00', NULL, 'ACTIVE')
                """,
                "AR-ACCRUAL-V1", "权责发生制·允许跨期应计", true,
                Json.stringify(Map.of(
                        "verifiableRequired", true,
                        "rateSourcePriority", List.of("PBoC_MIDDLE_RATE", "CONTRACT_AGREED_RATE"),
                        "applicableDate", "SERVICE_DATE",
                        "note", "外币必须可验证来源与适用日期，禁止用系统当前汇率补齐")),
                "AR-ACCRUAL-STRICT", "权责发生制·不允许跨期应计", false,
                Json.stringify(Map.of(
                        "verifiableRequired", true,
                        "rateSourcePriority", List.of("PBoC_MIDDLE_RATE"),
                        "applicableDate", "SERVICE_DATE",
                        "note", "跨期成本不得直接发布，须人工裁定")));

        // 归因规则册 V1：GRADE-B 及以上可直接归因
        Map<String, Object> v1 = Map.of(
                "directMinGrade", "GRADE-B",
                "items", List.of(
                        Map.of("ruleCode", "ARB1-DIRECT", "condition", "SINGLE_TARGET_DIRECT",
                                "voucherTypes", List.of("SUPPLIER_INVOICE", "INSPECTION_ORDER", "TRANSPORT_BILL",
                                        "WAREHOUSE_BILL", "SERVICE_ACCEPTANCE", "ACCOUNTING_VOUCHER",
                                        "DISPOSAL_BILL", "THIRD_PARTY_SERVICE"),
                                "classification", "DIRECT",
                                "description", "单一服务对象且在发布范围内，证据等级≥GRADE-B、合同/时间窗/应计依据齐备（含报废处理与第三方服务凭证）"),
                        Map.of("ruleCode", "ARB1-ALLOC", "condition", "MULTI_TARGET_WITH_TERMS",
                                "voucherTypes", List.of("TRANSPORT_BILL", "WAREHOUSE_BILL", "DISPOSAL_BILL",
                                        "THIRD_PARTY_SERVICE"),
                                "classification", "ALLOCATED",
                                "description", "多批次账单且具备重量/箱数/合同分摊条款，按条款比例分摊"),
                        Map.of("ruleCode", "ARB1-NOBASIS", "condition", "MULTI_TARGET_NO_TERMS",
                                "voucherTypes", List.of("TRANSPORT_BILL", "WAREHOUSE_BILL", "DISPOSAL_BILL",
                                        "THIRD_PARTY_SERVICE"),
                                "classification", "RELATED_ONLY",
                                "description", "多批次账单无分摊依据，仅相关不可归因，禁止按批次数平均"),
                        Map.of("ruleCode", "ARB1-OUTSCOPE", "condition", "OUT_OF_SCOPE_OR_NO_TARGET",
                                "voucherTypes", List.of(), "classification", "RELATED_ONLY",
                                "description", "无服务对象或对象不在所选发布范围内")));
        // 归因规则册 V2（期末后修订）：口径收紧为仅 GRADE-A 可直接归因；原始凭证不变
        Map<String, Object> v2 = Map.of(
                "directMinGrade", "GRADE-A",
                "items", v1.get("items"));
        jdbc.update("""
                INSERT INTO attribution_rule_book(rule_book_version, effective_at, status, description, rules)
                VALUES ('ARB-V1', '2026-10-01T00:00:00+08:00', 'SUPERSEDED',
                        '初版归因口径：GRADE-B 及以上可直接归因', ?::jsonb),
                       ('ARB-V2', '2026-11-12T00:00:00+08:00', 'ACTIVE',
                        '期末后修订：直接归因证据等级收紧为 GRADE-A，仅改分类口径，不改写原始凭证与已关闭期间记录',
                        ?::jsonb)
                """, Json.stringify(v1), Json.stringify(v2));
    }
}
