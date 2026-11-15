package com.trace.recall.cost;

import com.trace.recall.common.ApiException;
import com.trace.recall.common.Json;
import com.trace.recall.common.JdbcSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 口径修订与发布。
 *
 * - 发布：COMPILED → PUBLISHED，事实与审计投影冻结；
 * - 归因规则版本在期末后修订时，基于原工作区创建新工作区（新规则册版本），复制全部原始凭证，
 *   原工作区及其已关闭期间发布记录、旧口径成本事实与审计投影原样只读保留，绝不改写。
 */
@Service
public class CostRevisionService {

    private final JdbcSupport db;
    private final CostVoucherService voucherService;

    public CostRevisionService(JdbcSupport db, CostVoucherService voucherService) {
        this.db = db;
        this.voucherService = voucherService;
    }

    public Map<String, Object> publish(long workspaceId) {
        Map<String, Object> ws = db.queryOne("SELECT status FROM cost_workspace WHERE id = ?", workspaceId);
        if (ws == null) {
            throw ApiException.notFound("成本工作区不存在: " + workspaceId);
        }
        if (!"COMPILED".equals(ws.get("status"))) {
            throw ApiException.conflict("仅已编译(COMPILED)工作区可发布，当前状态: " + ws.get("status"));
        }
        db.update("UPDATE cost_workspace SET status = 'PUBLISHED' WHERE id = ?", workspaceId);
        return Map.of("workspaceId", workspaceId, "status", "PUBLISHED");
    }

    @Transactional
    public Map<String, Object> revise(long sourceWorkspaceId, String newRuleBookVersion, String note) {
        Map<String, Object> src = db.queryOne("""
                SELECT id, workspace_code, name, period_id, accounting_rule_code, rule_book_version,
                       selected_release_codes, status, created_by
                FROM cost_workspace WHERE id = ?
                """, sourceWorkspaceId);
        if (src == null) {
            throw ApiException.notFound("成本工作区不存在: " + sourceWorkspaceId);
        }
        if (newRuleBookVersion.equals(src.get("ruleBookVersion"))) {
            throw ApiException.badRequest("口径修订必须选择不同的归因规则册版本");
        }
        Long rbCount = db.jdbc().queryForObject(
                "SELECT count(*) FROM attribution_rule_book WHERE rule_book_version = ?",
                Long.class, newRuleBookVersion);
        if (rbCount == null || rbCount == 0) {
            throw ApiException.badRequest("归因规则册版本不存在: " + newRuleBookVersion);
        }

        long no = db.jdbc().queryForObject("SELECT coalesce(max(id), 0) + 1 FROM cost_workspace", Long.class);
        String code = "CW-" + String.format("%03d", no);
        long newId = db.insertReturningId("""
                INSERT INTO cost_workspace(workspace_code, name, period_id, accounting_rule_code, rule_book_version,
                    selected_release_codes, status, predecessor_workspace_id, revision_note, created_by)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, 'DRAFT', ?, ?, ?) RETURNING id
                """, code, src.get("name") + "（口径修订 " + newRuleBookVersion + "）",
                src.get("periodId"), src.get("accountingRuleCode"), newRuleBookVersion,
                Json.stringify(src.get("selectedReleaseCodes")), sourceWorkspaceId,
                note == null ? "期末后规则口径修订，原始凭证不变，旧口径事实只读保留" : note,
                src.get("createdBy"));

        // 复制原始凭证（重新登记以生成新工作区自己的身份簿），凭证内容与文件指纹保持不变
        List<Map<String, Object>> vouchers = db.query(
                "SELECT * FROM cost_voucher WHERE workspace_id = ? ORDER BY id", sourceWorkspaceId);
        for (Map<String, Object> v : vouchers) {
            voucherService.register(newId, stripDbColumns(v));
        }
        return Map.of("workspaceId", newId, "workspaceCode", code,
                "predecessorWorkspaceId", sourceWorkspaceId, "ruleBookVersion", newRuleBookVersion);
    }

    private Map<String, Object> stripDbColumns(Map<String, Object> row) {
        java.util.Map<String, Object> copy = new java.util.LinkedHashMap<>(row);
        copy.keySet().removeAll(List.of("id", "workspaceId", "registeredAt"));
        return copy;
    }
}
