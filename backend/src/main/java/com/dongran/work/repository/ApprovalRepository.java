package com.dongran.work.repository;

import com.dongran.work.infrastructure.Database;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ApprovalRepository {
  private final Database db;
  private final JdbcTemplate jdbc;

  public ApprovalRepository(Database db) {
    this.db = db;
    this.jdbc = db.jdbc;
  }

  public int insertPending(
      String id,
      String taskId,
      String projectId,
      String action,
      String arguments,
      String createdAt,
      String updatedAt) {
    return jdbc.update(
        "INSERT INTO approvals(id,task_id,project_id,action,arguments,status,created_at,updated_at) VALUES (?,?,?,?,?,'pending',?,?)",
        id,
        taskId,
        projectId,
        action,
        arguments,
        createdAt,
        updatedAt);
  }

  public int expirePending(String updatedAt, String id) {
    return jdbc.update(
        "UPDATE approvals SET status='expired',updated_at=? WHERE id=? AND status='pending'",
        updatedAt,
        id);
  }

  public List<Map<String, Object>> findByTask(String taskId) {
    return jdbc.queryForList("SELECT * FROM approvals WHERE task_id=? ORDER BY created_at", taskId);
  }

  public int resolvePending(String status, String updatedAt, String id) {
    return jdbc.update(
        "UPDATE approvals SET status=?,updated_at=? WHERE id=? AND status='pending'",
        status,
        updatedAt,
        id);
  }
}
