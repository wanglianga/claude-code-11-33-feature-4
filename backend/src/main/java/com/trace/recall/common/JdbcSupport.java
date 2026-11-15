package com.trace.recall.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量 JDBC 支持：统一把 snake_case 列名转换为前端使用的 camelCase 键，
 * jsonb 列还原为 Jackson JsonNode，其余类型保持原生（LocalDateTime/OffsetDateTime/BigDecimal 等）。
 */
@Component
public class JdbcSupport {

    private final JdbcTemplate jdbc;

    public JdbcSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public static String camel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (int i = 0; i < snake.length(); i++) {
            char c = snake.charAt(i);
            if (c == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private final RowMapper<Map<String, Object>> mapper = (rs, rowNum) -> {
        Map<String, Object> row = new LinkedHashMap<>();
        var meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String label = meta.getColumnLabel(i);
            Object value = rs.getObject(i);
            if (label.endsWith("_json") || value instanceof org.postgresql.util.PGobject) {
                value = Json.toNative(value);
            } else if (value instanceof java.sql.Date sqlDate) {
                value = sqlDate.toLocalDate();
            } else if (value instanceof java.sql.Array sqlArray) {
                try {
                    Object arr = sqlArray.getArray();
                    value = arr instanceof Object[] objects ? new java.util.ArrayList<>(java.util.Arrays.asList(objects)) : java.util.List.of();
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException("数组列读取失败: " + label, e);
                }
            }
            row.put(camel(label), value);
        }
        return row;
    };

    public List<Map<String, Object>> query(String sql, Object... args) {
        return jdbc.query(sql, mapper, args);
    }

    public Map<String, Object> queryOne(String sql, Object... args) {
        List<Map<String, Object>> rows = query(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insertReturningId(String sql, Object... args) {
        Long id = jdbc.queryForObject(sql, Long.class, args);
        return id == null ? -1L : id;
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }
}
