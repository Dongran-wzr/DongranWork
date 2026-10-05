package com.dongran.work.repository;

import com.dongran.work.infrastructure.Database;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class SkillEvolutionRepository {
  public final Database db;

  public SkillEvolutionRepository(Database db) {
    this.db = db;
  }

  public String enqueue(String task, String trigger, String model) {
    String id = Database.id(), now = Database.now();
    db.jdbc.update(
        "INSERT INTO skill_evolution_runs VALUES (?,?,?,'queued',NULL,?,?,?)",
        id,
        task,
        trigger,
        model,
        now,
        now);
    return id;
  }

  public void finish(String id, String status, String diagnosis) {
    db.jdbc.update(
        "UPDATE skill_evolution_runs SET status=?,diagnosis=?,updated_at=? WHERE id=?",
        status,
        diagnosis,
        Database.now(),
        id);
  }

  public Map<String, Object> candidate(String id) {
    return db.one("SELECT * FROM skill_evolution_candidates WHERE id=?", id);
  }

  public List<Map<String, Object>> candidates(String project) {
    return db.jdbc.queryForList(
        "SELECT c.*,r.trigger,r.diagnosis,r.task_id FROM skill_evolution_candidates c JOIN skill_evolution_runs r ON r.id=c.run_id WHERE (? IS NULL OR c.project_id=? OR c.project_id IS NULL) ORDER BY c.created_at DESC LIMIT 200",
        project,
        project);
  }

  public List<Map<String, Object>> runs(String task) {
    return db.jdbc.queryForList(
        "SELECT r.*,c.id AS candidate_id,c.status AS candidate_status FROM skill_evolution_runs r LEFT JOIN skill_evolution_candidates c ON c.run_id=r.id WHERE r.task_id=? ORDER BY r.created_at DESC LIMIT 50",
        task);
  }

  public List<Map<String, Object>> cases(String id) {
    return db.jdbc.queryForList(
        "SELECT * FROM skill_eval_cases WHERE candidate_id=? ORDER BY id", id);
  }

  public List<Map<String, Object>> results(String id) {
    return db.jdbc.queryForList(
        "SELECT * FROM skill_eval_results WHERE candidate_id=? ORDER BY created_at DESC,rowid DESC",
        id);
  }

  public List<Map<String, Object>> versions(String skill) {
    return db.jdbc.queryForList(
        "SELECT * FROM skill_versions WHERE skill_id=? ORDER BY created_at DESC,rowid DESC", skill);
  }

  public void snapshot(Map<String, Object> skill, String content, String task) {
    if (!db.jdbc
        .queryForList(
            "SELECT id FROM skill_versions WHERE skill_id=? AND content_hash=?",
            skill.get("id"),
            skill.get("content_hash"))
        .isEmpty()) return;
    db.jdbc.update(
        "INSERT INTO skill_versions VALUES (?,?,?,?,?,?,?)",
        Database.id(),
        skill.get("id"),
        skill.get("version"),
        content,
        skill.get("content_hash"),
        task,
        Database.now());
  }

  public void status(String id, String status) {
    db.jdbc.update(
        "UPDATE skill_evolution_candidates SET status=?,reviewed_at=? WHERE id=?",
        status,
        Database.now(),
        id);
  }
}
