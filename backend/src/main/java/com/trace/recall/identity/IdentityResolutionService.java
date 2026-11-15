package com.trace.recall.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.trace.recall.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 批次身份解析：从快照中的 MDM 身份观测生成 identity_record / identity_candidate。
 * 强制规则：同一内部批号被多个供应商批号观测且无装箱凭证时，保留双候选、不合并、不删除，
 * 匹配状态标 AMBIGUOUS；无法匹配的生产批号保持 UNMATCHED，不猜测归属。
 */
@Service
public class IdentityResolutionService {

    private final JdbcTemplate jdbc;

    public IdentityResolutionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void compile(long runId, List<Map<String, Object>> snapshotPackages) {
        Long mdmId = snapshotPackages.stream()
                .map(p -> (Long) p.get("id"))
                .filter(id -> jdbc.queryForObject(
                        "SELECT source_system FROM source_package WHERE id = ?", String.class, id)
                        .equals("MDM"))
                .findFirst()
                .orElse(null);
        if (mdmId == null) {
            return;
        }
        JsonNode payload = Json.tree(jdbc.queryForObject(
                "SELECT payload FROM source_package WHERE id = ?", Object.class, mdmId));
        for (JsonNode obs : payload.path("identityObservations")) {
            String codeType = obs.path("codeType").asText();
            String codeValue = obs.path("codeValue").asText();
            String observed = obs.has("observedBatchCode") && !obs.path("observedBatchCode").isNull()
                    ? obs.path("observedBatchCode").asText() : null;
            int candidateCount = obs.path("candidates").size();
            boolean ambiguous = candidateCount > 1;
            String matchStatus = observed == null ? "UNMATCHED" : ambiguous ? "AMBIGUOUS" : "MATCHED";

            long recordId = jdbc.queryForObject("""
                    INSERT INTO identity_record(run_id, code_type, code_value, observed_batch_code, match_status)
                    VALUES (?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, runId, codeType, codeValue, observed, matchStatus);

            for (JsonNode c : obs.path("candidates")) {
                jdbc.update("""
                        INSERT INTO identity_candidate(identity_record_id, candidate_batch_code, confidence, ambiguous, evidence)
                        VALUES (?, ?, ?, ?, ?::jsonb)
                        """, recordId, c.path("candidateBatchCode").asText(),
                        c.path("confidence").asText(),
                        c.path("ambiguous").asBoolean(false),
                        c.has("evidence") ? c.path("evidence").toString() : "[]");
            }
        }
    }
}
