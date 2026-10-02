package com.dongran.work.service;

import com.dongran.work.exception.ApiException;
import com.dongran.work.infrastructure.Database;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ContextDraftService {
  private final Database db;

  public ContextDraftService(Database db) {
    this.db = db;
  }

  public Object begin(String task, String path, String expected) {
    String id = Database.id();
    db.jdbc.update(
        "INSERT INTO context_drafts(id,task_id,path,expected_hash,updated_at) VALUES(?,?,?,?,?)",
        id,
        task,
        path,
        expected,
        Database.now());
    return Map.of("draftId", id, "nextChunk", 0);
  }

  public synchronized Object append(String task, String id, int sequence, String text) {
    var row = get(task, id);
    int next = ((Number) row.get("next_chunk")).intValue();
    if (!"open".equals(row.get("status")) || sequence != next)
      throw ApiException.conflict("草稿已结束或片段顺序不正确。");
    if (text.length() > 12000
        || String.valueOf(row.get("content")).length() + text.length() > 200000)
      throw ApiException.bad("草稿片段或文件过大。");
    db.jdbc.update(
        "UPDATE context_drafts SET content=content||?,next_chunk=next_chunk+1,updated_at=? WHERE id=?",
        text,
        Database.now(),
        id);
    return Map.of("draftId", id, "nextChunk", next + 1);
  }

  public Map<String, Object> get(String task, String id) {
    var rows =
        db.jdbc.queryForList("SELECT * FROM context_drafts WHERE id=? AND task_id=?", id, task);
    if (rows.isEmpty()) throw ApiException.missing("草稿不存在。");
    return rows.getFirst();
  }

  public void committed(String id) {
    db.jdbc.update(
        "UPDATE context_drafts SET status='committed',updated_at=? WHERE id=?", Database.now(), id);
  }
}
