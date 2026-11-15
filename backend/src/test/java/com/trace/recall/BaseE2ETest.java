package com.trace.recall;

import com.trace.recall.cost.CostSeeder;
import com.trace.recall.ingestion.DemoSeeder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 端到端测试基类：每个测试类执行前清空 recall_test schema 并重新播种，
 * 保证多个测试类共享 Spring 上下文时数据仍然隔离、主键与编号确定。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BaseE2ETest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DemoSeeder demoSeeder;
    @Autowired
    private CostSeeder costSeeder;

    @BeforeAll
    void resetAndSeed() throws Exception {
        jdbc.execute("""
                TRUNCATE TABLE
                    audit_view_version, cost_fact, duplicate_finding, period_assessment, attribution_result,
                    voucher_identity, cost_voucher, cost_workspace, attribution_rule_book, accounting_rule,
                    accounting_period, release_item, release_version, evidence_gap, quantity_state,
                    lineage_edge, identity_candidate, identity_record, verification_run, raw_fact,
                    batch_master, source_package, parse_rule, api_client
                RESTART IDENTITY CASCADE
                """);
        demoSeeder.run(null);
        costSeeder.run(null);
    }
}
