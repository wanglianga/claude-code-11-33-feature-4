package com.trace.recall.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.trace.recall.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数量质量引擎。
 *
 * 输入：选定检验更正版本判定的不合格原料 + 本核验编译出的谱系边。
 * 输出：每个受影响/待观察批次的数量区间（确认下界 / 待核 / 可能上界 / 未归因差额）与节点双凭证对照。
 *
 * 关键规则：
 * 1. 受影响范围沿 FEEDING/SPLIT/BLEND 边向下游 BFS 传播，运输/放行边不传播批次影响；
 * 2. 同一原料拆分/投料合计超过 batch_master.available 数量 → OVER_FEEDING，
 *    相关边置 FROZEN，冻结沿 BFS 传播到全部下游代际，冻结边贡献的量只进待核，不进确认下界；
 * 3. 物流签收与机构回报同时存在且数量不一致 → QUANTITY_CONFLICT，两个数字同时保留，任一来源不得覆盖另一方；
 * 4. 恒等式：确认下界 + 待核 = 可能上界；超额部分另列 unattributedDelta。
 */
@Service
public class QuantityQualityEngine {

    private final JdbcTemplate jdbc;

    public QuantityQualityEngine(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void evaluate(long runId, String inspectionVersion, List<Long> packageIds) {
        List<Edge> batchEdges = loadBatchEdges(runId);
        Set<String> nonconforming = loadNonconforming(inspectionVersion);

        // 1) 超额投料检测与冻结传播
        detectOverFeeding(runId, batchEdges);
        List<Edge> frozenEdges = loadBatchEdges(runId); // 重新加载含 FROZEN 状态

        // 2) 受影响闭包
        Set<String> affected = downstreamClosure(nonconforming, frozenEdges);

        // 3) 机构/物流节点双凭证对照
        Map<String, List<Map<String, Object>>> nodesByBatch = buildNodeQuantities(runId, packageIds);

        // 4) 逐批次计算区间
        for (String batch : affected) {
            BigDecimal confirmed = BigDecimal.ZERO;
            BigDecimal pending = BigDecimal.ZERO;
            BigDecimal delta = BigDecimal.ZERO;

            String type = jdbc.queryForObject("SELECT batch_type FROM batch_master WHERE batch_code = ?",
                    String.class, batch);
            if ("RAW".equals(type)) {
                BigDecimal available = jdbc.queryForObject(
                        "SELECT quantity FROM batch_master WHERE batch_code = ?", BigDecimal.class, batch);
                BigDecimal allocated = frozenEdges.stream()
                        .filter(e -> e.upstream.equals(batch))
                        .map(e -> e.quantity == null ? BigDecimal.ZERO : e.quantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (allocated.compareTo(available) > 0) {
                    confirmed = available;
                    pending = allocated.subtract(available);
                    delta = allocated.subtract(available);
                } else {
                    confirmed = allocated;
                }
            } else {
                for (Edge e : frozenEdges) {
                    if (!e.downstream.equals(batch)) {
                        continue;
                    }
                    BigDecimal qty = e.quantity == null ? BigDecimal.ZERO : e.quantity;
                    if ("FROZEN".equals(e.confidence) || "SUSPECTED".equals(e.confidence)) {
                        pending = pending.add(qty);
                    } else {
                        confirmed = confirmed.add(qty);
                    }
                }
            }
            BigDecimal upper = confirmed.add(pending);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("nodes", nodesByBatch.getOrDefault(batch, List.of()));
            jdbc.update("""
                    INSERT INTO quantity_state(run_id, batch_code, affected_flag, confirmed_lower, pending_qty,
                        possible_upper, unattributed_delta, unit, detail)
                    VALUES (?, ?, true, ?, ?, ?, ?, 'kg', ?::jsonb)
                    ON CONFLICT (run_id, batch_code) DO UPDATE SET
                        confirmed_lower = EXCLUDED.confirmed_lower,
                        pending_qty = EXCLUDED.pending_qty,
                        possible_upper = EXCLUDED.possible_upper,
                        unattributed_delta = EXCLUDED.unattributed_delta,
                        detail = EXCLUDED.detail
                    """, runId, batch, confirmed, pending, upper, delta, Json.stringify(detail));
        }
    }

    private List<Edge> loadBatchEdges(long runId) {
        return jdbc.query("""
                SELECT upstream_batch, downstream_batch, relation_type, quantity, confidence_status, source_fact_code
                FROM lineage_edge
                WHERE run_id = ? AND relation_type IN ('FEEDING','SPLIT','BLEND')
                """, (rs, i) -> new Edge(
                rs.getString("upstream_batch"), rs.getString("downstream_batch"),
                rs.getString("relation_type"),
                rs.getBigDecimal("quantity"), rs.getString("confidence_status"),
                rs.getString("source_fact_code")), runId);
    }

    private Set<String> loadNonconforming(String inspectionVersion) {
        JsonNode payload = Json.tree(jdbc.queryForObject("""
                SELECT payload FROM source_package WHERE source_revision = ? AND source_system = 'QC_LAB'
                """, Object.class, inspectionVersion));
        Set<String> result = new HashSet<>();
        payload.path("nonconformingBatches").forEach(b -> result.add(b.asText()));
        return result;
    }

    private void detectOverFeeding(long runId, List<Edge> edges) {
        Map<String, BigDecimal> allocated = new HashMap<>();
        edges.stream().filter(e -> "RAW".equals(batchType(e.upstream)))
                .forEach(e -> allocated.merge(e.upstream,
                        e.quantity == null ? BigDecimal.ZERO : e.quantity, BigDecimal::add));
        for (Map.Entry<String, BigDecimal> entry : allocated.entrySet()) {
            String raw = entry.getKey();
            BigDecimal available = jdbc.queryForObject(
                    "SELECT quantity FROM batch_master WHERE batch_code = ?", BigDecimal.class, raw);
            if (entry.getValue().compareTo(available) <= 0) {
                continue;
            }
            BigDecimal overage = entry.getValue().subtract(available);
            jdbc.update("""
                    INSERT INTO evidence_gap(run_id, gap_type, severity, subject_code, description, blocks_conclusion, evidence)
                    VALUES (?, 'OVER_FEEDING', 'BLOCKER', ?, ?, ?, ?::jsonb)
                    """, runId, raw,
                    "原料 " + raw + " 拆分/投料合计 " + entry.getValue() + "kg 超过可用量 " + available
                            + "kg，整条分配链确认量冻结，超额 " + overage + "kg 列为未归因差额",
                    raw + " 下游全部代际的确认量",
                    Json.stringify(Map.of(
                            "allocated", entry.getValue(), "available", available, "overage", overage)));

            // 冻结超额原料的全部出站边，并 BFS 传播到所有下游代际
            Set<String> initial = new HashSet<>();
            edges.stream().filter(e -> e.upstream.equals(raw)).forEach(e -> {
                freezeEdge(runId, e.sourceFactCode, "OVER_FEEDING");
                initial.add(e.downstream);
            });
            Set<String> frontier = initial;
            Set<String> frozenNodes = new HashSet<>(frontier);
            while (!frontier.isEmpty()) {
                Set<String> next = new HashSet<>();
                for (String node : frontier) {
                    for (Edge e : edges) {
                        if (e.upstream.equals(node) && !frozenNodes.contains(e.downstream)) {
                            freezeEdge(runId, e.sourceFactCode, "OVER_FEEDING_PROPAGATED");
                            next.add(e.downstream);
                        }
                    }
                }
                frozenNodes.addAll(next);
                frontier = next;
            }
        }
    }

    private void freezeEdge(long runId, String factCode, String reason) {
        jdbc.update("""
                UPDATE lineage_edge SET confidence_status = 'FROZEN', review_required = true, block_reason = ?
                WHERE run_id = ? AND source_fact_code = ? AND confidence_status <> 'FROZEN'
                """, reason, runId, factCode);
    }

    private Set<String> downstreamClosure(Set<String> seeds, List<Edge> edges) {
        Set<String> result = new HashSet<>(seeds);
        Set<String> frontier = new HashSet<>(seeds);
        while (!frontier.isEmpty()) {
            Set<String> next = new HashSet<>();
            for (String node : frontier) {
                for (Edge e : edges) {
                    if (e.upstream.equals(node) && !result.contains(e.downstream)) {
                        next.add(e.downstream);
                    }
                }
            }
            result.addAll(next);
            frontier = next;
        }
        return result;
    }

    private Map<String, List<Map<String, Object>>> buildNodeQuantities(long runId, List<Long> packageIds) {
        // 机构回报索引：batch+node → 报告
        Map<String, JsonNode> reportIndex = new HashMap<>();
        if (!packageIds.isEmpty()) {
            String inSql = String.join(",", java.util.Collections.nCopies(packageIds.size(), "?"));
            List<Map<String, Object>> instPkgs = jdbc.query("""
                    SELECT payload FROM source_package
                    WHERE id IN (%s) AND source_system = 'INSTITUTION'
                    """.formatted(inSql), (rs, i) -> Map.of("payload", Json.tree(rs.getObject("payload"))),
                    packageIds.toArray());
            for (Map<String, Object> row : instPkgs) {
                ((JsonNode) row.get("payload")).path("reports").forEach(r -> reportIndex.put(
                        r.path("batchCode").asText() + "|" + r.path("nodeName").asText(), r));
            }
        }

        // 运输边（含重复载体/解析差异降级状态）
        List<Map<String, Object>> shipments = jdbc.query("""
                SELECT upstream_batch, edge_detail, quantity, source_fact_code, confidence_status, block_reason
                FROM lineage_edge
                WHERE run_id = ? AND relation_type = 'SHIPMENT'
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batch", rs.getString("upstream_batch"));
            row.put("detail", Json.tree(rs.getObject("edge_detail")));
            row.put("qty", rs.getBigDecimal("quantity") == null ? BigDecimal.ZERO : rs.getBigDecimal("quantity"));
            row.put("fact", rs.getString("source_fact_code"));
            row.put("confidence", rs.getString("confidence_status"));
            row.put("blockReason", rs.getString("block_reason") == null ? "" : rs.getString("block_reason"));
            return row;
        }, runId);

        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (Map<String, Object> s : shipments) {
            String batch = (String) s.get("batch");
            JsonNode detail = (JsonNode) s.get("detail");
            String node = detail.path("nodeName").asText(null);
            String box = detail.path("boxCode").asText(null);
            Map<String, Object> nodeView = new LinkedHashMap<>();
            nodeView.put("node", node);
            nodeView.put("boxCode", box);
            nodeView.put("logisticsSignedQty", s.get("qty"));
            nodeView.put("logisticsFactCode", s.get("fact"));

            JsonNode report = batch == null ? null : reportIndex.get(batch + "|" + node);
            boolean conflict = false;
            if (report != null) {
                BigDecimal instQty = report.path("receivedQty").decimalValue();
                nodeView.put("institutionReceivedQty", instQty);
                nodeView.put("institutionFactCode", report.path("reportCode").asText());
                if (instQty.compareTo((BigDecimal) s.get("qty")) != 0) {
                    conflict = true;
                }
            }
            nodeView.put("quantityConflict", conflict);
            boolean weak = !"CONFIRMED".equals(s.get("confidence"));
            nodeView.put("evidenceWeak", weak);
            nodeView.put("weakReason", weak ? s.get("blockReason") : null);
            result.computeIfAbsent(batch == null ? "" : batch, k -> new ArrayList<>()).add(nodeView);

            if (conflict) {
                jdbc.update("""
                        INSERT INTO evidence_gap(run_id, gap_type, severity, subject_code, node_name,
                            description, blocks_conclusion, evidence)
                        VALUES (?, 'QUANTITY_CONFLICT', 'BLOCKER', ?, ?, ?, ?, ?::jsonb)
                        """, runId, batch, node,
                        "节点 " + node + " 物流签收 " + s.get("qty") + "kg 与机构回报 "
                                + report.path("receivedQty").asText() + "kg 不一致，双凭证并存，任一来源不得覆盖另一方",
                        batch + " 在 " + node + " 的数量确认",
                        Json.stringify(List.of(Map.of(
                                "logistics", Map.of("fact", s.get("fact"), "qty", s.get("qty")),
                                "institution", Map.of("fact", report.path("reportCode").asText(),
                                        "qty", report.path("receivedQty").decimalValue())))));
            }
        }
        return result;
    }

    private String batchType(String code) {
        if (code == null) {
            return "";
        }
        List<String> types = jdbc.queryForList(
                "SELECT batch_type FROM batch_master WHERE batch_code = ?", String.class, code);
        return types.isEmpty() ? "" : types.get(0);
    }

    private record Edge(String upstream, String downstream, String relationType,
                        BigDecimal quantity, String confidence, String sourceFactCode) {
    }
}
