package com.trace.recall.cost;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 召回处置成本证据归因工作台接口（内部 /api，无 API Key）。
 * 本接口只登记凭证、编译/查询成本事实与审计投影，不产生任何付款、报销、销毁、退运、调拨或审批动作。
 */
@RestController
@RequestMapping("/api/cost")
public class CostController {

    private final CostMetaService meta;
    private final CostWorkspaceService workspaces;
    private final CostVoucherService vouchers;
    private final CostFactCompiler compiler;
    private final CostQueryService query;
    private final CostRevisionService revision;
    private final CostDemoScenarioService demo;

    public CostController(CostMetaService meta, CostWorkspaceService workspaces, CostVoucherService vouchers,
                          CostFactCompiler compiler, CostQueryService query, CostRevisionService revision,
                          CostDemoScenarioService demo) {
        this.meta = meta;
        this.workspaces = workspaces;
        this.vouchers = vouchers;
        this.compiler = compiler;
        this.query = query;
        this.revision = revision;
        this.demo = demo;
    }

    @GetMapping("/meta/periods")
    public List<Map<String, Object>> periods() {
        return meta.periods();
    }

    @GetMapping("/meta/accounting-rules")
    public List<Map<String, Object>> accountingRules() {
        return meta.accountingRules();
    }

    @GetMapping("/meta/rule-books")
    public List<Map<String, Object>> ruleBooks() {
        return meta.ruleBooks();
    }

    @GetMapping("/meta/releases")
    public List<Map<String, Object>> releases() {
        return meta.publishedReleases();
    }

    @PostMapping("/demo-scenario")
    public Map<String, Object> demoScenario() {
        return demo.ensureDemoScenario();
    }

    @GetMapping("/workspaces")
    public List<Map<String, Object>> listWorkspaces() {
        return workspaces.list();
    }

    @PostMapping("/workspaces")
    public Map<String, Object> createWorkspace(@RequestBody Map<String, Object> body) {
        return workspaces.create(body);
    }

    @GetMapping("/workspaces/{id}")
    public Map<String, Object> workspace(@PathVariable long id) {
        return workspaces.detail(id);
    }

    @PostMapping("/workspaces/{id}/vouchers")
    public Map<String, Object> registerVoucher(@PathVariable long id, @RequestBody Map<String, Object> body) {
        long voucherId = vouchers.register(id, body);
        return Map.of("voucherId", voucherId);
    }

    @GetMapping("/workspaces/{id}/vouchers")
    public List<Map<String, Object>> listVouchers(@PathVariable long id) {
        return vouchers.listVouchers(id);
    }

    @GetMapping("/workspaces/{id}/identities")
    public List<Map<String, Object>> identities(@PathVariable long id) {
        return query.identities(id);
    }

    @PostMapping("/workspaces/{id}/compile")
    public Map<String, Object> compile(@PathVariable long id) {
        return compiler.compile(id);
    }

    @PostMapping("/workspaces/{id}/publish")
    public Map<String, Object> publish(@PathVariable long id) {
        return revision.publish(id);
    }

    @PostMapping("/workspaces/{id}/revise")
    public Map<String, Object> revise(@PathVariable long id, @RequestBody Map<String, String> body) {
        return revision.revise(id, body.get("ruleBookVersion"), body.get("note"));
    }

    @GetMapping("/workspaces/{id}/facts")
    public List<Map<String, Object>> facts(@PathVariable long id) {
        return query.facts(id);
    }

    @GetMapping("/workspaces/{id}/attributions")
    public List<Map<String, Object>> attributions(@PathVariable long id) {
        return query.attributions(id);
    }

    @GetMapping("/workspaces/{id}/periods")
    public List<Map<String, Object>> periodAssessments(@PathVariable long id) {
        return query.periods(id);
    }

    @GetMapping("/workspaces/{id}/findings")
    public List<Map<String, Object>> findings(@PathVariable long id) {
        return query.findings(id);
    }

    @GetMapping("/workspaces/{id}/facts/{factId}/audit")
    public Map<String, Object> auditTrail(@PathVariable long id, @PathVariable long factId) {
        return query.auditTrail(id, factId);
    }
}
