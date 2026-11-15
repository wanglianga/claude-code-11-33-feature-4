package com.trace.recall.cost;

import com.trace.recall.common.ApiException;
import com.trace.recall.common.JdbcSupport;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 成本结果查询：事实行、归因、期间、重复结论与审计投影钻取。 */
@Service
public class CostQueryService {

    private final JdbcSupport db;

    public CostQueryService(JdbcSupport db) {
        this.db = db;
    }

    public List<Map<String, Object>> facts(long workspaceId) {
        ensureExists(workspaceId);
        return db.query("""
                SELECT f.id, f.workspace_id, f.fact_code, f.voucher_id, f.batch_code, f.classification,
                       f.voucher_type, f.currency, f.original_net_amount, f.tax_amount, f.base_net_amount,
                       f.fx_rate_source, f.accrual_period, f.posting_period, f.period_diff_flag,
                       f.quality_status, f.pending_reasons, f.attribution_rule_version,
                       f.accounting_rule_code, f.duplicate_finding_ids, f.published_at,
                       v.external_doc_no, v.line_item_no, v.contract_no, v.file_hash, v.voucher_code
                FROM cost_fact f JOIN cost_voucher v ON v.id = f.voucher_id
                WHERE f.workspace_id = ? ORDER BY f.id
                """, workspaceId);
    }

    public List<Map<String, Object>> attributions(long workspaceId) {
        ensureExists(workspaceId);
        return db.query("""
                SELECT a.id, a.workspace_id, a.voucher_id, a.rule_book_version, a.rule_code,
                       a.classification, a.batch_code, a.allocated_base_amount, a.attribution_status,
                       a.reason, a.basis, v.voucher_code, v.external_doc_no, v.line_item_no
                FROM attribution_result a JOIN cost_voucher v ON v.id = a.voucher_id
                WHERE a.workspace_id = ? ORDER BY a.id
                """, workspaceId);
    }

    public List<Map<String, Object>> periods(long workspaceId) {
        ensureExists(workspaceId);
        return db.query("""
                SELECT pa.id, pa.workspace_id, pa.voucher_id, pa.accounting_rule_code, pa.accrual_period,
                       pa.posting_period, pa.period_diff_flag, pa.cross_period_legal, pa.assessment_status,
                       pa.reason, pa.basis, v.voucher_code
                FROM period_assessment pa JOIN cost_voucher v ON v.id = pa.voucher_id
                WHERE pa.workspace_id = ? ORDER BY pa.id
                """, workspaceId);
    }

    public List<Map<String, Object>> findings(long workspaceId) {
        ensureExists(workspaceId);
        return db.query("""
                SELECT id, workspace_id, finding_type, dedup_group, voucher_ids, status, description,
                       resolution, created_at
                FROM duplicate_finding WHERE workspace_id = ? ORDER BY id
                """, workspaceId);
    }

    public List<Map<String, Object>> identities(long workspaceId) {
        ensureExists(workspaceId);
        return db.query("""
                SELECT vi.id, vi.workspace_id, vi.voucher_id, vi.identity_key, vi.dedup_status,
                       vi.dedup_group, vi.note, v.voucher_code, v.external_doc_no, v.line_item_no
                FROM voucher_identity vi JOIN cost_voucher v ON v.id = vi.voucher_id
                WHERE vi.workspace_id = ? ORDER BY vi.id
                """, workspaceId);
    }

    /** 从任一成本事实行展开：凭证行 + 归因规则版本 + 期间判断 + 重复检测结果 + 质量状态。 */
    public Map<String, Object> auditTrail(long workspaceId, long factId) {
        Map<String, Object> fact = db.queryOne("""
                SELECT * FROM cost_fact WHERE workspace_id = ? AND id = ?
                """, workspaceId, factId);
        if (fact == null) {
            throw ApiException.notFound("成本事实不存在: " + factId);
        }
        long voucherId = ((Number) fact.get("voucherId")).longValue();
        Map<String, Object> voucher = db.queryOne("""
                SELECT * FROM cost_voucher WHERE id = ? AND workspace_id = ?
                """, voucherId, workspaceId);
        List<Map<String, Object>> attribution = db.query(
                "SELECT * FROM attribution_result WHERE workspace_id = ? AND voucher_id = ? ORDER BY id",
                workspaceId, voucherId);
        Map<String, Object> period = db.queryOne(
                "SELECT * FROM period_assessment WHERE workspace_id = ? AND voucher_id = ?",
                workspaceId, voucherId);
        List<Map<String, Object>> findings = db.query("""
                SELECT * FROM duplicate_finding
                WHERE workspace_id = ? AND voucher_ids @> ?::jsonb ORDER BY id
                """, workspaceId, "[" + voucherId + "]");
        List<Map<String, Object>> projections = db.query(
                "SELECT id, fact_id, view_version, projection, created_at FROM audit_view_version WHERE fact_id = ? ORDER BY id",
                factId);
        Map<String, Object> identity = db.queryOne("""
                SELECT * FROM voucher_identity WHERE workspace_id = ? AND voucher_id = ?
                """, workspaceId, voucherId);
        return Map.of("fact", fact, "voucher", voucher, "attribution", attribution,
                "periodAssessment", period == null ? Map.of() : period,
                "duplicateFindings", findings, "auditProjections", projections,
                "voucherIdentity", identity == null ? Map.of() : identity);
    }

    private void ensureExists(long workspaceId) {
        Long count = db.jdbc().queryForObject(
                "SELECT count(*) FROM cost_workspace WHERE id = ?", Long.class, workspaceId);
        if (count == null || count == 0) {
            throw ApiException.notFound("成本工作区不存在: " + workspaceId);
        }
    }
}
