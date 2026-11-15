package com.trace.recall.ingestion;

import com.trace.recall.common.Json;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示数据播种器（幂等）：
 * 重现 README 第 4 节时间线——8 个来源包、13 个批次主数据、两次检验更正、
 * 身份歧义、超额投料、重复箱码、解析差异、机构/物流数量冲突与迟到回报。
 * 所有引擎均从这些来源包的原始载荷编译结论，播种器本身不写任何结论表。
 */
@Component
public class DemoSeeder implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public DemoSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM source_package", Long.class);
        if (count != null && count > 0) {
            return;
        }
        seedParseRules();
        seedApiClient();
        seedBatches();
        seedPackages();
    }

    private void seedParseRules() {
        jdbc.update("""
                INSERT INTO parse_rule(version, effective_at, description, affected_types)
                VALUES ('PARSE-1.9', '2026-10-01T00:00:00+08:00', '初版解析规则', 'ALL')
                """);
        jdbc.update("""
                INSERT INTO parse_rule(version, effective_at, description, affected_types)
                VALUES ('PARSE-2.0', '2026-11-05T09:00:00+08:00', '物流与混批字段语义升级', 'LOGISTICS,BLEND')
                """);
        jdbc.update("""
                INSERT INTO parse_rule(version, effective_at, description, affected_types)
                VALUES ('PARSE-2.1', '2026-11-12T09:00:00+08:00', '机构回报与检验凭证字段细化', 'INSTITUTION_REPORT,INSPECTION')
                """);
    }

    private void seedApiClient() {
        jdbc.update("INSERT INTO api_client(api_key, name, enabled) VALUES ('ext-trace-key-2026', '外部处置系统', true)");
    }

    private void seedBatches() {
        record B(String code, String type, String product, String name, Double qty) {
        }
        List<B> batches = List.of(
                new B("RM-A01", "RAW", "P-RAW-01", "原料 A01", 80.0),
                new B("RM-A02", "RAW", "P-RAW-02", "原料 A02（双供应商批号观测）", 50.0),
                new B("RM-A03", "RAW", "P-RAW-03", "原料 A03", 20.0),
                new B("RM-A04", "RAW", "P-RAW-04", "原料 A04（可用量 60kg）", 60.0),
                new B("IM-B10", "INTERMEDIATE", "P-IM-10", "中间品 B10", null),
                new B("IM-B11", "INTERMEDIATE", "P-IM-11", "中间品 B11", null),
                new B("IM-B20", "INTERMEDIATE", "P-IM-20", "中间品 B20", null),
                new B("IM-B30", "INTERMEDIATE", "P-IM-30", "中间品 B30（缺投料凭证）", null),
                new B("FG-C01", "FINISHED", "P-FG-01", "成品 C01", null),
                new B("FG-C02", "FINISHED", "P-FG-02", "成品 C02", null),
                new B("FG-C03", "FINISHED", "P-FG-03", "成品 C03", null),
                new B("FG-C04", "FINISHED", "P-FG-04", "成品 C04", null),
                new B("FG-C05", "FINISHED", "P-FG-05", "成品 C05", null)
        );
        for (B b : batches) {
            jdbc.update("""
                    INSERT INTO batch_master(batch_code, batch_type, product_code, product_name, unit, quantity, attributes)
                    VALUES (?, ?, ?, ?, 'kg', ?, '{}'::jsonb)
                    """, b.code(), b.type(), b.product(), b.name(), b.qty());
        }
    }

    private void seedPackages() {
        // 1) MDM 主数据 + 身份观测（11-01）
        Map<String, Object> mdm = new LinkedHashMap<>();
        mdm.put("batches", jdbc.queryForList("""
                SELECT batch_code AS batchCode, batch_type AS batchType, product_code AS productCode,
                       product_name AS productName, unit, quantity FROM batch_master ORDER BY batch_code
                """));
        List<Map<String, Object>> identity = new ArrayList<>();
        identity.add(observation("SUPPLIER_LOT", "SUP-LOT-22", "RM-A02",
                List.of(candidate("RM-A02", "HIGH", true), candidate("RM-A01", "LOW", true)),
                "无装箱凭证，两个供应商批号同时指向 RM-A02"));
        identity.add(observation("SUPPLIER_LOT", "SUP-LOT-23", "RM-A02",
                List.of(candidate("RM-A02", "HIGH", true), candidate("RM-A01", "LOW", true)),
                "无装箱凭证，两个供应商批号同时指向 RM-A02"));
        identity.add(observation("BOX_CODE", "BOX-9", "FG-C02",
                List.of(candidate("FG-C02", "HIGH", false)), "物流箱码观测"));
        identity.add(observation("PRODUCTION_LOT", "PL-UNKNOWN-9", null,
                List.of(), "无法匹配任何内部批号"));
        for (String code : List.of("RM-A01", "RM-A02", "RM-A03", "RM-A04", "IM-B10", "IM-B11", "IM-B20", "IM-B30",
                "FG-C01", "FG-C02", "FG-C03", "FG-C04", "FG-C05")) {
            identity.add(observation("INTERNAL_LOT", code, code,
                    List.of(candidate(code, "HIGH", false)), "内部批号主数据"));
        }
        mdm.put("identityObservations", identity);
        insertPackage("MDM-PKG-1", "MDM", "MDM-REV-1", "PARSE-2.0", "PARSED",
                OffsetDateTime.parse("2026-11-01T09:00:00+08:00"), OffsetDateTime.parse("2026-11-01T08:30:00+08:00"),
                null, mdm);

        // 2) 检验 V1（11-02）：RM-A03、RM-A04 不合格
        Map<String, Object> qc1 = Map.of(
                "correctionVersion", "QC-REV-1",
                "nonconformingBatches", List.of("RM-A03", "RM-A04"),
                "inspectionResults", List.of(
                        Map.of("batchCode", "RM-A03", "conclusion", "NONCONFORMING", "item", "含量均匀度"),
                        Map.of("batchCode", "RM-A04", "conclusion", "NONCONFORMING", "item", "有关物质"),
                        Map.of("batchCode", "RM-A01", "conclusion", "CONFORMING", "item", "全项"),
                        Map.of("batchCode", "RM-A02", "conclusion", "CONFORMING", "item", "全项")));
        insertPackage("QC-PKG-1", "QC_LAB", "QC-REV-1", "PARSE-2.0", "PARSED",
                OffsetDateTime.parse("2026-11-02T14:00:00+08:00"), OffsetDateTime.parse("2026-11-02T13:30:00+08:00"),
                null, qc1);

        // 3) MES 投料/拆分/混批/放行（11-03）
        Map<String, Object> mes = new LinkedHashMap<>();
        List<Map<String, Object>> mesFacts = new ArrayList<>();
        mesFacts.add(fact("F-FEED-1", "FEEDING", "2026-11-03T09:10:00+08:00", "RM-A01", "IM-B20", 40.0));
        mesFacts.add(fact("F-FEED-2", "FEEDING", "2026-11-03T09:20:00+08:00", "RM-A03", "IM-B20", 20.0));
        mesFacts.add(fact("F-SPLIT-1", "SPLIT", "2026-11-03T10:00:00+08:00", "RM-A04", "IM-B10", 40.0));
        mesFacts.add(fact("F-SPLIT-2", "SPLIT", "2026-11-03T10:05:00+08:00", "RM-A04", "IM-B11", 35.0));
        mesFacts.add(fact("F-BLEND-1A", "BLEND", "2026-11-03T13:00:00+08:00", "IM-B20", "FG-C01", 30.0));
        mesFacts.add(fact("F-BLEND-1B", "BLEND", "2026-11-03T13:10:00+08:00", "IM-B10", "FG-C01", 25.0));
        mesFacts.add(fact("F-BLEND-2", "BLEND", "2026-11-03T14:00:00+08:00", "IM-B11", "FG-C02", 20.0));
        mesFacts.add(fact("F-BLEND-3", "BLEND", "2026-11-03T14:20:00+08:00", "IM-B11", "FG-C03", 15.0));
        mesFacts.add(fact("F-BLEND-4", "BLEND", "2026-11-03T15:00:00+08:00", "IM-B30", "FG-C04", 18.0));
        for (String fg : List.of("FG-C01", "FG-C02", "FG-C03", "FG-C04", "FG-C05")) {
            Map<String, Object> rel = new LinkedHashMap<>();
            rel.put("factCode", "F-REL-" + fg);
            rel.put("factType", "RELEASE");
            rel.put("eventTime", "2026-11-03T17:00:00+08:00");
            rel.put("upstreamBatch", fg);
            rel.put("downstreamBatch", fg);
            rel.put("quantity", 0.0);
            rel.put("unit", "kg");
            mesFacts.add(rel);
        }
        mes.put("facts", mesFacts);
        insertPackage("MES-PKG-1", "MES", "MES-REV-1", "PARSE-2.0", "PARSED",
                OffsetDateTime.parse("2026-11-03T18:00:00+08:00"), OffsetDateTime.parse("2026-11-03T17:30:00+08:00"),
                null, mes);

        // 4) 物流包 1（11-06，PARSE-2.0）
        Map<String, Object> log1 = new LinkedHashMap<>();
        List<Map<String, Object>> logFacts1 = new ArrayList<>();
        logFacts1.add(shipment("L-1", "2026-11-06T09:00:00+08:00", "BOX-9", null, "FG-C02", "W-华东仓", 20.0));
        logFacts1.add(shipment("L-2", "2026-11-06T10:00:00+08:00", "BOX-21", null, "FG-C01", "HOSP-甲", 30.0));
        logFacts1.add(shipment("L-3", "2026-11-06T11:00:00+08:00", "BOX-31", null, "FG-C03", "HOSP-乙", 50.0));
        log1.put("facts", logFacts1);
        insertPackage("LOG-PKG-1", "LOGISTICS", "LOG-REV-1", "PARSE-2.0", "PARSED",
                OffsetDateTime.parse("2026-11-06T12:00:00+08:00"), OffsetDateTime.parse("2026-11-06T11:30:00+08:00"),
                null, log1);

        // 5) 物流包 2（11-06，仍按 PARSE-1.9 解析 → PARSE_DIFF；BOX-9 在互不相连运输中重复）
        Map<String, Object> log2 = new LinkedHashMap<>();
        List<Map<String, Object>> logFacts2 = new ArrayList<>();
        logFacts2.add(shipment("L-4", "2026-11-06T15:00:00+08:00", "BOX-9", "PL-UNKNOWN-9", null, "W-华北仓", 12.0));
        logFacts2.add(shipment("L-5", "2026-11-06T16:00:00+08:00", "BOX-54", null, "FG-C04", "HOSP-丁", 18.0));
        log2.put("facts", logFacts2);
        insertPackage("LOG-PKG-2", "LOGISTICS", "LOG-REV-2", "PARSE-1.9", "PARSE_DIFF",
                OffsetDateTime.parse("2026-11-06T17:00:00+08:00"), OffsetDateTime.parse("2026-11-06T16:30:00+08:00"),
                null, log2);

        // 6) 机构回报 R1/R2（11-07）
        Map<String, Object> inst1 = Map.of("reports", List.of(
                Map.of("reportCode", "R-1", "eventTime", "2026-11-07T10:00:00+08:00", "nodeName", "HOSP-甲",
                        "batchCode", "FG-C01", "receivedQty", 30.0, "unit", "kg"),
                Map.of("reportCode", "R-2", "eventTime", "2026-11-07T11:00:00+08:00", "nodeName", "HOSP-乙",
                        "batchCode", "FG-C03", "receivedQty", 46.0, "unit", "kg",
                        "sensitiveContacts", List.of(Map.of("contactPerson", "李护士", "phone", "13800000000")))));
        insertPackage("INST-PKG-1", "INSTITUTION", "INST-REV-1", "PARSE-2.0", "PARSED",
                OffsetDateTime.parse("2026-11-07T15:00:00+08:00"), OffsetDateTime.parse("2026-11-07T14:30:00+08:00"),
                null, inst1);

        // 7) 检验更正 V2（11-12，PARSE-2.1）：RM-A01 改判不合格、RM-A03/A04 复检合格
        Map<String, Object> qc2 = Map.of(
                "correctionVersion", "QC-REV-2",
                "nonconformingBatches", List.of("RM-A01"),
                "inspectionResults", List.of(
                        Map.of("batchCode", "RM-A01", "conclusion", "NONCONFORMING", "item", "新增微生物限度异常"),
                        Map.of("batchCode", "RM-A03", "conclusion", "CONFORMING", "item", "复检合格"),
                        Map.of("batchCode", "RM-A04", "conclusion", "CONFORMING", "item", "复检合格")));
        insertPackage("QC-PKG-2", "QC_LAB", "QC-REV-2", "PARSE-2.1", "PARSED",
                OffsetDateTime.parse("2026-11-12T10:00:00+08:00"), OffsetDateTime.parse("2026-11-12T09:30:00+08:00"),
                "QC-PKG-1", qc2);

        // 8) 迟到机构回报 R3（11-13，PARSE-2.1）
        Map<String, Object> inst2 = Map.of("reports", List.of(
                Map.of("reportCode", "R-3", "eventTime", "2026-11-13T09:00:00+08:00", "nodeName", "HOSP-丙",
                        "batchCode", "FG-C03", "receivedQty", 15.0, "unit", "kg")));
        insertPackage("INST-PKG-2", "INSTITUTION", "INST-REV-2", "PARSE-2.1", "PARSED",
                OffsetDateTime.parse("2026-11-13T14:00:00+08:00"), OffsetDateTime.parse("2026-11-13T13:30:00+08:00"),
                null, inst2);
    }

    private Map<String, Object> observation(String codeType, String codeValue, String observed,
                                            List<Map<String, Object>> candidates, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("codeType", codeType);
        m.put("codeValue", codeValue);
        m.put("observedBatchCode", observed);
        m.put("candidates", candidates);
        m.put("note", note);
        return m;
    }

    private Map<String, Object> candidate(String batch, String confidence, boolean ambiguous) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("candidateBatchCode", batch);
        m.put("confidence", confidence);
        m.put("ambiguous", ambiguous);
        m.put("evidence", List.of(Map.of("source", "MDM-PKG-1")));
        return m;
    }

    private Map<String, Object> fact(String code, String type, String time, String up, String down, Double qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("factCode", code);
        m.put("factType", type);
        m.put("eventTime", time);
        m.put("upstreamBatch", up);
        m.put("downstreamBatch", down);
        m.put("quantity", qty);
        m.put("unit", "kg");
        return m;
    }

    private Map<String, Object> shipment(String code, String time, String box, String productionLot,
                                         String batch, String node, Double qty) {
        Map<String, Object> m = fact(code, "LOGISTICS", time, batch, node, qty);
        m.put("boxCode", box);
        m.put("productionLot", productionLot);
        m.put("nodeName", node);
        m.put("signedQty", qty);
        return m;
    }

    private void insertPackage(String code, String system, String revision, String parseVersion, String parseStatus,
                               OffsetDateTime receivedAt, OffsetDateTime eventTime, String supersedes,
                               Map<String, Object> payload) {
        String hash = sha256(Json.stringify(payload));
        Long packageId = jdbc.queryForObject("""
                INSERT INTO source_package(package_code, source_system, source_revision, payload_hash,
                       parse_rule_version, parse_status, received_at, event_time, supersedes_package_code, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id
                """, Long.class, code, system, revision, hash, parseVersion, parseStatus,
                receivedAt, eventTime, supersedes, Json.stringify(payload));
        // 登记原始事实
        List<Map<String, Object>> facts = payload.containsKey("facts")
                ? (List<Map<String, Object>>) payload.get("facts") : List.of();
        for (Map<String, Object> f : facts) {
            jdbc.update("""
                    INSERT INTO raw_fact(package_id, fact_type, fact_code, event_time, payload)
                    VALUES (?, ?, ?, ?, ?::jsonb)
                    """, packageId, f.get("factType"), f.get("factCode"),
                    OffsetDateTime.parse((String) f.get("eventTime")), Json.stringify(f));
        }
        List<Map<String, Object>> reports = payload.containsKey("reports")
                ? (List<Map<String, Object>>) payload.get("reports") : List.of();
        for (Map<String, Object> r : reports) {
            jdbc.update("""
                    INSERT INTO raw_fact(package_id, fact_type, fact_code, event_time, payload)
                    VALUES (?, 'INSTITUTION_REPORT', ?, ?, ?::jsonb)
                    """, packageId, r.get("reportCode"), OffsetDateTime.parse((String) r.get("eventTime")),
                    Json.stringify(r));
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
