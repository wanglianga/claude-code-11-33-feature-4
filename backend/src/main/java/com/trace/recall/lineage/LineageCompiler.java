package com.trace.recall.lineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.trace.recall.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 谱系编译器：把快照内的原始事实编译为不可变的有向关系边（每次核验独立一份）。
 *
 * 降级规则：
 * - 箱码在两条互不相连运输记录中出现 → 两条运输边均降级 SUSPECTED（重复载体证据，均不能证明链路完整）；
 * - 来源包解析状态 PARSE_DIFF 且解析规则影响该事实类型 → 仅对应类型的边降级 SUSPECTED，不牵连其他来源；
 * - 中间品只有出站混批、没有任何入站投料/拆分/混批凭证 → 缺失投料凭证缺口；
 * - 未匹配身份观测生成 UNMATCHED_BATCH 缺口，歧义观测生成 IDENTITY_AMBIGUITY 缺口。
 *
 * 冻结（FROZEN）由数量质量引擎在超额投料检测后沿下游 BFS 传播，本编译器只给出初始置信状态。
 */
@Service
public class LineageCompiler {

    private final JdbcTemplate jdbc;

    public LineageCompiler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> compile(long runId, List<Long> packageIds) {
        List<Map<String, Object>> edges = new ArrayList<>();
        if (packageIds.isEmpty()) {
            return edges;
        }
        String inSql = placeholders(packageIds.size());

        // 箱码 → 出现的运输事实，用于重复载体检测
        Map<String, List<JsonNode>> boxObservations = new HashMap<>();
        List<JsonNode> shipmentFacts = new ArrayList<>();
        List<Map<String, Object>> facts = jdbc.query("""
                SELECT f.fact_type, f.fact_code, f.event_time, f.payload, p.package_code,
                       p.parse_status, p.parse_rule_version
                FROM raw_fact f JOIN source_package p ON p.id = f.package_id
                WHERE f.package_id IN (%s)
                ORDER BY f.event_time, f.id
                """.formatted(inSql), (rs, i) -> Map.of(
                "factType", rs.getString("fact_type"),
                "factCode", rs.getString("fact_code"),
                "eventTime", rs.getObject("event_time", OffsetDateTime.class),
                "payload", Json.tree(rs.getObject("payload")),
                "packageCode", rs.getString("package_code"),
                "parseStatus", rs.getString("parse_status"),
                "parseRuleVersion", rs.getString("parse_rule_version")),
                packageIds.toArray());

        Set<String> affectedParseTypes = affectedTypesOfDiffPackages(packageIds);

        for (Map<String, Object> row : facts) {
            String type = (String) row.get("factType");
            JsonNode payload = (JsonNode) row.get("payload");
            String packageCode = (String) row.get("packageCode");
            String parseStatus = (String) row.get("parseStatus");
            String parseVersion = (String) row.get("parseRuleVersion");
            OffsetDateTime eventTime = (OffsetDateTime) row.get("eventTime");

            if ("INSTITUTION_REPORT".equals(type)) {
                continue; // 机构回报在数量质量引擎中与物流签收对照
            }

            String upstream = textOrNull(payload, "upstreamBatch");
            String downstream;
            String relationType;
            if ("LOGISTICS".equals(type)) {
                relationType = "SHIPMENT";
                downstream = payload.path("nodeName").asText();
                shipmentFacts.add(payload);
                String box = payload.path("boxCode").asText(null);
                if (box != null) {
                    boxObservations.computeIfAbsent(box, k -> new ArrayList<>()).add(payload);
                }
            } else if ("RELEASE".equals(type)) {
                relationType = "RELEASE";
                downstream = payload.path("downstreamBatch").asText();
                upstream = payload.path("upstreamBatch").asText();
            } else {
                relationType = type; // FEEDING / SPLIT / BLEND
                downstream = payload.path("downstreamBatch").asText();
            }

            String confidence = "CONFIRMED";
            String blockReason = null;
            boolean review = false;
            if ("PARSE_DIFF".equals(parseStatus) && affectedParseTypes.contains(relationType)) {
                confidence = "SUSPECTED";
                blockReason = "PARSE_DIFF";
                review = true;
            }

            Map<String, Object> detail = new HashMap<>();
            if (payload.has("boxCode")) {
                detail.put("boxCode", payload.path("boxCode").asText(null));
            }
            if (payload.has("productionLot")) {
                detail.put("productionLot", payload.path("productionLot").asText(null));
            }
            if (payload.has("nodeName")) {
                detail.put("nodeName", payload.path("nodeName").asText(null));
            }

            long edgeId = jdbc.queryForObject("""
                    INSERT INTO lineage_edge(run_id, edge_code, upstream_batch, downstream_batch, relation_type,
                        source_fact_code, source_package_code, valid_from, confidence_status, review_required,
                        block_reason, parse_rule_version, quantity, unit, edge_detail)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    RETURNING id
                    """, Long.class, runId, "E-" + payload.path("factCode").asText(), upstream, downstream,
                    relationType, payload.path("factCode").asText(), packageCode, eventTime, confidence, review,
                    blockReason, parseVersion,
                    payload.has("quantity") && !payload.path("quantity").isNull()
                            ? payload.path("quantity").decimalValue() : null,
                    payload.path("unit").asText(null), Json.stringify(detail));
            Map<String, Object> edgeView = new HashMap<>();
            edgeView.put("id", edgeId);
            edgeView.put("relationType", relationType);
            edgeView.put("upstream", upstream);
            edgeView.put("downstream", downstream);
            edgeView.put("boxCode", detail.get("boxCode"));
            edges.add(edgeView);
        }

        applyDuplicateBoxRule(runId, boxObservations);
        detectMissingFeeding(runId);
        detectIdentityGaps(runId);
        return edges;
    }

    private Set<String> affectedTypesOfDiffPackages(List<Long> packageIds) {
        Set<String> types = new HashSet<>();
        List<String> affected = jdbc.queryForList("""
                SELECT DISTINCT r.affected_types
                FROM parse_rule r
                JOIN source_package p ON p.parse_rule_version = r.version
                WHERE p.id IN (%s) AND p.parse_status = 'PARSE_DIFF'
                """.formatted(placeholders(packageIds.size())), String.class, packageIds.toArray());
        for (String csv : affected) {
            for (String t : csv.split(",")) {
                types.add(t.trim());
            }
        }
        return types;
    }

    private void applyDuplicateBoxRule(long runId, Map<String, List<JsonNode>> boxObservations) {
        boxObservations.forEach((box, obs) -> {
            if (obs.size() <= 1) {
                return;
            }
            Set<String> disconnected = new HashSet<>();
            obs.forEach(o -> disconnected.add(o.path("factCode").asText()));
            for (JsonNode o : obs) {
                String factCode = o.path("factCode").asText();
                jdbc.update("""
                        UPDATE lineage_edge SET confidence_status = 'SUSPECTED', review_required = true,
                            block_reason = 'DUPLICATE_BOX'
                        WHERE run_id = ? AND source_fact_code = ?
                        """, runId, factCode);
            }
            jdbc.update("""
                    INSERT INTO evidence_gap(run_id, gap_type, severity, subject_code, node_name,
                        description, blocks_conclusion, evidence)
                    VALUES (?, 'DUPLICATE_BOX', 'BLOCKER', ?, NULL, ?, ?, ?::jsonb)
                    """, runId, box,
                    "箱码 " + box + " 在 " + disconnected.size() + " 条互不相连运输记录中重复，两条运输边均不能证明链路完整",
                    "受影响批次运输链路完整性确认",
                    Json.stringify(List.of(Map.of("boxCode", box, "facts", disconnected))));
        });
    }

    private void detectMissingFeeding(long runId) {
        // 作为上游出现、但没有任何入站投料/拆分/混批边的中间品
        List<String> intermediates = jdbc.queryForList("""
                SELECT DISTINCT e.upstream_batch
                FROM lineage_edge e JOIN batch_master b ON b.batch_code = e.upstream_batch
                WHERE e.run_id = ? AND b.batch_type = 'INTERMEDIATE'
                  AND NOT EXISTS (
                      SELECT 1 FROM lineage_edge x
                      WHERE x.run_id = e.run_id AND x.downstream_batch = e.upstream_batch
                        AND x.relation_type IN ('FEEDING','SPLIT','BLEND'))
                """, String.class, runId);
        for (String batch : intermediates) {
            jdbc.update("""
                    INSERT INTO evidence_gap(run_id, gap_type, severity, subject_code, description, blocks_conclusion, evidence)
                    VALUES (?, 'MISSING_FEEDING_EVIDENCE', 'BLOCKER', ?, ?, ?, '[]'::jsonb)
                    """, runId, batch,
                    "中间品 " + batch + " 存在出站混批/放行事实，但缺少任何入站投料、拆分或混批凭证",
                    batch + " 与上游原料批次之间的谱系连接确认");
        }
    }

    private void detectIdentityGaps(long runId) {
        List<Map<String, Object>> records = jdbc.query(
                "SELECT id, code_type, code_value, match_status FROM identity_record WHERE run_id = ?",
                (rs, i) -> Map.of("id", rs.getLong("id"), "codeType", rs.getString("code_type"),
                        "codeValue", rs.getString("code_value"), "matchStatus", rs.getString("match_status")),
                runId);
        for (Map<String, Object> r : records) {
            if ("UNMATCHED".equals(r.get("matchStatus"))) {
                jdbc.update("""
                        INSERT INTO evidence_gap(run_id, gap_type, severity, subject_code, description, blocks_conclusion, evidence)
                        VALUES (?, 'UNMATCHED_BATCH', 'BLOCKER', ?, ?, ?, '[]'::jsonb)
                        """, runId, r.get("codeValue"),
                        "身份观测 " + r.get("codeValue") + " 无法匹配任何内部批号，不得猜测归属",
                        "该载体对应批次的范围归因");
            } else if ("AMBIGUOUS".equals(r.get("matchStatus"))) {
                jdbc.update("""
                        INSERT INTO evidence_gap(run_id, gap_type, severity, subject_code, description, blocks_conclusion, evidence)
                        VALUES (?, 'IDENTITY_AMBIGUITY', 'WARNING', ?, ?, ?, ?::jsonb)
                        """, runId, r.get("codeValue"),
                        "编码 " + r.get("codeValue") + " 存在多个候选身份且无装箱凭证，保留双候选不合并",
                        "供应商批号与内部批号的唯一对应关系",
                        Json.stringify(List.of(Map.of(
                                "identityRecordId", r.get("id"), "codeType", r.get("codeType")))));
            }
        }
    }

    private String textOrNull(JsonNode node, String field) {
        return node.has(field) && !node.path(field).isNull() ? node.path(field).asText() : null;
    }

    private String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }
}
