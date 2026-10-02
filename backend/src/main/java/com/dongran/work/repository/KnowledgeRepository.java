package com.dongran.work.repository;

import com.dongran.work.infrastructure.Database;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeRepository {
  private final Database db;
  private final JdbcTemplate jdbc;

  public KnowledgeRepository(Database db) {
    this.db = db;
    this.jdbc = db.jdbc;
  }

  public List<Map<String, Object>> findVisible(String projectId) {
    return jdbc.queryForList(
        "SELECT id,project_id AS projectId,name,source_name AS sourceName,source_mime AS sourceMime,extraction_status AS extractionStatus,extraction_warning AS extractionWarning,length(content) AS characters,created_at AS createdAt,updated_at AS updatedAt FROM knowledge WHERE project_id IS NULL OR project_id=? ORDER BY updated_at DESC LIMIT 200",
        projectId);
  }

  public Map<String, Object> findRequired(String id) {
    return db.one(
        "SELECT id,project_id AS projectId,name,content,checksum,source_name AS sourceName,source_mime AS sourceMime,extraction_status AS extractionStatus,extraction_warning AS extractionWarning,created_at AS createdAt,updated_at AS updatedAt FROM knowledge WHERE id=?",
        id);
  }

  public void source(String id, String name, String mime, byte[] bytes) {
    jdbc.update(
        "UPDATE knowledge SET source_name=?,source_mime=?,source_bytes=? WHERE id=?",
        name,
        mime,
        bytes,
        id);
  }

  public void extraction(String id, String status, String warning) {
    jdbc.update(
        "UPDATE knowledge SET extraction_status=?,extraction_warning=? WHERE id=?",
        status,
        warning,
        id);
  }

  public byte[] sourceBytes(String id) {
    return jdbc.queryForObject("SELECT source_bytes FROM knowledge WHERE id=?", byte[].class, id);
  }

  public int insert(
      String id,
      String projectId,
      String name,
      String content,
      String checksum,
      String createdAt,
      String updatedAt) {
    return jdbc.update(
        "INSERT INTO knowledge(id,project_id,name,content,checksum,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
        id,
        projectId,
        name,
        content,
        checksum,
        createdAt,
        updatedAt);
  }

  public int update(String name, String content, String checksum, String updatedAt, String id) {
    return jdbc.update(
        "UPDATE knowledge SET name=?,content=?,checksum=?,updated_at=? WHERE id=?",
        name,
        content,
        checksum,
        updatedAt,
        id);
  }

  public int deleteIndex(String id) {
    return jdbc.update("DELETE FROM knowledge_fts WHERE id=?", id);
  }

  public int insertIndex(String id, String name, String content) {
    return jdbc.update(
        "INSERT INTO knowledge_fts(id,name,content) VALUES (?,?,?)", id, name, content);
  }

  public int delete(String id) {
    return jdbc.update("DELETE FROM knowledge WHERE id=?", id);
  }

  public List<Map<String, Object>> searchShortTerm(
      String projectId, String nameTerm, String contentTerm) {
    return jdbc.queryForList(
        "SELECT id,name,substr(content,1,600) AS excerpt,project_id AS projectId FROM knowledge WHERE (project_id IS NULL OR project_id=?) AND (instr(lower(name),lower(?))>0 OR instr(lower(content),lower(?))>0) LIMIT 20",
        projectId,
        nameTerm,
        contentTerm);
  }

  public List<Map<String, Object>> searchFullText(String phrase, String projectId) {
    return jdbc.queryForList(
        "SELECT k.id,k.name,k.project_id AS projectId,snippet(knowledge_fts,2,'','',' … ',40) AS excerpt FROM knowledge_fts JOIN knowledge k ON k.id=knowledge_fts.id WHERE knowledge_fts MATCH ? AND (k.project_id IS NULL OR k.project_id=?) ORDER BY rank LIMIT 20",
        phrase,
        projectId);
  }
}
