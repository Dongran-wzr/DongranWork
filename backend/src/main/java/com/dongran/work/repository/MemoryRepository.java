package com.dongran.work.repository;

import com.dongran.work.exception.ApiException;
import com.dongran.work.infrastructure.Database;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MemoryRepository {
  private final Database db;

  public MemoryRepository(Database db) {
    this.db = db;
  }

  public List<Map<String, Object>> rows() {
    return db.jdbc.queryForList(
        "SELECT m.*,p.name AS project_name FROM memories m LEFT JOIN projects p ON p.id=m.project_id ORDER BY m.updated_at DESC");
  }

  public List<Map<String, Object>> legacy() {
    return rows().stream().filter(r -> "active".equals(r.get("status"))).map(this::entry).toList();
  }

  public Map<String, Object> entry(Map<String, Object> r) {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", r.get("id"));
    m.put("title", r.get("title"));
    m.put("content", r.get("content"));
    m.put("scope", r.get("project_id") == null ? "global" : "project");
    m.put("projectId", r.get("project_id"));
    m.put("projectName", Objects.toString(r.get("project_name"), ""));
    m.put("createdAt", r.get("created_at"));
    m.put("updatedAt", r.get("updated_at"));
    return m;
  }

  public Map<String, Object> get(String id) {
    return rows().stream()
        .filter(r -> id.equals(r.get("id")))
        .findFirst()
        .orElseThrow(() -> ApiException.missing("记忆不存在。"));
  }

  private void archive(Map<String, Object> row) {
    db.jdbc.update(
        "INSERT INTO memory_versions(memory_id,document,created_at) VALUES(?,?,?)",
        row.get("id"),
        db.json(row),
        Database.now());
  }

  @Transactional
  public synchronized void replace(List<Map<String, Object>> entries) {
    var ids = new HashSet<String>();
    for (var e : entries) {
      String id = String.valueOf(e.get("id"));
      ids.add(id);
      var old = rows().stream().filter(r -> id.equals(r.get("id"))).findFirst();
      if (old.isPresent()) {
        var r = old.get();
        if (!"active".equals(r.get("status")))
          throw ApiException.conflict("记忆已归档或删除，请刷新后再编辑，旧设置不能恢复条目。");
        if (!Objects.equals(r.get("title"), e.get("title"))
            || !Objects.equals(r.get("content"), e.get("content"))
            || !Objects.equals(r.get("project_id"), e.get("projectId"))
            || !"active".equals(r.get("status"))) {
          archive(r);
          db.jdbc.update(
              "UPDATE memories SET title=?,content=?,project_id=?,status='active',version=version+1,updated_at=? WHERE id=?",
              e.get("title"),
              e.get("content"),
              e.get("projectId"),
              Database.now(),
              id);
        }
      } else
        db.jdbc.update(
            "INSERT INTO memories(id,project_id,title,content,created_at,updated_at) VALUES(?,?,?,?,?,?)",
            id,
            e.get("projectId"),
            e.get("title"),
            e.get("content"),
            e.get("createdAt"),
            e.get("updatedAt"));
    }
    for (var r : rows())
      if ("active".equals(r.get("status")) && !ids.contains(r.get("id")))
        change(String.valueOf(r.get("id")), "deleted", ((Number) r.get("version")).intValue());
  }

  @Transactional
  public synchronized Map<String, Object> propose(
      String project,
      String title,
      String content,
      String task,
      long message,
      String quote,
      String origin,
      boolean active) {
    var same =
        rows().stream()
            .filter(
                r ->
                    Objects.equals(project, r.get("project_id"))
                        && content.strip().equals(String.valueOf(r.get("content")).strip()))
            .findFirst();
    if (same.isPresent()) {
      var old = same.get();
      if (active && !"active".equals(old.get("status")))
        return change(
            String.valueOf(old.get("id")), "active", ((Number) old.get("version")).intValue());
      return old;
    }
    if (rows().stream().filter(r -> Set.of("active", "candidate").contains(r.get("status"))).count()
        >= 100) throw ApiException.bad("记忆与候选合计最多 100 条，请先整理。");
    String id = Database.id(), now = Database.now();
    db.jdbc.update(
        "INSERT INTO memories(id,project_id,title,content,status,source_task,source_message,source_quote,origin,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        id,
        project,
        title,
        content,
        active ? "active" : "candidate",
        task,
        message,
        quote,
        origin,
        now,
        now);
    return get(id);
  }

  @Transactional
  public synchronized Map<String, Object> change(String id, String status, int version) {
    if (!Set.of("active", "rejected", "deleted", "archived").contains(status))
      throw ApiException.bad("记忆状态不合法。");
    var old = get(id);
    if (((Number) old.get("version")).intValue() != version)
      throw ApiException.conflict("记忆已变化，请刷新。");
    if (status.equals("active")
        && rows().stream()
            .anyMatch(
                r ->
                    !id.equals(r.get("id"))
                        && "active".equals(r.get("status"))
                        && Objects.equals(old.get("project_id"), r.get("project_id"))
                        && Objects.equals(old.get("title"), r.get("title"))
                        && !Objects.equals(old.get("content"), r.get("content"))))
      throw ApiException.conflict("同一范围存在同名记忆，请先归档冲突条目再接受。");
    archive(old);
    db.jdbc.update(
        "UPDATE memories SET status=?,version=version+1,updated_at=? WHERE id=?",
        status,
        Database.now(),
        id);
    return get(id);
  }

  @Transactional
  public synchronized void pin(String id, boolean pinned) {
    archive(get(id));
    db.jdbc.update(
        "UPDATE memories SET pinned=?,version=version+1,updated_at=? WHERE id=?",
        pinned ? 1 : 0,
        Database.now(),
        id);
  }

  public Object history(String id) {
    get(id);
    return db.jdbc.queryForList(
        "SELECT document,created_at FROM memory_versions WHERE memory_id=? ORDER BY id DESC", id);
  }
}
