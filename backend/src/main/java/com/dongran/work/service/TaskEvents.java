package com.dongran.work.service;

import com.dongran.work.infrastructure.Database;
import com.dongran.work.repository.TaskEventRepository;
import com.dongran.work.repository.TaskRepository;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class TaskEvents {
  private final TaskEventRepository repository;
  private final Database db;
  private final TaskRepository tasks;

  public TaskEvents(TaskEventRepository repository, Database db, TaskRepository tasks) {
    this.repository = repository;
    this.db = db;
    this.tasks = tasks;
  }

  public void emit(String taskId, String type, Object payload) {
    repository.insert(taskId, type, db.json(payload), Database.now());
  }

  public List<Map<String, Object>> since(String taskId, long id) {
    return repository.findAfter(taskId, id).stream()
        .map(
            row -> {
              row.put("data", db.object(String.valueOf(row.get("data"))));
              return row;
            })
        .toList();
  }

  public void status(String taskId, String status, String error) {
    tasks.updateStatus(taskId, status, error, Database.now());
    emit(taskId, "status", Map.of("status", status, "error", error == null ? "" : error));
  }

  public void message(String taskId, String role, String agent, String content) {
    tasks.addMessage(taskId, role, agent, content, Database.now());
    emit(taskId, "message", Map.of("role", role, "agent", agent, "content", content));
  }
}
