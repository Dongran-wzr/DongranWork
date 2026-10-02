package com.dongran.work.infrastructure;

import com.dongran.work.exception.ApiException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.util.*;

public final class ToolCallValidator {
  public static Map<String, Object> arguments(
      Database db, Map<String, Object> call, List<Map<String, Object>> tools) {
    if (!(call.get("id") instanceof String id)
        || id.isBlank()
        || !(call.get("function") instanceof Map<?, ?> f)) throw ApiException.bad("工具调用缺少标识或函数。");
    String name = String.valueOf(f.get("name"));
    var definition =
        tools.stream()
            .map(t -> (Map<?, ?>) t.get("function"))
            .filter(t -> name.equals(t.get("name")))
            .findFirst()
            .orElseThrow(() -> ApiException.forbidden("当前模式不允许工具：" + name));
    try {
      ObjectMapper strict =
          db.mapper
              .copy()
              .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      JsonNode node = strict.readTree(String.valueOf(f.get("arguments")));
      if (node == null || !node.isObject()) throw new IllegalArgumentException();
      validate(node, strict.valueToTree(definition.get("parameters")), "arguments");
      return db.object(node.toString());
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw ApiException.bad("工具参数不是完整、严格合法的 JSON 对象。");
    }
  }

  private static void validate(JsonNode value, JsonNode schema, String path) {
    String type = schema.path("type").asText();
    boolean valid =
        switch (type) {
          case "object" -> value.isObject();
          case "string" -> value.isTextual();
          case "integer" -> value.isIntegralNumber();
          case "number" -> value.isNumber();
          case "boolean" -> value.isBoolean();
          case "array" -> value.isArray();
          default -> true;
        };
    if (!valid) throw ApiException.bad("工具参数类型错误：" + path);
    if (schema.has("enum")) {
      boolean found = false;
      for (var item : schema.get("enum")) if (item.equals(value)) found = true;
      if (!found) throw ApiException.bad("工具参数不在允许范围：" + path);
    }
    if (value.isTextual()
        && schema.has("maxLength")
        && value.textValue().length() > schema.get("maxLength").asInt())
      throw ApiException.bad("工具参数过长：" + path);
    if (value.isNumber()
        && ((schema.has("minimum") && value.doubleValue() < schema.get("minimum").asDouble())
            || (schema.has("maximum") && value.doubleValue() > schema.get("maximum").asDouble())))
      throw ApiException.bad("工具参数超出范围：" + path);
    if (value.isArray() && schema.has("items"))
      for (int i = 0; i < value.size(); i++)
        validate(value.get(i), schema.get("items"), path + "[" + i + "]");
    if (value.isObject()) {
      for (var key : schema.path("required"))
        if (!value.has(key.asText())) throw ApiException.bad("工具参数缺少字段：" + key.asText());
      value
          .fields()
          .forEachRemaining(
              e -> {
                var child = schema.path("properties").get(e.getKey());
                if (child != null) validate(e.getValue(), child, path + "." + e.getKey());
                else if (schema.path("additionalProperties").isBoolean()
                    && !schema.path("additionalProperties").asBoolean())
                  throw ApiException.bad("未知工具参数：" + e.getKey());
              });
    }
  }
}
