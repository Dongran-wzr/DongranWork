package com.dongran.work.repository;

import com.dongran.work.model.ModelProvider;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ModelProviderRepository {
  private final JdbcTemplate jdbc;

  public ModelProviderRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<ModelProvider> ROW =
      (rs, n) ->
          new ModelProvider(
              rs.getString("id"),
              rs.getString("name"),
              rs.getString("kind"),
              rs.getString("base_url"),
              rs.getString("model_name"),
              rs.getString("notes"),
              rs.getDouble("temperature"),
              rs.getInt("context_window"),
              rs.getString("credential_id"),
              rs.getBoolean("active"),
              rs.getLong("revision"),
              rs.getString("created_at"),
              rs.getString("updated_at"),
              rs.getString("tested_at"),
              rs.getObject("latency_ms") == null ? null : rs.getLong("latency_ms"),
              rs.getString("test_error"));

  public List<ModelProvider> findAll() {
    return jdbc.query("SELECT * FROM model_providers ORDER BY active DESC,created_at", ROW);
  }

  public Optional<ModelProvider> find(String id) {
    return jdbc.query("SELECT * FROM model_providers WHERE id=?", ROW, id).stream().findFirst();
  }

  public Optional<ModelProvider> active() {
    return jdbc.query("SELECT * FROM model_providers WHERE active=1", ROW).stream().findFirst();
  }

  public void insert(ModelProvider p) {
    jdbc.update(
        "INSERT INTO model_providers(id,name,kind,base_url,model_name,notes,temperature,context_window,credential_id,active,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        p.id(),
        p.name(),
        p.kind(),
        p.baseUrl(),
        p.modelName(),
        p.notes(),
        p.temperature(),
        p.contextWindow(),
        p.credentialId(),
        p.active(),
        p.createdAt(),
        p.updatedAt());
  }

  public int update(ModelProvider p, long expected) {
    return jdbc.update(
        "UPDATE model_providers SET name=?,kind=?,base_url=?,model_name=?,notes=?,temperature=?,context_window=?,revision=revision+1,updated_at=?,tested_at=NULL,latency_ms=NULL,test_error=NULL WHERE id=? AND revision=?",
        p.name(),
        p.kind(),
        p.baseUrl(),
        p.modelName(),
        p.notes(),
        p.temperature(),
        p.contextWindow(),
        p.updatedAt(),
        p.id(),
        expected);
  }

  public void activate(String id) {
    jdbc.update("UPDATE model_providers SET active=0 WHERE active=1");
    jdbc.update("UPDATE model_providers SET active=1 WHERE id=?", id);
  }

  public void delete(String id) {
    jdbc.update("DELETE FROM model_providers WHERE id=?", id);
  }

  public void testResult(String id, long revision, String at, long latency, String error) {
    jdbc.update(
        "UPDATE model_providers SET tested_at=?,latency_ms=?,test_error=? WHERE id=? AND revision=?",
        at,
        latency,
        error,
        id,
        revision);
  }
}
