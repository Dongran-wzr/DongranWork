package com.dongran.work.repository;

import com.dongran.work.infrastructure.Database;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TaskRepository {
  private final JdbcTemplate jdbc;

  public TaskRepository(Database database) {
    this.jdbc = database.jdbc;
  }

  public void insert(
      String id,
      String projectId,
      String title,
      String prompt,
      String mode,
      String createdAt,
      String scheduleId) {
    jdbc.update(
        "INSERT INTO tasks(id,project_id,title,prompt,status,mode,created_at,updated_at,schedule_id) VALUES (?,?,?,?,'queued',?,?,?,?)",
        id,
        projectId,
        title,
        prompt,
        mode,
        createdAt,
        createdAt,
        scheduleId);
  }

  public Optional<Map<String, Object>> find(String id) {
    var rows =
        jdbc.queryForList(
            "SELECT id,project_id AS projectId,title,prompt,status,mode,created_at AS createdAt,updated_at AS updatedAt,error,schedule_id AS scheduleId FROM tasks WHERE id=?",
            id);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public List<Map<String, Object>> findAll(String projectId) {
    return jdbc.queryForList(
        "SELECT id,project_id AS projectId,title,status,created_at AS createdAt,updated_at AS updatedAt,error FROM tasks WHERE (? IS NULL OR project_id=?) ORDER BY created_at DESC LIMIT 200",
        projectId,
        projectId);
  }

  public List<Map<String, Object>> messages(String taskId) {
    return jdbc.queryForList(
        "SELECT id,role,agent,content,created_at AS createdAt FROM messages WHERE task_id=? ORDER BY id",
        taskId);
  }

  public void updateMode(String id, String mode) {
    jdbc.update("UPDATE tasks SET mode=?,updated_at=? WHERE id=?", mode, Database.now(), id);
  }

  public void updateStatus(String id, String status, String error, String updatedAt) {
    jdbc.update(
        "UPDATE tasks SET status=?,error=?,updated_at=? WHERE id=?", status, error, updatedAt, id);
  }

  public void addMessage(
      String taskId, String role, String agent, String content, String createdAt) {
    jdbc.update(
        "INSERT INTO messages(task_id,role,agent,content,created_at) VALUES (?,?,?,?,?)",
        taskId,
        role,
        agent,
        content,
        createdAt);
  }
}
