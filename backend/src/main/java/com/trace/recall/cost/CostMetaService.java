package com.trace.recall.cost;

import com.trace.recall.common.JdbcSupport;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 成本域元数据：会计期间、会计规则、归因规则册、可选已发布范围。 */
@Service
public class CostMetaService {

    private final JdbcSupport db;

    public CostMetaService(JdbcSupport db) {
        this.db = db;
    }

    public List<Map<String, Object>> periods() {
        return db.query("""
                SELECT id, period_code, period_name, period_start, period_end, closed_at, status, created_at
                FROM accounting_period ORDER BY period_start
                """);
    }

    public List<Map<String, Object>> accountingRules() {
        return db.query("""
                SELECT rule_code, rule_name, allows_cross_period, fx_policy, period_basis,
                       effective_at, supersedes_rule_code, status
                FROM accounting_rule ORDER BY effective_at
                """);
    }

    public List<Map<String, Object>> ruleBooks() {
        return db.query("""
                SELECT id, rule_book_version, effective_at, status, description, rules
                FROM attribution_rule_book ORDER BY effective_at
                """);
    }

    public List<Map<String, Object>> publishedReleases() {
        return db.query("""
                SELECT rv.id, rv.release_code, rv.version_no, rv.status, rv.published_at,
                       (SELECT count(*) FROM release_item ri WHERE ri.release_id = rv.id) AS item_count
                FROM release_version rv ORDER BY rv.id
                """);
    }

    public Map<String, Object> ruleBook(String version) {
        Map<String, Object> book = db.queryOne(
                "SELECT id, rule_book_version, effective_at, status, description, rules FROM attribution_rule_book WHERE rule_book_version = ?",
                version);
        if (book == null) {
            throw new IllegalArgumentException("归因规则册版本不存在: " + version);
        }
        return book;
    }
}
