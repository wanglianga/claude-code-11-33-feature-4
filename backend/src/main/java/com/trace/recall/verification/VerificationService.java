package com.trace.recall.verification;

import com.trace.recall.common.ApiException;
import com.trace.recall.common.Json;
import com.trace.recall.common.JdbcSupport;
import com.trace.recall.identity.IdentityResolutionService;
import com.trace.recall.lineage.LineageCompiler;
import com.trace.recall.quality.QuantityQualityEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 核验编排：发起一次范围核验时锁定截止时间前的来源包快照，
 * 然后依次执行身份解析 → 谱系编译 → 数量质量评估，所有结论表均以 run_id 隔离、不可变。
 */
@Service
public class VerificationService {

    private final JdbcSupport db;
    private final IdentityResolutionService identityService;
    private final LineageCompiler lineageCompiler;
    private final QuantityQualityEngine quantityEngine;

    public VerificationService(JdbcSupport db, IdentityResolutionService identityService,
                               LineageCompiler lineageCompiler, QuantityQualityEngine quantityEngine) {
        this.db = db;
        this.identityService = identityService;
        this.lineageCompiler = lineageCompiler;
        this.quantityEngine = quantityEngine;
    }

    @Transactional
    public Map<String, Object> createRun(String inspectionVersion, OffsetDateTime cutoffAt, String createdBy) {
        Long qcCount = db.jdbc().queryForObject(
                "SELECT count(*) FROM source_package WHERE source_system = 'QC_LAB' AND source_revision = ?",
                Long.class, inspectionVersion);
        if (qcCount == null || qcCount == 0) {
            throw ApiException.badRequest("检验更正版本不存在: " + inspectionVersion);
        }
        long runNo = db.jdbc().queryForObject("SELECT coalesce(max(id), 0) + 1 FROM verification_run", Long.class);
        String runCode = "RUN-" + String.format("%03d", runNo);

        // 快照：截止时间（含）之前接收的全部来源包，记录哈希与解析状态
        List<Map<String, Object>> packages = db.query("""
                SELECT id, package_code, source_system, source_revision, payload_hash, parse_rule_version,
                       parse_status, received_at, event_time
                FROM source_package WHERE received_at <= ? ORDER BY received_at, id
                """, cutoffAt);
        List<Map<String, Object>> snapshot = new ArrayList<>();
        List<Long> packageIds = new ArrayList<>();
        for (Map<String, Object> p : packages) {
            packageIds.add((Long) p.get("id"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("packageCode", p.get("packageCode"));
            item.put("sourceSystem", p.get("sourceSystem"));
            item.put("payloadHash", p.get("payloadHash"));
            item.put("parseRuleVersion", p.get("parseRuleVersion"));
            item.put("parseStatus", p.get("parseStatus"));
            snapshot.add(item);
        }
        Map<String, Object> inputSnapshot = Map.of("cutoffAt", cutoffAt.toString(), "packages", snapshot);

        long runId = db.insertReturningId("""
                INSERT INTO verification_run(run_code, inspection_correction_version, cutoff_at, rule_version,
                    status, input_snapshot, created_by)
                VALUES (?, ?, ?, 'RULE-2026.11', 'LOCKED', ?::jsonb, ?) RETURNING id
                """, runCode, inspectionVersion, cutoffAt, Json.stringify(inputSnapshot),
                createdBy == null || createdBy.isBlank() ? "质量分析员-张某" : createdBy);

        identityService.compile(runId, packages);
        lineageCompiler.compile(runId, packageIds);
        quantityEngine.evaluate(runId, inspectionVersion, packageIds);
        return Map.of("runId", runId, "runCode", runCode);
    }

    public List<Map<String, Object>> listRuns() {
        return db.query("""
                SELECT id, run_code, inspection_correction_version, cutoff_at, rule_version,
                       status, input_snapshot, created_by, created_at
                FROM verification_run ORDER BY id
                """);
    }

    public Map<String, Object> getRun(long runId) {
        Map<String, Object> run = db.queryOne("""
                SELECT id, run_code, inspection_correction_version, cutoff_at, rule_version,
                       status, input_snapshot, created_by, created_at
                FROM verification_run WHERE id = ?
                """, runId);
        if (run == null) {
            throw ApiException.notFound("核验不存在: " + runId);
        }
        List<Map<String, Object>> packages = db.query("""
                SELECT sp.id, sp.package_code, sp.source_system, sp.source_revision, sp.payload_hash,
                       sp.parse_rule_version, sp.parse_status, sp.received_at, sp.event_time
                FROM source_package sp
                JOIN jsonb_array_elements((SELECT input_snapshot FROM verification_run WHERE id = ?) -> 'packages') snap
                  ON snap ->> 'packageCode' = sp.package_code
                ORDER BY sp.received_at, sp.id
                """, runId);
        List<Map<String, Object>> identityRecords = loadIdentityRecords(runId);
        List<Map<String, Object>> edges = db.query("""
                SELECT id, edge_code, upstream_batch, downstream_batch, relation_type, source_fact_code,
                       source_package_code, valid_from, valid_to, confidence_status, review_required,
                       block_reason, parse_rule_version, quantity, unit, edge_detail
                FROM lineage_edge WHERE run_id = ? ORDER BY valid_from, id
                """, runId);
        List<Map<String, Object>> quantities = db.query("""
                SELECT run_id, batch_code, affected_flag, confirmed_lower, pending_qty, possible_upper,
                       unattributed_delta, unit, detail
                FROM quantity_state WHERE run_id = ? ORDER BY batch_code
                """, runId);
        List<Map<String, Object>> gaps = db.query("""
                SELECT id, gap_type, severity, subject_code, node_name, description, blocks_conclusion,
                       evidence, status, created_at
                FROM evidence_gap WHERE run_id = ? ORDER BY severity DESC, id
                """, runId);
        return Map.of("run", run, "packages", packages, "identityRecords", identityRecords,
                "edges", edges, "quantities", quantities, "gaps", gaps);
    }

    public List<Map<String, Object>> loadIdentityRecords(long runId) {
        List<Map<String, Object>> records = db.query("""
                SELECT id, run_id, code_type, code_value, observed_batch_code, match_status
                FROM identity_record WHERE run_id = ? ORDER BY id
                """, runId);
        for (Map<String, Object> r : records) {
            long recordId = ((Number) r.get("id")).longValue();
            List<Map<String, Object>> candidates = db.query("""
                    SELECT id, identity_record_id, candidate_batch_code, confidence, ambiguous,
                           evidence
                    FROM identity_candidate WHERE identity_record_id = ? ORDER BY id
                    """, recordId);
            r.put("candidates", candidates);
        }
        return records;
    }

    /** 批次全链路证据钻取：主数据 + 数量 + 节点 + 边 + 身份 + 缺口 + 原始载荷。 */
    public Map<String, Object> batchTrace(long runId, String batchCode) {
        getRun(runId); // 存在性校验
        Map<String, Object> master = db.queryOne("""
                SELECT batch_code, batch_type, product_code, product_name, unit, quantity,
                       attributes
                FROM batch_master WHERE batch_code = ?
                """, batchCode);
        Map<String, Object> quantity = db.queryOne("""
                SELECT run_id, batch_code, affected_flag, confirmed_lower, pending_qty, possible_upper,
                       unattributed_delta, unit, detail
                FROM quantity_state WHERE run_id = ? AND batch_code = ?
                """, runId, batchCode);
        List<Map<String, Object>> edges = db.query("""
                SELECT id, edge_code, upstream_batch, downstream_batch, relation_type, source_fact_code,
                       source_package_code, valid_from, confidence_status, review_required, block_reason,
                       parse_rule_version, quantity, unit, edge_detail
                FROM lineage_edge
                WHERE run_id = ? AND (upstream_batch = ? OR downstream_batch = ?)
                ORDER BY valid_from, id
                """, runId, batchCode, batchCode);
        List<Map<String, Object>> identityRecords = db.query("""
                SELECT id, code_type, code_value, observed_batch_code, match_status
                FROM identity_record WHERE run_id = ? AND observed_batch_code = ?
                ORDER BY id
                """, runId, batchCode);
        for (Map<String, Object> r : identityRecords) {
            r.put("candidates", db.query("""
                    SELECT id, candidate_batch_code, confidence, ambiguous, evidence
                    FROM identity_candidate WHERE identity_record_id = ?
                    """, ((Number) r.get("id")).longValue()));
        }
        List<Map<String, Object>> gaps = db.query("""
                SELECT id, gap_type, severity, subject_code, node_name, description, blocks_conclusion,
                       evidence, status
                FROM evidence_gap WHERE run_id = ? AND subject_code = ? ORDER BY id
                """, runId, batchCode);

        // 与该批次相关的来源包（运输/机构/投料凭证指向该批次）
        List<Map<String, Object>> rawPackages = db.query("""
                SELECT DISTINCT sp.id, sp.package_code, sp.source_system, sp.source_revision, sp.payload_hash,
                       sp.parse_rule_version, sp.parse_status, sp.received_at, sp.event_time,
                       sp.payload
                FROM source_package sp
                JOIN raw_fact rf ON rf.package_id = sp.id
                JOIN jsonb_array_elements((SELECT input_snapshot FROM verification_run WHERE id = ?) -> 'packages') snap
                  ON snap ->> 'packageCode' = sp.package_code
                WHERE rf.payload::text LIKE ?
                ORDER BY sp.received_at
                """, runId, "%" + batchCode + "%");
        return Map.of("batchMaster", master == null ? Map.of() : master,
                "quantity", quantity == null ? Map.of() : quantity,
                "edges", edges, "identityRecords", identityRecords, "gaps", gaps,
                "rawPackages", rawPackages);
    }
}
