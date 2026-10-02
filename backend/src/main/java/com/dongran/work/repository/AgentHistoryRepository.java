package com.dongran.work.repository;

import com.dongran.work.infrastructure.Database;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentHistoryRepository {
  private final Database db;
  private final JdbcTemplate jdbc;

  public AgentHistoryRepository(Database db) {
    this.db = db;
    this.jdbc = db.jdbc;
  }

  public List<Map<String, Object>> recentMessages(String taskId) {
    return jdbc.queryForList(
        "SELECT role,content FROM messages WHERE task_id=? AND role IN ('user','assistant') ORDER BY id DESC LIMIT 40",
        taskId);
  }
}
