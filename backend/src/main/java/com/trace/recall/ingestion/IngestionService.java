package com.trace.recall.ingestion;

import com.trace.recall.common.ApiException;
import com.trace.recall.common.JdbcSupport;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 接入登记台：来源包、解析规则、批次主数据的只读查询。
 * 写入由 DemoSeeder 与（可选的）登记接口完成，所有原始载荷以 jsonb 原样保留。
 */
@Service
public class IngestionService {

    private final JdbcSupport db;

    public IngestionService(JdbcSupport db) {
        this.db = db;
    }

    public List<Map<String, Object>> listPackages() {
        return db.query("""
                SELECT id, package_code, source_system, source_revision, payload_hash, parse_rule_version,
                       parse_status, received_at, event_time, supersedes_package_code
                FROM source_package ORDER BY received_at, id
                """);
    }

    public Map<String, Object> getPackage(String code) {
        Map<String, Object> pkg = db.queryOne("""
                SELECT id, package_code, source_system, source_revision, payload_hash, parse_rule_version,
                       parse_status, received_at, event_time, supersedes_package_code, payload
                FROM source_package WHERE package_code = ?
                """, code);
        if (pkg == null) {
            throw ApiException.notFound("来源包不存在: " + code);
        }
        return pkg;
    }

    public List<Map<String, Object>> listParseRules() {
        return db.query("SELECT version, effective_at, description, affected_types FROM parse_rule ORDER BY effective_at");
    }

    public List<Map<String, Object>> listBatches() {
        return db.query("""
                SELECT batch_code, batch_type, product_code, product_name, unit, quantity, attributes
                FROM batch_master ORDER BY batch_code
                """);
    }

    /** 检验更正版本：来源于 QC_LAB 来源包，按接收时间排列。 */
    public List<Map<String, Object>> inspectionVersions() {
        return db.query("""
                SELECT source_revision AS version, received_at, supersedes_package_code, source_revision
                FROM source_package
                WHERE source_system = 'QC_LAB'
                ORDER BY received_at
                """);
    }
}
