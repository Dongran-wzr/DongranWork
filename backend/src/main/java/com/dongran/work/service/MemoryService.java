package com.dongran.work.service;

import com.dongran.work.exception.ApiException;
import com.dongran.work.infrastructure.Database;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryService {
  private final PreferenceService preferences;
  private final ProjectService projects;

  public MemoryService(PreferenceService preferences, ProjectService projects) {
    this.preferences = preferences;
    this.projects = projects;
  }

  public List<Map<String, Object>> list(String projectId, boolean effective) {
    return effective
        ? preferences.memories(projectId)
        : preferences.collection("memoryEntries").stream()
            .filter(e -> Objects.equals(projectId, e.get("projectId")))
            .toList();
  }

  @Transactional
  public Map<String, Object> save(String id, Map<String, Object> body) {
    String title = Database.required(body, "title", 80),
        content = Database.required(body, "content", 2000);
    String projectId = Database.text(body, "projectId", null);
    var entries = new ArrayList<>(preferences.collection("memoryEntries"));
    Map<String, Object> old = null;
    if (id != null) {
      String target = id;
      old =
          entries.stream()
              .filter(e -> target.equals(e.get("id")))
              .findFirst()
              .orElseThrow(() -> ApiException.missing("记忆不存在。"));
      entries.remove(old);
    } else id = Database.id();
    if (entries.size() >= 100) throw ApiException.bad("记忆最多 100 条。");
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("id", id);
    entry.put("title", title);
    entry.put("content", content);
    entry.put("scope", projectId == null ? "global" : "project");
    entry.put("projectId", projectId);
    entry.put("projectName", projectId == null ? "" : projects.get(projectId).name());
    entry.put("createdAt", old == null ? Database.now() : old.get("createdAt"));
    entry.put("updatedAt", Database.now());
    entries.add(entry);
    preferences.patch(Map.of("memoryEntries", entries));
    return entry;
  }

  @Transactional
  public void delete(String id) {
    var entries = new ArrayList<>(preferences.collection("memoryEntries"));
    if (!entries.removeIf(e -> id.equals(e.get("id")))) throw ApiException.missing("记忆不存在。");
    preferences.patch(Map.of("memoryEntries", entries));
  }
}
