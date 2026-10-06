package com.dongran.work.service;

import com.dongran.work.exception.ApiException;
import com.dongran.work.infrastructure.Database;
import java.util.*;
import org.springframework.stereotype.Component;

/** Server-side settings contract. JSON collections are validated before persistence. */
@Component
public class SettingsValidator {
  private static final Set<String> BOOLEANS =
      Set.of(
          "memoryEnabled",
          "memoryAutoCapture",
          "contextAutoCompact",
          "petEnabled",
          "petDnd",
          "showToolCount",
          "expandTools",
          "showSuggestions",
          "completionNotice",
          "showActivity",
          "reduceMotion",
          "autoDelegate",
          "skillEvolutionEnabled",
          "confirmPlan",
          "enableProduct",
          "enableDeveloper",
          "enableTester",
          "commandApproval",
          "allowNetwork",
          "rememberHistory",
          "gitAutoFetch",
          "gitConfirmCommit");
  private static final Map<String, Integer> TEXTS =
      Map.ofEntries(
          Map.entry("accountNickname", 32),
          Map.entry("accountTitle", 80),
          Map.entry("accountBio", 300),
          Map.entry("accountAvatar", 400000),
          Map.entry("themePreset", 256),
          Map.entry("customAccent", 7),
          Map.entry("themeName", 32),
          Map.entry("activeThemeId", 80),
          Map.entry("petName", 16),
          Map.entry("petStyle", 256),
          Map.entry("petActivity", 256),
          Map.entry("petPosition", 256),
          Map.entry("sendKey", 256),
          Map.entry("artifactPanel", 256),
          Map.entry("theme", 256),
          Map.entry("density", 256),
          Map.entry("defaultModel", 256),
          Map.entry("provider", 256),
          Map.entry("webSearchProvider", 20),
          Map.entry("baseUrl", 2048),
          Map.entry("modelName", 200),
          Map.entry("projectInstructions", 12000),
          Map.entry("permissionMode", 256),
          Map.entry("excludedPaths", 12000),
          Map.entry("shell", 256),
          Map.entry("settingsKey", 256),
          Map.entry("terminalKey", 256),
          Map.entry("searchKey", 256),
          Map.entry("gitDefaultBranch", 120),
          Map.entry("gitBranchPrefix", 80),
          Map.entry("gitCommitStyle", 256),
          Map.entry("worktreeMode", 256),
          Map.entry("worktreeDirectory", 500),
          Map.entry("worktreeSetup", 4000),
          Map.entry("worktreeCleanup", 256));
  private static final Set<String> COLLECTIONS =
      Set.of(
          "memoryEntries",
          "memoryProjectPreferences",
          "savedThemes",
          "petCustomPosition",
          "installedExtensions",
          "automationHooks",
          "serviceConnections");
  private static final Map<String, double[]> NUMBERS =
      Map.ofEntries(
          Map.entry("petSize", new double[] {56.0, 128.0, 8.0}),
          Map.entry("petOpacity", new double[] {40.0, 100.0, 5.0}),
          Map.entry("fontSize", new double[] {12.0, 18.0, 1.0}),
          Map.entry("sidebarWidth", new double[] {240.0, 340.0, 1.0}),
          Map.entry("temperature", new double[] {0.0, 2.0, 0.1}),
          Map.entry("contextWindow", new double[] {4096.0, 131072.0, 1024.0}),
          Map.entry("parallelAgents", new double[] {1.0, 4.0, 1.0}),
          Map.entry("contextOutputReserve", new double[] {512.0, 16384.0, 1.0}),
          Map.entry("contextRetryLimit", new double[] {0.0, 3.0, 1.0}),
          Map.entry("contextMemoryLimit", new double[] {1.0, 20.0, 1.0}),
          Map.entry("terminalFontSize", new double[] {11.0, 18.0, 1.0}),
          Map.entry("terminalHeight", new double[] {180.0, 360.0, 10.0}));

  public void validate(String key, Object value) {
    if (BOOLEANS.contains(key)) {
      if (!(value instanceof Boolean)) bad(key);
      return;
    }
    if (TEXTS.containsKey(key)) {
      if (!(value instanceof String s) || s.length() > TEXTS.get(key) || s.indexOf('\0') >= 0)
        bad(key);
      return;
    }
    if (NUMBERS.containsKey(key)) {
      if (!(value instanceof Number)) bad(key);
      double n = ((Number) value).doubleValue();
      double[] range = NUMBERS.get(key);
      if (!Double.isFinite(n)
          || n < range[0]
          || n > range[1]
          || Math.abs((n - range[0]) / range[2] - Math.rint((n - range[0]) / range[2])) > 0.000001)
        bad(key);
      return;
    }
    if (!COLLECTIONS.contains(key)) bad(key);
    if (key.equals("petCustomPosition")) {
      if (value == null) return;
      if (!(value instanceof Map<?, ?> p) || p.size() != 2) bad(key);
      for (String axis : List.of("x", "y")) {
        Object v = ((Map<?, ?>) value).get(axis);
        if (!(v instanceof Number n)
            || !Double.isFinite(n.doubleValue())
            || n.doubleValue() < 0
            || n.doubleValue() > 1) bad(key);
      }
      return;
    }
    if (!(value instanceof List<?> list)
        || list.size() > (key.equals("memoryProjectPreferences") ? 200 : 100)) bad(key);
    Set<Object> ids = new HashSet<>();
    for (Object item : (List<?>) value) {
      if (!(item instanceof Map<?, ?>)) bad(key);
      @SuppressWarnings("unchecked")
      Map<String, Object> row = (Map<String, Object>) item;
      String idKey = key.equals("memoryProjectPreferences") ? "projectId" : "id";
      String id = Database.required(row, idKey, 100);
      if (!ids.add(id)) bad(key);
      switch (key) {
        case "installedExtensions" -> {
          if (!Set.of("product-docs", "code-review", "browser-check").contains(id)
              || !(row.get("enabled") instanceof Boolean)) bad(key);
        }
        case "automationHooks" -> {
          Database.required(row, "name", 100);
          Database.required(row, "command", 4000);
          Database.number(row, "timeout", 30, 1, 300);
          if (!(row.get("enabled") instanceof Boolean)
              || !Set.of("before-task", "before-tool", "after-task")
                  .contains(String.valueOf(row.get("event")))
              || !Set.of("stop", "continue").contains(String.valueOf(row.get("failurePolicy"))))
            bad(key);
        }
        case "serviceConnections" -> {
          Database.required(row, "name", 100);
          PreferenceService.validateUrl(Database.required(row, "url", 2048));
          if (!(row.get("enabled") instanceof Boolean)
              || !Set.of("mcp-http", "mcp-sse", "github").contains(String.valueOf(row.get("type"))))
            bad(key);
        }
        case "memoryEntries" -> {
          Database.required(row, "title", 80);
          Database.required(row, "content", 2000);
          String scope = Database.required(row, "scope", 10);
          if (!Set.of("global", "project").contains(scope)) bad(key);
          if (scope.equals("project")) {
            Database.required(row, "projectId", 100);
            Database.required(row, "projectName", 200);
          } else if (row.get("projectId") != null) bad(key);
          timestamp(row, "createdAt");
          timestamp(row, "updatedAt");
        }
        case "memoryProjectPreferences" -> {
          if (!(row.get("enabled") instanceof Boolean)
              || !(row.get("inheritGlobal") instanceof Boolean)) bad(key);
        }
        case "savedThemes" -> {
          if (((List<?>) value).size() > 20) bad(key);
          Database.required(row, "name", 32);
          if (!Set.of("light", "dark", "system").contains(String.valueOf(row.get("mode")))
              || !Database.required(row, "color", 7).matches("#[a-fA-F0-9]{6}")) bad(key);
        }
        default -> bad(key);
      }
    }
  }

  private void timestamp(Map<String, Object> row, String key) {
    try {
      java.time.Instant.parse(Database.required(row, key, 32));
    } catch (Exception e) {
      bad(key);
    }
  }

  private void bad(String key) {
    throw ApiException.bad("设置字段不合法：" + key);
  }
}
