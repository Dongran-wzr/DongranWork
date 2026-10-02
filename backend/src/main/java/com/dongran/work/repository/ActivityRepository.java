package com.dongran.work.repository;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ActivityRepository {
  private final JdbcTemplate jdbc;

  public ActivityRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Map<String, Object>> daily(String projectId) {
    return jdbc.queryForList(
        "SELECT date(created_at,'localtime') AS date,count(*) AS count FROM tasks WHERE (? IS NULL OR project_id=?) GROUP BY date(created_at,'localtime') ORDER BY date",
        projectId,
        projectId);
  }
}
