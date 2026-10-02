package com.dongran.work.repository;

import com.dongran.work.infrastructure.Database;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class SkillRepository {
  private final Database db;

  public SkillRepository(Database db) {
    this.db = db;
  }

  public List<Map<String, Object>> list(String project) {
    return db.jdbc.queryForList(
        "SELECT * FROM skills WHERE project_id IS NULL OR project_id=? ORDER BY project_id IS NOT NULL DESC,name",
        project);
  }

  public Map<String, Object> get(String id) {
    return db.one("SELECT * FROM skills WHERE id=?", id);
  }

  public void insert(
      String id,
      String project,
      String name,
      String description,
      String version,
      String root,
      String hash,
      String source) {
    String now = Database.now();
    db.jdbc.update(
        "INSERT INTO skills(id,project_id,name,description,version,root_path,content_hash,source,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
        id,
        project,
        name,
        description,
        version,
        root,
        hash,
        source,
        now,
        now);
  }

  public void update(String id, String name, String description, String version, String hash) {
    db.jdbc.update(
        "UPDATE skills SET name=?,description=?,version=?,content_hash=?,updated_at=? WHERE id=?",
        name,
        description,
        version,
        hash,
        Database.now(),
        id);
  }

  public void state(String id, boolean enabled, boolean auto) {
    get(id);
    db.jdbc.update(
        "UPDATE skills SET enabled=?,auto_match=?,updated_at=? WHERE id=?",
        enabled ? 1 : 0,
        auto ? 1 : 0,
        Database.now(),
        id);
  }

  public void delete(String id) {
    get(id);
    db.jdbc.update("DELETE FROM skills WHERE id=?", id);
  }
}
