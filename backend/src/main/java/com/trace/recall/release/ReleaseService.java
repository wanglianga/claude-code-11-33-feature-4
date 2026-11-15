package com.trace.recall.release;

import com.trace.recall.common.ApiException;
import com.trace.recall.common.Json;
import com.trace.recall.common.JdbcSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 范围发布器：把核验结果编译为版本化的发布范围。
 *
 * - 同一核验重复发布被拒绝；新版本发布时旧 PUBLISHED 版本置 SUPERSEDED 只读保留；
 * - 与上一版本比对生成三类差异：INSPECTION_CHANGED / NEW_LOGISTICS_EVIDENCE / PARSE_RULE_REVISED，
 *   多类同时出现合成 MIXED:...；
 * - 受影响结论变化记录新进入、移出与数量区间变化；
 * - 对外发布项仅含白名单字段，原始载荷与敏感联系人永不离开内部接口。
 */
@Service
public class ReleaseService {

    private final JdbcSupport db;

    public ReleaseService(JdbcSupport db) {
        this.db = db;
    }

    @Transactional
    public Map<String, Object> publish(long runId) {
        Map<String, Object> run = db.queryOne("""
                SELECT id, run_code, inspection_correction_version, rule_version, status, input_snapshot
                FROM verification_run WHERE id = ?
                """, runId);
        if (run == null) {
            throw ApiException.notFound("核验不存在: " + runId);
        }
        Long exists = db.jdbc().queryForObject(
                "SELECT count(*) FROM release_version WHERE run_id = ?", Long.class, runId);
        if (exists != null && exists > 0) {
            throw ApiException.conflict("该核验已发布，检验更正应通过新核验生成新版本，不得覆盖已发布结果");
        }

        List<Map<String, Object>> priorPublished = db.query(
                "SELECT * FROM release_version WHERE status = 'PUBLISHED' ORDER BY version_no DESC, id DESC LIMIT 1");
        int versionNo = priorPublished.isEmpty() ? 1
                : ((Number) priorPublished.get(0).get("versionNo")).intValue() + 1;
        String releaseCode = "REL-" + String.format("%03d", versionNo);

        List<String> diffTypes = new ArrayList<>();
        List<Map<String, Object>> diffDetail = new ArrayList<>();
        Map<String, Object> prior = priorPublished.isEmpty() ? null : priorPublished.get(0);

        if (prior != null) {
            String priorInspection = (String) db.jdbc().queryForObject("""
                    SELECT inspection_correction_version FROM verification_run
                    WHERE id = (SELECT run_id FROM release_version WHERE id = ?)
                    """, String.class, prior.get("id"));
            if (!run.get("inspectionCorrectionVersion").equals(priorInspection)) {
                diffTypes.add("INSPECTION_CHANGED");
                diffDetail.add(Map.of("type", "INSPECTION_CHANGED",
                        "description", "核验选择的检验更正版本不同",
                        "from", priorInspection, "to", run.get("inspectionCorrectionVersion")));
            }
            List<String> newPackages = diffNewPackages(run, prior);
            if (!newPackages.isEmpty()) {
                diffTypes.add("NEW_LOGISTICS_EVIDENCE");
                diffDetail.add(Map.of("type", "NEW_LOGISTICS_EVIDENCE",
                        "description", "新快照中出现旧快照没有的物流/机构来源包（迟到回报只进新版本）",
                        "packages", newPackages));
            }
            List<String> parseChanged = diffParseRevised(run, prior);
            if (!parseChanged.isEmpty()) {
                diffTypes.add("PARSE_RULE_REVISED");
                diffDetail.add(Map.of("type", "PARSE_RULE_REVISED",
                        "description", "来源包哈希有效但解析版本落后且字段语义受影响，需重新审阅关系边",
                        "packages", parseChanged));
            }
        }
        String diffType = diffTypes.isEmpty() ? "NONE"
                : diffTypes.size() == 1 ? diffTypes.get(0) : "MIXED:" + String.join("+", diffTypes);

        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("correctionVersion", run.get("inspectionCorrectionVersion"));
        basis.put("ruleVersion", run.get("ruleVersion"));
        basis.put("packages", packageSnapshots(runId));

        List<Map<String, Object>> affectedConclusions = buildConclusionChanges(runId, prior);

        long releaseId = db.insertReturningId("""
                INSERT INTO release_version(release_code, version_no, run_id, status, predecessor_release_code,
                    diff_type, diff_detail, basis, affected_conclusions)
                VALUES (?, ?, ?, 'PUBLISHED', ?, ?, ?::jsonb, ?::jsonb, ?::jsonb) RETURNING id
                """, releaseCode, versionNo, runId,
                prior == null ? null : prior.get("releaseCode"), diffType,
                Json.stringify(diffDetail), Json.stringify(basis),
                Json.stringify(affectedConclusions));

        if (prior != null) {
            db.update("UPDATE release_version SET status = 'SUPERSEDED' WHERE id = ?", prior.get("id"));
        }
        db.update("UPDATE verification_run SET status = 'PUBLISHED' WHERE id = ?", runId);
        insertReleaseItems(runId, releaseId);
        return Map.of("releaseId", releaseId, "releaseCode", releaseCode, "versionNo", versionNo);
    }

    @SuppressWarnings("unchecked")
    private List<String> diffNewPackages(Map<String, Object> currentRun, Map<String, Object> prior) {
        Map<String, Object> currentSnap = (Map<String, Object>) currentRun.get("inputSnapshot");
        Map<String, Object> priorBasis = (Map<String, Object>) prior.get("basis");
        Set<String> oldCodes = new LinkedHashSet<>();
        for (Object o : (List<?>) priorBasis.get("packages")) {
            oldCodes.add((String) ((Map<?, ?>) o).get("packageCode"));
        }
        List<String> added = new ArrayList<>();
        for (Object o : (List<?>) currentSnap.get("packages")) {
            Map<?, ?> pkg = (Map<?, ?>) o;
            String system = (String) pkg.get("sourceSystem");
            String code = (String) pkg.get("packageCode");
            if (!oldCodes.contains(code) && ("LOGISTICS".equals(system) || "INSTITUTION".equals(system))) {
                added.add(code);
            }
        }
        return added;
    }

    @SuppressWarnings("unchecked")
    private List<String> diffParseRevised(Map<String, Object> currentRun, Map<String, Object> prior) {
        Map<String, Object> currentSnap = (Map<String, Object>) currentRun.get("inputSnapshot");
        Map<String, Object> priorBasis = (Map<String, Object>) prior.get("basis");
        Map<String, String> oldParse = new LinkedHashMap<>();
        for (Object o : (List<?>) priorBasis.get("packages")) {
            Map<?, ?> pkg = (Map<?, ?>) o;
            oldParse.put((String) pkg.get("packageCode"), (String) pkg.get("parseRuleVersion"));
        }
        List<String> changed = new ArrayList<>();
        for (Object o : (List<?>) currentSnap.get("packages")) {
            Map<?, ?> pkg = (Map<?, ?>) o;
            String code = (String) pkg.get("packageCode");
            if ("PARSE_DIFF".equals(pkg.get("parseStatus"))
                    || (oldParse.containsKey(code) && !oldParse.get(code).equals(pkg.get("parseRuleVersion")))) {
                changed.add(code);
            }
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> packageSnapshots(long runId) {
        Map<String, Object> run = db.queryOne(
                "SELECT input_snapshot FROM verification_run WHERE id = ?", runId);
        return (List<Map<String, Object>>) ((Map<?, ?>) run.get("inputSnapshot")).get("packages");
    }

    private List<Map<String, Object>> buildConclusionChanges(long runId, Map<String, Object> prior) {
        List<Map<String, Object>> current = quantityMap(runId);
        if (prior == null) {
            return current.stream().map(q -> Map.<String, Object>of(
                    "batchCode", q.get("batchCode"), "changeType", "NEW_AFFECTED_BATCH",
                    "current", intervalView(q))).toList();
        }
        long priorRunId = ((Number) prior.get("runId")).longValue();
        List<Map<String, Object>> previous = quantityMap(priorRunId);
        Map<String, Map<String, Object>> prevByBatch = new LinkedHashMap<>();
        previous.forEach(q -> prevByBatch.put((String) q.get("batchCode"), q));

        List<Map<String, Object>> changes = new ArrayList<>();
        Set<String> currentCodes = new LinkedHashSet<>();
        for (Map<String, Object> q : current) {
            String batch = (String) q.get("batchCode");
            currentCodes.add(batch);
            Map<String, Object> old = prevByBatch.get(batch);
            if (old == null) {
                changes.add(Map.of("batchCode", batch, "changeType", "NEW_AFFECTED_BATCH",
                        "current", intervalView(q)));
            } else if (!sameInterval(old, q)) {
                changes.add(Map.of("batchCode", batch, "changeType", "QUANTITY_INTERVAL_CHANGED",
                        "previous", intervalView(old), "current", intervalView(q)));
            }
        }
        for (Map<String, Object> old : previous) {
            String batch = (String) old.get("batchCode");
            if (!currentCodes.contains(batch)) {
                changes.add(Map.of("batchCode", batch, "changeType", "NO_LONGER_AFFECTED",
                        "previous", intervalView(old)));
            }
        }
        return changes;
    }

    private List<Map<String, Object>> quantityMap(long runId) {
        return db.query("""
                SELECT batch_code, confirmed_lower, pending_qty, possible_upper, unattributed_delta, unit
                FROM quantity_state WHERE run_id = ? ORDER BY batch_code
                """, runId);
    }

    private Map<String, Object> intervalView(Map<String, Object> q) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("confirmedLower", q.get("confirmedLower"));
        v.put("pendingQty", q.get("pendingQty"));
        v.put("possibleUpper", q.get("possibleUpper"));
        v.put("unattributedDelta", q.get("unattributedDelta"));
        return v;
    }

    private boolean sameInterval(Map<String, Object> a, Map<String, Object> b) {
        return a.get("confirmedLower").equals(b.get("confirmedLower"))
                && a.get("pendingQty").equals(b.get("pendingQty"))
                && a.get("possibleUpper").equals(b.get("possibleUpper"));
    }

    private void insertReleaseItems(long runId, long releaseId) {
        List<Map<String, Object>> quantities = quantityMap(runId);
        for (Map<String, Object> q : quantities) {
            String batch = (String) q.get("batchCode");
            List<Map<String, Object>> nodeRows = db.query("""
                    SELECT edge_detail, confidence_status
                    FROM lineage_edge WHERE run_id = ? AND relation_type = 'SHIPMENT' AND upstream_batch = ?
                    """, runId, batch);
            String nodeName = nodeRows.isEmpty() ? null
                    : String.valueOf(((Map<?, ?>) nodeRows.get(0).get("edgeDetail")).get("nodeName"));
            boolean conflict = db.jdbc().queryForObject("""
                    SELECT count(*) FROM evidence_gap
                    WHERE run_id = ? AND gap_type = 'QUANTITY_CONFLICT' AND subject_code = ?
                    """, Long.class, runId, batch) > 0;
            String grade = ((Number) q.get("pendingQty")).doubleValue() > 0 ? "PENDING" : "CONFIRMED";
            db.update("""
                    INSERT INTO release_item(release_id, batch_code, node_name, confirmed_qty, pending_qty,
                        possible_qty, lower_bound, upper_bound, unit, evidence_grade, trace_link, conflict_flag)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, releaseId, batch, nodeName, q.get("confirmedLower"), q.get("pendingQty"),
                    q.get("possibleUpper"), q.get("confirmedLower"), q.get("possibleUpper"),
                    q.get("unit"), grade, "/runs/" + runId + "/batches/" + batch, conflict);
        }
    }

    public List<Map<String, Object>> listReleases() {
        List<Map<String, Object>> releases = db.query("""
                SELECT id, release_code, version_no, run_id, status, predecessor_release_code, diff_type,
                       diff_detail, basis,
                       affected_conclusions, published_at
                FROM release_version ORDER BY id
                """);
        attachItems(releases);
        return releases;
    }

    public Map<String, Object> getRelease(String code) {
        Map<String, Object> release = db.queryOne("""
                SELECT id, release_code, version_no, run_id, status, predecessor_release_code, diff_type,
                       diff_detail, basis,
                       affected_conclusions, published_at
                FROM release_version WHERE release_code = ?
                """, code);
        if (release == null) {
            throw ApiException.notFound("发布版本不存在: " + code);
        }
        attachItems(List.of(release));
        return release;
    }

    private void attachItems(List<Map<String, Object>> releases) {
        for (Map<String, Object> r : releases) {
            long releaseId = ((Number) r.get("id")).longValue();
            r.put("items", db.query("""
                    SELECT id, release_id, batch_code, node_name, confirmed_qty, pending_qty, possible_qty,
                           lower_bound, upper_bound, unit, evidence_grade, trace_link, conflict_flag
                    FROM release_item WHERE release_id = ? ORDER BY id
                    """, releaseId));
        }
    }

    /** 外部脱敏视图：严格白名单，原始载荷与敏感字段在 SQL 层即不选取。 */
    public Map<String, Object> externalSummary(String code) {
        Map<String, Object> release = getRelease(code);
        List<Map<String, Object>> safeItems = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) release.get("items");
        for (Map<String, Object> it : items) {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("batchCode", it.get("batchCode"));
            safe.put("nodeName", it.get("nodeName"));
            safe.put("confirmedQty", it.get("confirmedQty"));
            safe.put("pendingQty", it.get("pendingQty"));
            safe.put("possibleQty", it.get("possibleQty"));
            safe.put("lowerBound", it.get("lowerBound"));
            safe.put("upperBound", it.get("upperBound"));
            safe.put("unit", it.get("unit"));
            safe.put("evidenceGrade", it.get("evidenceGrade"));
            safe.put("traceLink", it.get("traceLink"));
            safe.put("conflictFlag", it.get("conflictFlag"));
            safeItems.add(safe);
        }
        return Map.of("releaseCode", release.get("releaseCode"),
                "versionNo", release.get("versionNo"),
                "status", release.get("status"),
                "publishedAt", release.get("publishedAt"),
                "items", safeItems);
    }
}
