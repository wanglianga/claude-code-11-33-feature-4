package com.trace.recall.cost;

import com.trace.recall.common.JdbcSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成本域演示场景（通过接口显式触发，幂等）。
 *
 * 在已发布范围 REL-001/REL-002 之上建立一个成本工作区，登记覆盖六类验收样例的凭证：
 * 无分摊依据的多范围账单、未配对贷项通知、合法跨期应计、应计依据缺失、
 * 双来源支付冲突、精确重复、拆分/多方账单、缺失汇率、可验证汇率、按条款分摊、GRADE-B 口径差异。
 */
@Service
public class CostDemoScenarioService {

    private final JdbcSupport db;
    private final CostWorkspaceService workspaceService;
    private final CostVoucherService voucherService;

    public CostDemoScenarioService(JdbcSupport db, CostWorkspaceService workspaceService,
                                   CostVoucherService voucherService) {
        this.db = db;
        this.workspaceService = workspaceService;
        this.voucherService = voucherService;
    }

    @Transactional
    public Map<String, Object> ensureDemoScenario() {
        List<Long> existingIds = db.jdbc().queryForList(
                "SELECT id FROM cost_workspace WHERE name LIKE '演示场景%' ORDER BY id LIMIT 1",
                Long.class);
        if (!existingIds.isEmpty()) {
            return Map.of("workspaceId", existingIds.get(0), "reused", true);
        }
        Long periodId = db.jdbc().queryForObject(
                "SELECT id FROM accounting_period WHERE period_code = 'AP-2026-10'", Long.class);

        Map<String, Object> created = workspaceService.create(Map.of(
                "name", "演示场景·召回处置成本归因（关闭期间 2026-10）",
                "periodId", periodId,
                "accountingRuleCode", "AR-ACCRUAL-V1",
                "ruleBookVersion", "ARB-V1",
                "releaseCodes", List.of("REL-001", "REL-002"),
                "createdBy", "成本分析员-王某"));
        long ws = ((Number) created.get("workspaceId")).longValue();

        // 1) 无分摊依据的多范围运输账单 → 仅相关不可归因，禁止平均分摊
        register(ws, base("CV-MULTI-01", "TRANSPORT_BILL", "TRANSP-MULTI-01", "L1", "CTR-T-01",
                "CNY", 10600, 600, 10000, List.of("FG-C02", "FG-C03"), "GRADE-A",
                "2026-10-08", null, "2026-10-15", true, Map.of()));

        // 2) 合法跨期应计：服务在已关闭期间，验收/入账在当前期间，三要素齐备
        register(ws, with(base("CV-CROSS-01", "WAREHOUSE_BILL", "WH-CROSS-01", "L1", "CTR-W-01",
                "CNY", 5300, 300, 5000, List.of("RM-A04"), "GRADE-A",
                "2026-10-28", "2026-11-03", "2026-11-06", true, Map.of()),
                "servicePeriodStart", "2026-10-01", "servicePeriodEnd", "2026-10-31"));

        // 3) 跨期但应计依据/等级缺失 → 待核，不得以入账日覆盖服务事实
        register(ws, base("CV-NOBASIS-01", "SERVICE_ACCEPTANCE", "SVC-NOBASIS-01", "L1", null,
                "CNY", 2120, 120, 2000, List.of("FG-C01"), "GRADE-C",
                "2026-10-25", "2026-11-04", "2026-11-04", false, Map.of()));

        // 4a) 正数发票（可正常确认）
        register(ws, base("CV-INV-041", "SUPPLIER_INVOICE", "INV-041", "L1", "CTR-S-04",
                "CNY", 8480, 480, 8000, List.of("IM-B10"), "GRADE-A",
                "2026-10-10", "2026-10-11", "2026-10-15", true, Map.of()));
        // 4b) 未关联原发票行的贷项通知 → 待人工配对，配对前不得冲减任何批次
        Map<String, Object> credit = base("CV-CN-041", "CREDIT_NOTICE", "CN-041", "L1", "CTR-S-04",
                "CNY", -2120, -120, -2000, List.of("IM-B10"), "GRADE-A",
                "2026-10-20", "2026-10-20", "2026-10-22", true, Map.of());
        credit.put("referencedDocNo", null);
        credit.put("referencedLineNo", null);
        register(ws, credit);

        // 5a/5b) 同一检测服务双来源“已支付”声明，币种/税额不一致 → 支付状态冲突，双声明并存
        register(ws, with(base("CV-LAB-PAY", "INSPECTION_ORDER", "LAB-BILL-09", "L1", "CTR-LAB-09",
                "CNY", 10600, 600, 10000, List.of("RM-A03"), "GRADE-A",
                "2026-10-12", "2026-10-12", "2026-10-16", true, Map.of()),
                "serviceNo", "SVC-LAB-9",
                "sourceSystem", "QC_LAB",
                "paymentClaim", Map.of("paid", true, "source", "LABORATORY", "currency", "CNY", "tax", 600)));
        register(ws, with(base("CV-INST-PAY", "ACCOUNTING_VOUCHER", "INST-PAY-09", "L1", "CTR-LAB-09",
                "USD", 1484, 84, 1400, List.of("RM-A03"), "GRADE-A",
                "2026-10-12", "2026-10-13", "2026-10-16", true, Map.of()),
                "serviceNo", "SVC-LAB-9",
                "sourceSystem", "INSTITUTION",
                "fxVerified", false,
                "paymentClaim", Map.of("paid", true, "source", "INSTITUTION", "currency", "USD", "tax", 84)));

        // 6) 外币凭证缺少可验证汇率 → 本币金额留空、待核，禁止用当前汇率补齐
        register(ws, with(base("CV-FX-MISSING", "WAREHOUSE_BILL", "WH-FX-01", "L1", "CTR-W-FX",
                "USD", 3180, 180, 3000, List.of("FG-C03"), "GRADE-A",
                "2026-10-18", "2026-10-19", "2026-10-22", true, Map.of()),
                "fxVerified", false, "exchangeRate", null, "baseNetAmount", null));

        // 7) 外币但汇率来源/日期可验证 → 可确认
        register(ws, with(base("CV-FX-OK", "WAREHOUSE_BILL", "WH-FX-02", "L1", "CTR-W-FX",
                "EUR", 1060, 60, 1000, List.of("IM-B20"), "GRADE-A",
                "2026-10-15", "2026-10-16", "2026-10-20", true, Map.of()),
                "fxVerified", true, "exchangeRate", 7.800000, "fxRateSource", "PBoC_MIDDLE_RATE",
                "fxRateDate", "2026-10-15", "baseNetAmount", 7800));

        // 8a/8b) 身份键完全一致 → 精确重复候选
        Map<String, Object> dup1 = base("CV-DUP-1", "SUPPLIER_INVOICE", "INV-DUP-77", "L1", "CTR-DUP",
                "CNY", 3180, 180, 3000, List.of("FG-C01"), "GRADE-A",
                "2026-10-09", "2026-10-09", "2026-10-12", true, Map.of());
        dup1.put("fileHash", "sha256-dup-77");
        register(ws, dup1);
        Map<String, Object> dup2 = base("CV-DUP-2", "SUPPLIER_INVOICE", "INV-DUP-77", "L1", "CTR-DUP",
                "CNY", 3180, 180, 3000, List.of("FG-C01"), "GRADE-A",
                "2026-10-09", "2026-10-09", "2026-10-12", true, Map.of());
        dup2.put("fileHash", "sha256-dup-77");
        register(ws, dup2);

        // 9) 多批次但具备重量分摊条款 → 按规则分摊为两条事实（600/400）
        register(ws, with(base("CV-ALLOC-01", "TRANSPORT_BILL", "TRANSP-ALLOC-01", "L1", "CTR-T-ALLOC",
                "CNY", 1060, 60, 1000, List.of("FG-C02", "FG-C03"), "GRADE-A",
                "2026-10-19", "2026-10-19", "2026-10-23", true, Map.of()),
                "allocationTerms", Map.of("basis", "WEIGHT",
                        "shares", List.of(Map.of("batchCode", "FG-C02", "weight", 60),
                                Map.of("batchCode", "FG-C03", "weight", 40)))));

        // 10) 单一对象 GRADE-B：ARB-V1 可直接归因；口径修订到 ARB-V2 后降为待核
        register(ws, base("CV-GRADEB-01", "SUPPLIER_INVOICE", "INV-GRADEB-01", "L1", "CTR-GB",
                "CNY", 1590, 90, 1500, List.of("FG-C01"), "GRADE-B",
                "2026-10-14", "2026-10-14", "2026-10-18", true, Map.of()));

        return Map.of("workspaceId", ws, "reused", false);
    }

    private void register(long ws, Map<String, Object> v) {
        voucherService.register(ws, v);
    }

    private Map<String, Object> base(String code, String type, String docNo, String line, String contract,
                                     String currency, double gross, double tax, double net,
                                     List<String> targets, String grade, String serviceDate,
                                     String acceptanceDate, String postingDate, boolean accrual,
                                     Map<String, Object> terms) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("workspaceId", null);
        v.put("voucherCode", code);
        v.put("voucherType", type);
        v.put("externalDocNo", docNo);
        v.put("lineItemNo", line);
        v.put("contractNo", contract);
        v.put("sourceSystem", "ERP");
        v.put("fileName", code + ".pdf");
        v.put("fileHash", "sha256-" + code.toLowerCase());
        v.put("currency", currency);
        v.put("grossAmount", gross);
        v.put("taxAmount", tax);
        v.put("netAmount", net);
        v.put("baseNetAmount", "CNY".equals(currency) ? net : null);
        v.put("fxVerified", false);
        v.put("serviceDate", serviceDate);
        v.put("acceptanceDate", acceptanceDate);
        v.put("postingDate", postingDate);
        v.put("targetBatchCodes", targets);
        v.put("evidenceGrade", grade);
        v.put("accrualBasisVerified", accrual);
        v.put("allocationTerms", terms);
        return v;
    }

    private Map<String, Object> with(Map<String, Object> v, Object... kv) {
        for (int i = 0; i < kv.length; i += 2) {
            v.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return v;
    }
}
