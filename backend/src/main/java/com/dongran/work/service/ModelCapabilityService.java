package com.dongran.work.service;

import com.dongran.work.infrastructure.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ModelCapabilityService {
  private final Database db;
  private final ModelProviderService providers;
  private final ModelClient model;

  public ModelCapabilityService(Database db, ModelProviderService providers, ModelClient model) {
    this.db = db;
    this.providers = providers;
    this.model = model;
  }

  public Map<String, Object> view(String id) {
    var p = providers.get(id);
    var rows =
        db.jdbc.queryForList(
            "SELECT document FROM model_capabilities WHERE provider_id=? AND revision=?",
            id,
            p.revision());
    return rows.isEmpty()
        ? Map.of("state", "unknown")
        : db.object(String.valueOf(rows.getFirst().get("document")));
  }

  public Map<String, Object> test(String id) throws Exception {
    var config = providers.configuration(id);
    var results = new LinkedHashMap<String, Object>();
    for (String capability : List.of("chat", "structured", "tools")) {
      try {
        String prompt =
            switch (capability) {
              case "chat" -> "Reply exactly OK.";
              case "structured" -> "Return only valid JSON: {\"ok\":true}";
              default -> "Call capability_probe with value ping. Do not answer in prose.";
            };
        List<Map<String, Object>> tools =
            capability.equals("tools")
                ? List.of(
                    Map.of(
                        "type",
                        "function",
                        "function",
                        Map.of(
                            "name",
                            "capability_probe",
                            "description",
                            "Harmless protocol probe; never executed",
                            "parameters",
                            Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("value", Map.of("type", "string")),
                                "required",
                                List.of("value")))))
                : List.of();
        var result =
            model.completeWithConfiguration(
                config, List.of(Map.of("role", "user", "content", prompt)), tools, text -> {});
        boolean ok =
            capability.equals("chat")
                ? !result.text().isBlank()
                : capability.equals("structured")
                    ? Boolean.TRUE.equals(db.object(result.text()).get("ok"))
                    : result.calls().stream()
                        .anyMatch(
                            call -> {
                              if (!(call.get("function") instanceof Map<?, ?> f)
                                  || !"capability_probe".equals(f.get("name"))) return false;
                              try {
                                return "ping"
                                    .equals(
                                        db.object(String.valueOf(f.get("arguments"))).get("value"));
                              } catch (Exception e) {
                                return false;
                              }
                            });
        results.put(capability, ok ? "passed" : "not_verified");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw e;
      } catch (Exception e) {
        results.put(capability, "not_verified");
      }
    }
    if (providers.get(id).revision() != config.revision())
      throw com.dongran.work.exception.ApiException.conflict("配置已改变，请重新检测。");
    results.put("state", "tested");
    results.put("testedAt", Database.now());
    db.jdbc.update(
        "INSERT INTO model_capabilities(provider_id,revision,document) VALUES(?,?,?) ON CONFLICT(provider_id) DO UPDATE SET revision=excluded.revision,document=excluded.document",
        id,
        config.revision(),
        db.json(results));
    return results;
  }
}
