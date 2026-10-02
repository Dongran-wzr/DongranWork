package com.dongran.work.repository;

import com.dongran.work.infrastructure.Database;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TaskEventRepository {
  private final Database db;
  private final JdbcTemplate jdbc;

  public TaskEventRepository(Database db) {
    this.db = db;
    this.jdbc = db.jdbc;
  }

  public int insert(String taskId, String type, String data, String createdAt) {
    return jdbc.update(
        "INSERT INTO events(task_id,type,data,created_at) VALUES (?,?,?,?)",
        taskId,
        type,
        data,
        createdAt);
  }

  public List<Map<String, Object>> findAfter(String taskId, long cursor) {
    return jdbc.queryForList(
        "SELECT id,type,data,created_at AS createdAt FROM events WHERE task_id=? AND id>? ORDER BY id LIMIT 200",
        taskId,
        cursor);
  }
}
