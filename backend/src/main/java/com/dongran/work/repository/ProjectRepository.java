package com.dongran.work.repository;

import com.dongran.work.model.Project;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectRepository {
  private final JdbcTemplate jdbc;

  private static final RowMapper<Project> ROW =
      (rs, index) ->
          new Project(
              rs.getString("id"),
              rs.getString("name"),
              rs.getString("path"),
              rs.getString("createdAt"),
              rs.getString("openedAt"));

  public ProjectRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Project> findRecent() {
    return jdbc.query(
        "SELECT id,name,path,created_at AS createdAt,opened_at AS openedAt FROM projects ORDER BY opened_at DESC",
        ROW);
  }

  public Optional<Project> findById(String id) {
    return one(
        "SELECT id,name,path,created_at AS createdAt,opened_at AS openedAt FROM projects WHERE id=?",
        id);
  }

  public Optional<Project> findByPath(String path, boolean insensitive) {
    return one(
        insensitive
            ? "SELECT id,name,path,created_at AS createdAt,opened_at AS openedAt FROM projects WHERE lower(path)=lower(?)"
            : "SELECT id,name,path,created_at AS createdAt,opened_at AS openedAt FROM projects WHERE path=?",
        path);
  }

  public void insert(String id, String name, String path, String createdAt, String openedAt) {
    jdbc.update(
        "INSERT INTO projects(id,name,path,created_at,opened_at) VALUES (?,?,?,?,?)",
        id,
        name,
        path,
        createdAt,
        openedAt);
  }

  public void touch(String id, String openedAt) {
    jdbc.update("UPDATE projects SET opened_at=? WHERE id=?", openedAt, id);
  }

  private Optional<Project> one(String sql, Object arg) {
    var rows = jdbc.query(sql, ROW, arg);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }
}
