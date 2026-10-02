package com.dongran.work.repository;

import com.dongran.work.infrastructure.Database;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CommandRepository {
  private final Database db;
  private final JdbcTemplate jdbc;

  public CommandRepository(Database db) {
    this.db = db;
    this.jdbc = db.jdbc;
  }

  public Map<String, Object> findRequired(String id) {
    return db.one(
        "SELECT *,execution_mode AS executionMode,sandbox_backend AS sandboxBackend,"
            + "workspace_path AS workspacePath,sync_status AS syncStatus FROM command_runs WHERE id=?",
        id);
  }

  public int insertQueued(
      String id,
      String projectId,
      String taskId,
      String command,
      String createdAt,
      String updatedAt) {
    return jdbc.update(
        "INSERT INTO command_runs(id,project_id,task_id,command,status,created_at,updated_at,execution_mode,sync_status) VALUES (?,?,?,?,'queued',?,?,'sandbox-required','pending')",
        id,
        projectId,
        taskId,
        command,
        createdAt,
        updatedAt);
  }

  public int markRunning(String updatedAt, String id) {
    return jdbc.update(
        "UPDATE command_runs SET status='running',updated_at=? WHERE id=? AND status='queued'",
        updatedAt,
        id);
  }

  public void sandbox(String id, String backend, String workspace, String syncStatus) {
    jdbc.update(
        "UPDATE command_runs SET sandbox_backend=?,workspace_path=?,sync_status=? WHERE id=?",
        backend,
        workspace,
        syncStatus,
        id);
  }

  public int finish(String status, Integer exitCode, String updatedAt, String id) {
    return jdbc.update(
        "UPDATE command_runs SET status=?,exit_code=?,updated_at=? WHERE id=?",
        status,
        exitCode,
        updatedAt,
        id);
  }

  public int markCancelled(String updatedAt, String id) {
    return jdbc.update(
        "UPDATE command_runs SET status='cancelled',updated_at=? WHERE id=?", updatedAt, id);
  }

  public int markFailed(String output, String updatedAt, String id) {
    return jdbc.update(
        "UPDATE command_runs SET status='failed',output=?,updated_at=? WHERE id=? AND status!='cancelled'",
        output,
        updatedAt,
        id);
  }

  public int updateOutput(String output, String updatedAt, String id) {
    return jdbc.update(
        "UPDATE command_runs SET output=?,updated_at=? WHERE id=?", output, updatedAt, id);
  }

  public int cancelActive(String updatedAt, String id) {
    return jdbc.update(
        "UPDATE command_runs SET status='cancelled',sync_status='not-applied',updated_at=? WHERE id=? AND status IN ('queued','running')",
        updatedAt,
        id);
  }

  public List<Map<String, Object>> findActiveByTask(String taskId) {
    return jdbc.queryForList(
        "SELECT id FROM command_runs WHERE task_id=? AND status IN ('queued','running')", taskId);
  }
}
