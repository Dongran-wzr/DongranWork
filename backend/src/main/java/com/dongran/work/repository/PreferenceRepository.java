package com.dongran.work.repository;

import com.dongran.work.infrastructure.Database;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PreferenceRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public PreferenceRepository(Database database) {
    this.jdbc = database.jdbc;
    this.mapper = database.mapper;
  }

  public Map<String, Object> findAll() {
    var result = new LinkedHashMap<String, Object>();
    jdbc.query(
        "SELECT key,value FROM preferences ORDER BY key",
        rs -> {
          try {
            result.put(rs.getString(1), mapper.readValue(rs.getString(2), Object.class));
          } catch (Exception e) {
            throw new IllegalStateException("Invalid stored preference", e);
          }
        });
    return result;
  }

  public void upsert(String key, String value) {
    jdbc.update(
        "INSERT INTO preferences(key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        key,
        value);
  }
}
