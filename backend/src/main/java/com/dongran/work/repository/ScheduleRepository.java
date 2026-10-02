package com.dongran.work.repository;

import com.dongran.work.infrastructure.Database;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ScheduleRepository {
  private final Database db;
  private final JdbcTemplate jdbc;

  public ScheduleRepository(Database db) {
    this.db = db;
    this.jdbc = db.jdbc;
  }

  public List<Map<String, Object>> findAll() {
    return jdbc.queryForList("SELECT document FROM schedules ORDER BY id");
  }

  public Map<String, Object> findRequired(String id) {
    return db.one("SELECT document FROM schedules WHERE id=?", id);
  }

  public List<Map<String, Object>> findById(String id) {
    return jdbc.queryForList("SELECT document FROM schedules WHERE id=?", id);
  }

  public int delete(String id) {
    return jdbc.update("DELETE FROM schedules WHERE id=?", id);
  }

  public int upsert(String id, String document, String nextRun, String updatedAt) {
    return jdbc.update(
        "INSERT INTO schedules VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET document=excluded.document,next_run=excluded.next_run,updated_at=excluded.updated_at",
        id,
        document,
        nextRun,
        updatedAt);
  }

  public List<Map<String, Object>> findScheduled() {
    return jdbc.queryForList(
        "SELECT id,document,next_run FROM schedules WHERE next_run IS NOT NULL");
  }

  public int interruptClaims() {
    return jdbc.update(
        "UPDATE schedule_runs SET status='interrupted',error='应用退出时尚未提交执行。' WHERE status='claimed'");
  }

  public List<Map<String, Object>> findDue(String now) {
    return jdbc.queryForList(
        "SELECT id,next_run FROM schedules WHERE next_run IS NOT NULL AND julianday(next_run)<=julianday(?) ORDER BY next_run LIMIT 20",
        now);
  }

  public List<Map<String, Object>> findClaimCandidate(String id) {
    return jdbc.queryForList("SELECT document,next_run FROM schedules WHERE id=?", id);
  }

  public int claim(String id, String scheduleId, String dueAt) {
    return jdbc.update(
        "INSERT OR IGNORE INTO schedule_runs(id,schedule_id,due_at,status) VALUES (?,?,?,'claimed')",
        id,
        scheduleId,
        dueAt);
  }

  public Integer countActiveTasks(String scheduleId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM tasks WHERE schedule_id=? AND status IN ('queued','running','awaiting_approval')",
        Integer.class,
        scheduleId);
  }

  public int skip(String scheduleId, String dueAt) {
    return jdbc.update(
        "UPDATE schedule_runs SET status='skipped',error='上一轮仍在执行。' WHERE schedule_id=? AND due_at=?",
        scheduleId,
        dueAt);
  }

  public int markSubmitted(String taskId, String scheduleId, String dueAt) {
    return jdbc.update(
        "UPDATE schedule_runs SET status='submitted',task_id=? WHERE schedule_id=? AND due_at=?",
        taskId,
        scheduleId,
        dueAt);
  }

  public int markFailed(String error, String scheduleId, String dueAt) {
    return jdbc.update(
        "UPDATE schedule_runs SET status='failed',error=? WHERE schedule_id=? AND due_at=?",
        error,
        scheduleId,
        dueAt);
  }

  public List<Map<String, Object>> findRuns(String scheduleId) {
    return jdbc.queryForList(
        "SELECT r.*,t.status AS taskStatus FROM schedule_runs r LEFT JOIN tasks t ON t.id=r.task_id WHERE r.schedule_id=? ORDER BY due_at DESC LIMIT 100",
        scheduleId);
  }
}
