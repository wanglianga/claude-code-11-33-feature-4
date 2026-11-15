package com.trace.recall.cost;

import com.trace.recall.common.ApiException;
import com.trace.recall.common.Json;
import com.trace.recall.common.JdbcSupport;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 成本分析工作区：关闭期间 + 一组已发布范围 + 会计规则 + 归因规则版本的一次分析。 */
@Service
public class CostWorkspaceService {

    private final JdbcSupport db;

    public CostWorkspaceService(JdbcSupport db) {
        this.db = db;
    }

    public Map<String, Object> create(Map<String, Object> body) {
        Long periodId = Long.valueOf(String.valueOf(body.get("periodId")));
        String accountingRule = String.valueOf(body.get("accountingRuleCode"));
        String ruleBookVersion = String.valueOf(body.get("ruleBookVersion"));
        Object releases = body.get("releaseCodes");
        if (!(releases instanceof List) || ((List<?>) releases).isEmpty()) {
            throw ApiException.badRequest("至少选择一个已发布范围");
        }
        for (Object code : (List<?>) releases) {
            Long relCount = db.jdbc().queryForObject(
                    "SELECT count(*) FROM release_version WHERE release_code = ?",
                    Long.class, String.valueOf(code));
            if (relCount == null || relCount == 0) {
                throw ApiException.badRequest("已发布范围不存在: " + code);
            }
        }
        Long periodCount = db.jdbc().queryForObject(
                "SELECT count(*) FROM accounting_period WHERE id = ?", Long.class, periodId);
        if (periodCount == null || periodCount == 0) {
            throw ApiException.badRequest("会计期间不存在");
        }
        Long arCount = db.jdbc().queryForObject(
                "SELECT count(*) FROM accounting_rule WHERE rule_code = ?", Long.class, accountingRule);
        if (arCount == null || arCount == 0) {
            throw ApiException.badRequest("会计规则不存在: " + accountingRule);
        }
        Long rbCount = db.jdbc().queryForObject(
                "SELECT count(*) FROM attribution_rule_book WHERE rule_book_version = ?", Long.class, ruleBookVersion);
        if (rbCount == null || rbCount == 0) {
            throw ApiException.badRequest("归因规则册版本不存在: " + ruleBookVersion);
        }
        long no = db.jdbc().queryForObject("SELECT coalesce(max(id), 0) + 1 FROM cost_workspace", Long.class);
        String code = "CW-" + String.format("%03d", no);
        long id = db.insertReturningId("""
                INSERT INTO cost_workspace(workspace_code, name, period_id, accounting_rule_code, rule_book_version,
                    selected_release_codes, status, created_by, revision_note)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, 'DRAFT', ?, ?)
                RETURNING id
                """, code,
                body.getOrDefault("name", "成本归因工作区 " + code),
                periodId, accountingRule, ruleBookVersion, Json.stringify(releases),
                body.getOrDefault("createdBy", "成本分析员-王某"),
                body.get("revisionNote"));
        return Map.of("workspaceId", id, "workspaceCode", code);
    }

    public List<Map<String, Object>> list() {
        return db.query("""
                SELECT w.id, w.workspace_code, w.name, w.period_id, p.period_code, p.period_name,
                       w.accounting_rule_code, w.rule_book_version, w.selected_release_codes, w.status,
                       w.predecessor_workspace_id, w.revision_note, w.created_by, w.created_at,
                       w.compiled_at, w.summary
                FROM cost_workspace w JOIN accounting_period p ON p.id = w.period_id
                ORDER BY w.id
                """);
    }

    public Map<String, Object> detail(long id) {
        Map<String, Object> ws = db.queryOne("""
                SELECT w.id, w.workspace_code, w.name, w.period_id, p.period_code, p.period_name,
                       p.period_start, p.period_end, p.status AS period_status, p.closed_at,
                       w.accounting_rule_code, w.rule_book_version, w.selected_release_codes, w.status,
                       w.predecessor_workspace_id, w.revision_note, w.created_by, w.created_at,
                       w.compiled_at, w.summary
                FROM cost_workspace w JOIN accounting_period p ON p.id = w.period_id
                WHERE w.id = ?
                """, id);
        if (ws == null) {
            throw ApiException.notFound("成本工作区不存在: " + id);
        }
        return ws;
    }
}
