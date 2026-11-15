package com.trace.recall.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.postgresql.util.PGobject;

import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL JSONB 与 Jackson 之间的归一化工具。
 * 所有结构化字段（payload、快照、明细、依据）统一以 jsonb 落库，保留原始结构不拍平。
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static String stringify(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    public static PGobject jsonb(Object value) {
        try {
            PGobject obj = new PGobject();
            obj.setType("jsonb");
            obj.setValue(MAPPER.writeValueAsString(value));
            return obj;
        } catch (Exception e) {
            throw new IllegalStateException("JSONB 序列化失败", e);
        }
    }

    public static JsonNode tree(Object dbValue) {
        if (dbValue == null) {
            return null;
        }
        try {
            String text = dbValue instanceof PGobject pg ? pg.getValue() : String.valueOf(dbValue);
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("JSONB 解析失败", e);
        }
    }

    /** 把 jsonb 列还原为 Jackson 序列化友好的原生 Map/List/标量。 */
    public static Object toNative(Object dbValue) {
        return toNative(tree(dbValue));
    }

    private static Object toNative(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            node.fields().forEachRemaining(e -> map.put(e.getKey(), toNative(e.getValue())));
            return map;
        }
        if (node.isArray()) {
            java.util.List<Object> list = new ArrayList<>();
            node.forEach(child -> list.add(toNative(child)));
            return list;
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }

    public static <T> T read(Object dbValue, Class<T> type) {
        JsonNode node = tree(dbValue);
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return MAPPER.treeToValue(node, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSONB 转换失败", e);
        }
    }

    public static <T> List<T> readList(Object dbValue, Class<T> type) {
        JsonNode node = tree(dbValue);
        List<T> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        node.forEach(item -> {
            try {
                result.add(MAPPER.treeToValue(item, type));
            } catch (Exception e) {
                throw new IllegalStateException("JSONB 列表转换失败", e);
            }
        });
        return result;
    }

    public static ArrayNode createArray() {
        return MAPPER.createArrayNode();
    }
}
