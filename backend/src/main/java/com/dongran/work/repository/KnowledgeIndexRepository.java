package com.dongran.work.repository;

import com.dongran.work.infrastructure.Database;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeIndexRepository {
  private final Database db;

  public KnowledgeIndexRepository(Database db) {
    this.db = db;
  }

  public Map<String, Object> settings() {
    var rows =
        db.jdbc.queryForList(
            "SELECT document,revision FROM knowledge_retrieval_settings WHERE id=1");
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public int saveSettings(String document, long revision) {
    if (revision == 0)
      return db.jdbc.update(
          "INSERT OR IGNORE INTO knowledge_retrieval_settings(id,document,revision) VALUES (1,?,1)",
          document);
    return db.jdbc.update(
        "UPDATE knowledge_retrieval_settings SET document=?,revision=revision+1 WHERE id=1 AND revision=?",
        document,
        revision);
  }

  public List<Map<String, Object>> unindexed() {
    return db.jdbc.queryForList(
        "SELECT id,name,content FROM knowledge k WHERE length(content)>0 AND NOT EXISTS(SELECT 1 FROM knowledge_chunks c WHERE c.document_id=k.id)");
  }

  public List<Map<String, Object>> chunks(String id) {
    return db.jdbc.queryForList(
        "SELECT id,ordinal,content,vector_signature AS signature FROM knowledge_chunks WHERE document_id=? ORDER BY ordinal",
        id);
  }

  public void deleteChunks(String id) {
    db.jdbc.update(
        "DELETE FROM knowledge_chunks_fts WHERE chunk_id IN (SELECT id FROM knowledge_chunks WHERE document_id=?)",
        id);
    db.jdbc.update("DELETE FROM knowledge_chunks WHERE document_id=?", id);
  }

  public void addChunk(
      String id, String doc, int ordinal, String content, String nameTokens, String tokens) {
    db.jdbc.update(
        "INSERT INTO knowledge_chunks(id,document_id,ordinal,content) VALUES(?,?,?,?)",
        id,
        doc,
        ordinal,
        content);
    db.jdbc.update(
        "INSERT INTO knowledge_chunks_fts(chunk_id,name,content) VALUES(?,?,?)",
        id,
        nameTokens,
        tokens);
  }

  public int vector(String id, float[] vector, String signature) {
    var buffer =
        java.nio.ByteBuffer.allocate(vector.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    for (float v : vector) buffer.putFloat(v);
    return db.jdbc.update(
        "UPDATE knowledge_chunks SET vector_blob=?,vector_dimension=?,vector_signature=? WHERE id=?",
        buffer.array(),
        vector.length,
        signature,
        id);
  }

  public boolean hasVectors(String project, String signature) {
    return db.jdbc.queryForObject(
            "SELECT count(*) FROM knowledge_chunks c JOIN knowledge k ON k.id=c.document_id WHERE NOT EXISTS(SELECT 1 FROM knowledge_chunks pending WHERE pending.document_id=c.document_id AND (pending.vector_blob IS NULL OR pending.vector_signature IS NULL OR pending.vector_signature!=c.vector_signature OR pending.vector_dimension!=c.vector_dimension)) AND c.vector_blob IS NOT NULL AND c.vector_signature=? AND (k.project_id IS NULL OR k.project_id=?)",
            Integer.class,
            signature,
            project)
        > 0;
  }

  public List<Map<String, Object>> lexical(String project, String query, int limit) {
    return db.jdbc.queryForList(
        "SELECT c.id AS chunkId,k.id,k.name,k.project_id AS projectId,c.ordinal,c.content AS excerpt,bm25(knowledge_chunks_fts,0,3,1) AS bm25 FROM knowledge_chunks_fts JOIN knowledge_chunks c ON c.id=knowledge_chunks_fts.chunk_id JOIN knowledge k ON k.id=c.document_id WHERE knowledge_chunks_fts MATCH ? AND (k.project_id IS NULL OR k.project_id=?) ORDER BY bm25 LIMIT ?",
        query,
        project,
        limit);
  }

  public List<Map<String, Object>> nearest(
      String project, String signature, float[] query, int limit) {
    var best =
        new PriorityQueue<Map<String, Object>>(
            Comparator.comparingDouble(r -> ((Number) r.get("score")).doubleValue()));
    db.jdbc.query(
        "SELECT c.id AS chunkId,k.id,k.name,k.project_id AS projectId,c.ordinal,c.content AS excerpt,c.vector_blob FROM knowledge_chunks c JOIN knowledge k ON k.id=c.document_id WHERE NOT EXISTS(SELECT 1 FROM knowledge_chunks pending WHERE pending.document_id=c.document_id AND (pending.vector_blob IS NULL OR pending.vector_signature IS NULL OR pending.vector_signature!=c.vector_signature OR pending.vector_dimension!=c.vector_dimension)) AND c.vector_blob IS NOT NULL AND c.vector_signature=? AND c.vector_dimension=? AND (k.project_id IS NULL OR k.project_id=?)",
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs -> {
              var buffer =
                  java.nio.ByteBuffer.wrap(rs.getBytes("vector_blob"))
                      .order(java.nio.ByteOrder.LITTLE_ENDIAN);
              double score = 0;
              for (float v : query) score += v * buffer.getFloat();
              var row = new LinkedHashMap<String, Object>();
              for (String key : List.of("chunkId", "id", "name", "projectId", "ordinal", "excerpt"))
                row.put(key, rs.getObject(key));
              row.put("score", score);
              best.add(row);
              if (best.size() > limit) best.poll();
            },
        signature,
        query.length,
        project);
    var result = new ArrayList<>(best);
    result.sort(
        Comparator.comparingDouble(
                (Map<String, Object> r) -> ((Number) r.get("score")).doubleValue())
            .reversed());
    return result;
  }

  public Map<String, Object> status(String id, String signature) {
    return db.one(
        "SELECT count(*) AS chunks,coalesce(sum(CASE WHEN vector_signature=? THEN 1 ELSE 0 END),0) AS embedded FROM knowledge_chunks WHERE document_id=?",
        signature,
        id);
  }
}
