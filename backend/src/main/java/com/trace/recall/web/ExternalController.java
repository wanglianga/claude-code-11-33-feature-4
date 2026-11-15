package com.trace.recall.web;

import com.trace.recall.release.ReleaseService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部处置系统接口：经 ApiKeyInterceptor 校验 X-API-Key。
 * 仅返回发布版本的脱敏摘要，SQL 与服务层均不选取原始载荷和敏感联系人字段。
 */
@RestController
@RequestMapping("/external")
public class ExternalController {

    private final JdbcTemplate jdbc;
    private final ReleaseService release;

    public ExternalController(JdbcTemplate jdbc, ReleaseService release) {
        this.jdbc = jdbc;
        this.release = release;
    }

    @GetMapping("/releases")
    public List<Map<String, Object>> releases() {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT release_code, version_no, status, diff_type, published_at
                FROM release_version ORDER BY id
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("releaseCode", rs.getString("release_code"));
            m.put("versionNo", rs.getInt("version_no"));
            m.put("status", rs.getString("status"));
            m.put("diffType", rs.getString("diff_type"));
            m.put("publishedAt", rs.getObject("published_at"));
            return m;
        });
        return new ArrayList<>(rows);
    }

    @GetMapping("/releases/{code}")
    public Map<String, Object> summary(@PathVariable String code) {
        return release.externalSummary(code);
    }
}
