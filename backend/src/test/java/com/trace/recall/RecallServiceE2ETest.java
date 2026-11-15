package com.trace.recall;

import com.fasterxml.jackson.databind.JsonNode;
import com.trace.recall.common.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 基线能力端到端规则测试，运行在独立 recall_test schema。
 * 覆盖 README 第 6 节的七项断言。
 */
class RecallServiceE2ETest extends BaseE2ETest {

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
    private Map<String, Object> body(String json) throws Exception {
        return Json.MAPPER.readValue(json, Map.class);
    }

    @Test
    void fullRecallVerificationAndReleaseScenario() throws Exception {
        // 两次核验
        mvc().perform(post("/api/runs").contentType("application/json").content("""
                {"inspectionCorrectionVersion":"QC-REV-1","cutoffAt":"2026-11-10T18:00:00+08:00","createdBy":"测试员"}
                """)).andExpect(status().isOk());
        mvc().perform(post("/api/runs").contentType("application/json").content("""
                {"inspectionCorrectionVersion":"QC-REV-2","cutoffAt":"2026-11-14T18:00:00+08:00","createdBy":"测试员"}
                """)).andExpect(status().isOk());

        // 6) 快照分别 6 / 8 个包
        String run1Json = mvc().perform(get("/api/runs/1")).andReturn().getResponse().getContentAsString();
        Map<String, Object> run1 = body(run1Json);
        String run2Json = mvc().perform(get("/api/runs/2")).andReturn().getResponse().getContentAsString();
        Map<String, Object> run2 = body(run2Json);
        assertEquals(6, ((List<?>) run1.get("packages")).size());
        assertEquals(8, ((List<?>) run2.get("packages")).size());

        // 1) 身份歧义保留 >=2 候选，且不存在供应商批号合并边
        long ambiguousCandidates = ((List<Map<String, Object>>) run1.get("identityRecords")).stream()
                .filter(r -> "AMBIGUOUS".equals(r.get("matchStatus")))
                .flatMap(r -> ((List<Map<String, Object>>) r.get("candidates")).stream())
                .count();
        assertTrue(ambiguousCandidates >= 2, "身份歧义应保留至少两个候选");
        boolean supplierMerged = ((List<Map<String, Object>>) run1.get("edges")).stream()
                .anyMatch(e -> "SUP-LOT-22".equals(e.get("upstreamBatch")) || "SUP-LOT-23".equals(e.get("upstreamBatch")));
        assertFalse(supplierMerged, "谱系中不应存在供应商批号合并边");

        // 2) RM-A04 超额 15kg，确认下界 60、未归因差 15；冻结边 >=3；孙代 FG-C03 确认量为 0
        Map<String, Object> rmA04 = ((List<Map<String, Object>>) run1.get("quantities")).stream()
                .filter(q -> "RM-A04".equals(q.get("batchCode"))).findFirst().orElseThrow();
        assertEquals(60.0, ((Number) rmA04.get("confirmedLower")).doubleValue(), 0.001);
        assertEquals(15.0, ((Number) rmA04.get("unattributedDelta")).doubleValue(), 0.001);
        long frozenEdges = ((List<Map<String, Object>>) run1.get("edges")).stream()
                .filter(e -> "FROZEN".equals(e.get("confidenceStatus"))).count();
        assertTrue(frozenEdges >= 3, "冻结边应至少 3 条，实际 " + frozenEdges);
        Map<String, Object> fgC03 = ((List<Map<String, Object>>) run1.get("quantities")).stream()
                .filter(q -> "FG-C03".equals(q.get("batchCode"))).findFirst().orElseThrow();
        assertEquals(0.0, ((Number) fgC03.get("confirmedLower")).doubleValue(), 0.001);

        // 3) HOSP-乙 数量冲突同时保留 50（物流）与 46（机构）
        String traceJson = mvc().perform(get("/api/runs/1/batches/FG-C03/trace"))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> trace = body(traceJson);
        JsonNode quantity = Json.tree(Json.MAPPER.valueToTree(trace.get("quantity")));
        JsonNode nodes = quantity.path("detail").path("nodes");
        boolean conflictKeptBoth = false;
        for (JsonNode n : nodes) {
            if ("HOSP-乙".equals(n.path("node").asText()) && n.path("quantityConflict").asBoolean()) {
                conflictKeptBoth = n.path("logisticsSignedQty").asDouble() == 50.0
                        && n.path("institutionReceivedQty").asDouble() == 46.0;
            }
        }
        assertTrue(conflictKeptBoth, "数量冲突节点应同时保留 50 与 46 两个数字");

        // 4) BOX-9 两条运输边均降级为重复载体
        long box9Edges = ((List<Map<String, Object>>) run1.get("edges")).stream()
                .filter(e -> {
                    JsonNode detail = Json.tree(Json.MAPPER.valueToTree(e.get("edgeDetail")));
                    return "BOX-9".equals(detail.path("boxCode").asText());
                }).count();
        long box9Suspected = ((List<Map<String, Object>>) run1.get("edges")).stream()
                .filter(e -> {
                    JsonNode detail = Json.tree(Json.MAPPER.valueToTree(e.get("edgeDetail")));
                    return "BOX-9".equals(detail.path("boxCode").asText())
                            && "SUSPECTED".equals(e.get("confidenceStatus"));
                }).count();
        assertEquals(2, box9Edges);
        assertEquals(2, box9Suspected, "BOX-9 两条运输边都应降级");

        // 5) PARSE_DIFF 只降级 LOGISTICS 边，MES FEEDING 边保持 CONFIRMED
        boolean feedingConfirmed = ((List<Map<String, Object>>) run1.get("edges")).stream()
                .filter(e -> "FEEDING".equals(e.get("relationType")))
                .allMatch(e -> "CONFIRMED".equals(e.get("confidenceStatus")));
        assertTrue(feedingConfirmed, "MES 的 FEEDING 边不应受物流解析差异牵连");

        // 两次发布
        mvc().perform(post("/api/runs/1/publish")).andExpect(status().isOk());
        mvc().perform(post("/api/runs/2/publish")).andExpect(status().isOk());
        String releasesJson = mvc().perform(get("/api/releases")).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> releases = Json.MAPPER.readValue(releasesJson, List.class);
        Map<String, Object> rel1 = releases.stream().filter(r -> "REL-001".equals(r.get("releaseCode"))).findFirst().orElseThrow();
        Map<String, Object> rel2 = releases.stream().filter(r -> "REL-002".equals(r.get("releaseCode"))).findFirst().orElseThrow();
        // 6) 旧版本 SUPERSEDED，新版本 MIXED 三类变化
        assertEquals("SUPERSEDED", rel1.get("status"));
        assertEquals("MIXED:INSPECTION_CHANGED+NEW_LOGISTICS_EVIDENCE+PARSE_RULE_REVISED", rel2.get("diffType"));

        // 7) 外部接口鉴权与脱敏
        mvc().perform(get("/external/releases")).andExpect(status().isUnauthorized());
        mvc().perform(get("/external/releases").header("X-API-Key", "wrong")).andExpect(status().isUnauthorized());
        String external = mvc().perform(get("/external/releases/REL-002").header("X-API-Key", "ext-trace-key-2026"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(external.contains("payload"));
        assertFalse(external.contains("sensitiveContacts"));
        assertFalse(external.contains("contactPerson"));
        assertFalse(external.contains("phone"));
        Map<String, Object> externalBody = body(external);
        List<String> allowed = List.of("releaseCode", "versionNo", "status", "publishedAt", "items");
        assertTrue(allowed.containsAll(externalBody.keySet()), "外部响应只含白名单顶层字段");
    }
}
