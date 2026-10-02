package com.dongran.work.infrastructure;

import com.dongran.work.exception.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Database {
  public final JdbcTemplate jdbc;
  public final ObjectMapper mapper;

  public Database(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public static String now() {
    return new java.time.format.DateTimeFormatterBuilder()
        .appendInstant(3)
        .toFormatter()
        .format(Instant.now());
  }

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }

  public Map<String, Object> object(String value) {
    try {
      return mapper.readValue(value, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored document", e);
    }
  }

  public Map<String, Object> one(String sql, Object... args) {
    var rows = jdbc.queryForList(sql, args);
    if (rows.isEmpty()) throw ApiException.missing("记录不存在。");
    return rows.getFirst();
  }

  public static String text(Map<String, ?> data, String key, String fallback) {
    Object value = data.get(key);
    return value instanceof String s ? s : fallback;
  }

  public static String required(Map<String, ?> data, String key, int max) {
    Object value = data.get(key);
    if (!(value instanceof String s) || s.isBlank() || s.length() > max || s.indexOf('\0') >= 0)
      throw ApiException.bad(key + " 不能为空或超过长度限制。");
    return s.trim();
  }

  public static boolean bool(Map<String, ?> data, String key, boolean fallback) {
    return data.get(key) instanceof Boolean b ? b : fallback;
  }

  public static int number(Map<String, ?> data, String key, int fallback, int min, int max) {
    Object value = data.get(key);
    if (value == null) return fallback;
    if (!(value instanceof Number n)
        || n.doubleValue() != n.intValue()
        || n.intValue() < min
        || n.intValue() > max) throw ApiException.bad(key + " 超出允许范围。");
    return n.intValue();
  }
}
