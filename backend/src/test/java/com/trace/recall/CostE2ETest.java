package com.trace.recall;

import com.trace.recall.common.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 召回处置成本证据归因端到端测试，运行在独立 recall_test schema。
 * 覆盖六类验收样例：无分摊多范围账单、未配对贷项、跨期应计、双来源支付冲突、口径修订、缺失汇率，
 * 以及从任一成本事实行展开的审计投影完整性。
 */
class CostE2ETest extends BaseE2ETest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context).build();
        }
        return mvc;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String json) throws Exception {
        return Json.MAPPER.readValue(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String json) throws Exception {
        return Json.MAPPER.readValue(json, List.class);
    }

    private long factWsId() throws Exception {
        String json = mvc().perform(post("/api/cost/demo-scenario")).andReturn().getResponse().getContentAsString();
        return ((Number) map(json).get("workspaceId")).longValue();
    }

    @Test
    void rejectsWorkspaceWithUnknownReleaseOrEmptySelection() throws Exception {
        String periodsJson = mvc().perform(get("/api/cost/meta/periods"))
                .andReturn().getResponse().getContentAsString();
        long periodId = ((Number) ((List<Map<String, Object>>) list(periodsJson)).get(0).get("id")).longValue();
        // 不存在的发布范围 → 400
        mvc().perform(post("/api/cost/workspaces").contentType("application/json").content("""
                {"periodId":%d,"accountingRuleCode":"AR-ACCRUAL-V1","ruleBookVersion":"ARB-V1","releaseCodes":["REL-999"]}
                """.formatted(periodId))).andExpect(status().isBadRequest());
        // 空范围集合 → 400
        mvc().perform(post("/api/cost/workspaces").contentType("application/json").content("""
                {"periodId":%d,"accountingRuleCode":"AR-ACCRUAL-V1","ruleBookVersion":"ARB-V1","releaseCodes":[]}
                """.formatted(periodId))).andExpect(status().isBadRequest());
    }

    @Test
    void costAttributionSixAcceptanceScenarios() throws Exception {
        // 前置：两次核验与发布，演示场景依赖 REL-001/REL-002
        mvc().perform(post("/api/runs").contentType("application/json").content("""
                {"inspectionCorrectionVersion":"QC-REV-1","cutoffAt":"2026-11-10T18:00:00+08:00","createdBy":"测试员"}
                """)).andExpect(status().isOk());
        mvc().perform(post("/api/runs").contentType("application/json").content("""
                {"inspectionCorrectionVersion":"QC-REV-2","cutoffAt":"2026-11-14T18:00:00+08:00","createdBy":"测试员"}
                """)).andExpect(status().isOk());
        mvc().perform(post("/api/runs/1/publish")).andExpect(status().isOk());
        mvc().perform(post("/api/runs/2/publish")).andExpect(status().isOk());

        long wsId = factWsId();
        mvc().perform(post("/api/cost/workspaces/" + wsId + "/compile")).andExpect(status().isOk());

        List<Map<String, Object>> facts = list(
                mvc().perform(get("/api/cost/workspaces/" + wsId + "/facts")).andReturn().getResponse().getContentAsString());
        List<Map<String, Object>> findings = list(
                mvc().perform(get("/api/cost/workspaces/" + wsId + "/findings")).andReturn().getResponse().getContentAsString());

        // 1) 无分摊依据的多范围账单 → 仅相关不可归因，且不存在按批次数平均的分摊行
        Map<String, Object> multi = factByVoucher(facts, "CV-MULTI-01");
        assertEquals("RELATED_ONLY", multi.get("classification"));
        assertEquals("PENDING", multi.get("qualityStatus"));
        assertNull(multi.get("batchCode"));
        assertTrue(((List<?>) multi.get("pendingReasons")).contains("ATTRIBUTION_INSUFFICIENT"));
        // 有条款的多范围账单才允许分摊，且比例为 600/400，而非平均 500/500
        List<Map<String, Object>> allocated = facts.stream()
                .filter(f -> "CV-ALLOC-01".equals(f.get("voucherCode"))).toList();
        assertEquals(2, allocated.size());
        assertEquals(0, new BigDecimal("600").compareTo(num(allocated.get(0).get("baseNetAmount"))));
        assertEquals(0, new BigDecimal("400").compareTo(num(allocated.get(1).get("baseNetAmount"))));
        assertTrue(allocated.stream().allMatch(f -> "ALLOCATED".equals(f.get("classification"))));

        // 2) 未配对贷项通知：进入待人工配对集合，配对前不得冲减批次
        Map<String, Object> creditFinding = findings.stream()
                .filter(f -> "CREDIT_NOTICE_UNPAIRED".equals(f.get("findingType"))).findFirst().orElseThrow();
        assertEquals("OPEN", creditFinding.get("status"));
        Map<String, Object> creditFact = factByVoucher(facts, "CV-CN-041");
        assertEquals("PENDING", creditFact.get("qualityStatus"));
        assertTrue(((List<?>) creditFact.get("pendingReasons")).contains("DUPLICATE_UNRESOLVED"));

        // 3) 合法跨期应计：同时保留应计/入账期间并标记差异；依据缺失的跨期必须待核
        Map<String, Object> cross = factByVoucher(facts, "CV-CROSS-01");
        assertEquals("CONFIRMED", cross.get("qualityStatus"));
        assertEquals("2026-10", cross.get("accrualPeriod"));
        assertEquals("2026-11", cross.get("postingPeriod"));
        assertEquals(Boolean.TRUE, cross.get("periodDiffFlag"));
        Map<String, Object> noBasis = factByVoucher(facts, "CV-NOBASIS-01");
        assertEquals("PENDING", noBasis.get("qualityStatus"));
        assertTrue(((List<?>) noBasis.get("pendingReasons")).contains("ACCRUAL_BASIS_MISSING"));
        assertEquals("2026-11", noBasis.get("postingPeriod"), "入账期间仍按入账日保留，不用其覆盖服务事实");

        // 4) 双来源支付冲突：两个独立声明并存且都不被认定为真实付款
        Map<String, Object> payConflict = findings.stream()
                .filter(f -> "PAYMENT_CONFLICT".equals(f.get("findingType"))).findFirst().orElseThrow();
        assertEquals("CONFLICT", payConflict.get("status"));
        assertEquals(2, ((List<?>) payConflict.get("voucherIds")).size());
        List<Map<String, Object>> payFacts = facts.stream()
                .filter(f -> "CV-LAB-PAY".equals(f.get("voucherCode")) || "CV-INST-PAY".equals(f.get("voucherCode"))).toList();
        assertEquals(2, payFacts.size());
        assertTrue(payFacts.stream().allMatch(f -> "PENDING".equals(f.get("qualityStatus"))));
        assertTrue(payFacts.stream().allMatch(f -> ((List<?>) f.get("pendingReasons")).contains("PAYMENT_CONFLICT")));

        // 6) 缺失汇率：本币金额强制留空待核；可验证汇率的外币凭证可确认
        Map<String, Object> fxMissing = factByVoucher(facts, "CV-FX-MISSING");
        assertEquals("PENDING", fxMissing.get("qualityStatus"));
        assertNull(fxMissing.get("baseNetAmount"), "汇率不可验证时本币金额必须为空");
        assertTrue(((List<?>) fxMissing.get("pendingReasons")).contains("FX_MISSING"));
        Map<String, Object> fxOk = factByVoucher(facts, "CV-FX-OK");
        assertEquals("CONFIRMED", fxOk.get("qualityStatus"));
        assertEquals(0, new BigDecimal("7800").compareTo(num(fxOk.get("baseNetAmount"))));

        // 精确重复：身份键一致的两张发票都被挂起，不允许任一进入正式事实
        List<Map<String, Object>> dups = facts.stream()
                .filter(f -> "CV-DUP-1".equals(f.get("voucherCode")) || "CV-DUP-2".equals(f.get("voucherCode"))).toList();
        assertEquals(2, dups.size());
        assertTrue(dups.stream().allMatch(f -> "PENDING".equals(f.get("qualityStatus"))));

        // 审计投影：任一事实可展开到凭证行、归因规则版本、期间判断、重复结论、质量状态
        long firstFactId = ((Number) facts.get(0).get("id")).longValue();
        Map<String, Object> audit = map(mvc().perform(
                get("/api/cost/workspaces/" + wsId + "/facts/" + firstFactId + "/audit"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> sections = List.of("fact", "voucher", "attribution", "periodAssessment",
                "duplicateFindings", "auditProjections", "voucherIdentity");
        assertTrue(audit.keySet().containsAll(sections));
        assertNotNull(((Map<?, ?>) audit.get("voucher")).get("fileHash"));
        List<Map<String, Object>> projections = (List<Map<String, Object>>) audit.get("auditProjections");
        assertEquals("ARB-V1", projections.get(0).get("viewVersion"));

        // 5) 口径修订：发布后旧工作区只读；新建 ARB-V2 分析视图，GRADE-B 由确认变待核，旧事实不改写
        mvc().perform(post("/api/cost/workspaces/" + wsId + "/publish")).andExpect(status().isOk());
        mvc().perform(post("/api/cost/workspaces/" + wsId + "/compile")).andExpect(status().isConflict());
        String reviseJson = mvc().perform(post("/api/cost/workspaces/" + wsId + "/revise")
                .contentType("application/json")
                .content("{\"ruleBookVersion\":\"ARB-V2\",\"note\":\"期末后口径修订\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long newWsId = ((Number) map(reviseJson).get("workspaceId")).longValue();
        mvc().perform(post("/api/cost/workspaces/" + newWsId + "/compile")).andExpect(status().isOk());
        List<Map<String, Object>> newFacts = list(mvc().perform(
                get("/api/cost/workspaces/" + newWsId + "/facts")).andReturn().getResponse().getContentAsString());
        Map<String, Object> gradeBNew = factByVoucher(newFacts, "CV-GRADEB-01");
        assertEquals("PENDING", gradeBNew.get("qualityStatus"), "ARB-V2 收紧为 GRADE-A 后，GRADE-B 应降为待核");
        Map<String, Object> gradeBOld = factByVoucher(facts, "CV-GRADEB-01");
        assertEquals("CONFIRMED", gradeBOld.get("qualityStatus"), "旧口径已发布事实必须只读保留");
    }

    private Map<String, Object> factByVoucher(List<Map<String, Object>> facts, String voucherCode) {
        return facts.stream().filter(f -> voucherCode.equals(f.get("voucherCode"))).findFirst()
                .orElseThrow(() -> new AssertionError("缺少凭证对应的事实: " + voucherCode));
    }

    /** JSON 往返后数值可能是 Double/Integer/BigDecimal，统一转 BigDecimal 做数值比较。 */
    private BigDecimal num(Object o) {
        return new BigDecimal(String.valueOf(o));
    }
}
