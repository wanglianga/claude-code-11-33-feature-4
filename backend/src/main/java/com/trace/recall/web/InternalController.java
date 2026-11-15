package com.trace.recall.web;

import com.trace.recall.ingestion.IngestionService;
import com.trace.recall.release.ReleaseService;
import com.trace.recall.verification.VerificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 内部质量工作台接口：可查看原始载荷，无 API Key（内网部署）。 */
@RestController
@RequestMapping("/api")
public class InternalController {

    private final IngestionService ingestion;
    private final VerificationService verification;
    private final ReleaseService release;

    public InternalController(IngestionService ingestion, VerificationService verification, ReleaseService release) {
        this.ingestion = ingestion;
        this.verification = verification;
        this.release = release;
    }

    @GetMapping("/source-packages")
    public List<Map<String, Object>> packages() {
        return ingestion.listPackages();
    }

    @GetMapping("/source-packages/{code}")
    public Map<String, Object> packageDetail(@PathVariable String code) {
        return ingestion.getPackage(code);
    }

    @GetMapping("/meta/inspection-versions")
    public List<Map<String, Object>> inspectionVersions() {
        return ingestion.inspectionVersions();
    }

    @GetMapping("/meta/parse-rules")
    public List<Map<String, Object>> parseRules() {
        return ingestion.listParseRules();
    }

    @GetMapping("/batches")
    public List<Map<String, Object>> batches() {
        return ingestion.listBatches();
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs() {
        return verification.listRuns();
    }

    @PostMapping("/runs")
    public Map<String, Object> createRun(@RequestBody Map<String, String> body) {
        return verification.createRun(
                body.get("inspectionCorrectionVersion"),
                OffsetDateTime.parse(body.get("cutoffAt")),
                body.get("createdBy"));
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> runDetail(@PathVariable long id) {
        return verification.getRun(id);
    }

    @GetMapping("/runs/{id}/batches/{batchCode}/trace")
    public Map<String, Object> trace(@PathVariable long id, @PathVariable String batchCode) {
        return verification.batchTrace(id, batchCode);
    }

    @PostMapping("/runs/{id}/publish")
    public Map<String, Object> publish(@PathVariable long id) {
        return release.publish(id);
    }

    @GetMapping("/releases")
    public List<Map<String, Object>> releases() {
        return release.listReleases();
    }
}
