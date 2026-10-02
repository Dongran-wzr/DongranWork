package com.dongran.work.controller;

import com.dongran.work.exception.ApiException;
import com.dongran.work.infrastructure.Database;
import com.dongran.work.service.PreferenceService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SettingsController {
  private final PreferenceService preferences;
  private final Database db;

  public SettingsController(PreferenceService preferences, Database db) {
    this.preferences = preferences;
    this.db = db;
  }

  @GetMapping("/settings")
  public Object settings() {
    return preferences.all();
  }

  @PatchMapping("/settings")
  public Object settings(@RequestBody Map<String, Object> body) {
    return preferences.patch(body);
  }

  @GetMapping("/settings/export")
  public Object export() {
    return Map.of("version", 1, "settings", preferences.all());
  }

  @PutMapping("/settings/import")
  public Object importSettings(@RequestBody Map<String, Object> body) {
    if (!(body.get("version") instanceof Number n)
        || n.intValue() != 1
        || !(body.get("settings") instanceof Map<?, ?> values)) throw ApiException.bad("配置格式不合法。");
    return preferences.patch(db.object(db.json(values)));
  }

  @GetMapping("/account")
  public Object account() {
    var result = new LinkedHashMap<String, Object>();
    preferences
        .all()
        .forEach(
            (k, v) -> {
              if (k.startsWith("account")) result.put(k, v);
            });
    return result;
  }

  @PatchMapping("/account")
  public Object account(@RequestBody Map<String, Object> body) {
    if (body.keySet().stream()
        .anyMatch(
            k ->
                !Set.of("accountNickname", "accountTitle", "accountBio", "accountAvatar")
                    .contains(k))) throw ApiException.bad("账户字段不合法。");
    preferences.patch(body);
    return account();
  }
}
