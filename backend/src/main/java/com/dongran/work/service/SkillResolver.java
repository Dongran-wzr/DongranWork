package com.dongran.work.service;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SkillResolver {
  private final SkillService skills;

  public SkillResolver(SkillService skills) {
    this.skills = skills;
  }

  public List<Map<String, Object>> candidates(String project, String prompt) {
    var query = new HashSet<>(KnowledgeRetrievalService.tokens(prompt.toLowerCase(Locale.ROOT)));
    return skills.effective(project).stream()
        .filter(r -> ((Number) r.get("auto_match")).intValue() == 1)
        .map(
            r -> {
              var m = new LinkedHashMap<>(r);
              var terms =
                  new HashSet<>(
                      KnowledgeRetrievalService.tokens(r.get("name") + " " + r.get("description")));
              terms.retainAll(query);
              int score = terms.size();
              if (prompt
                  .toLowerCase(Locale.ROOT)
                  .contains(String.valueOf(r.get("name")).toLowerCase(Locale.ROOT))) score += 10;
              m.put("score", score);
              return m;
            })
        .filter(r -> ((Number) r.get("score")).intValue() >= 2)
        .sorted(
            Comparator.comparingInt((Map<String, Object> r) -> ((Number) r.get("score")).intValue())
                .reversed())
        .limit(5)
        .map(
            r ->
                Map.of(
                    "id",
                    r.get("id"),
                    "name",
                    r.get("name"),
                    "description",
                    r.get("description"),
                    "version",
                    r.get("version")))
        .toList();
  }
}
