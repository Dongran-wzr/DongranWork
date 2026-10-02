package com.dongran.work.service;

import com.dongran.work.exception.ApiException;
import com.dongran.work.infrastructure.Database;
import com.dongran.work.repository.PreferenceRepository;
import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PreferenceService {
  private final SettingsValidator validator;
  private final Database db;
  private final PreferenceRepository repository;

  public PreferenceService(
      Database db, PreferenceRepository repository, SettingsValidator validator) {
    this.validator = validator;
    this.db = db;
    this.repository = repository;
  }

  public Map<String, Object> all() {
    return repository.findAll();
  }

  public String string(String key, String fallback) {
    return Database.text(all(), key, fallback);
  }

  public boolean bool(String key, boolean fallback) {
    return Database.bool(all(), key, fallback);
  }

  @Transactional
  public Map<String, Object> patch(Map<String, Object> values) {
    if (db.json(values).length() > 1_500_000) throw ApiException.bad("配置过大。");
    for (var entry : values.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      if (!key.matches("[a-zA-Z][a-zA-Z0-9]{0,79}")
          || key.toLowerCase(Locale.ROOT).matches(".*(secret|password|apikey|token).*"))
        throw ApiException.bad("不支持的配置字段。");
      validator.validate(key, value);
      if (key.equals("accountNickname")
          && (!(value instanceof String s) || s.isBlank() || s.length() > 32))
        throw ApiException.bad("昵称不能为空且不能超过 32 个字符。");
      if (key.equals("accountAvatar")) validateAvatar(value);
      if (key.equals("baseUrl")) validateUrl(String.valueOf(value));
      if (key.equals("parallelAgents")) Database.number(values, key, 3, 1, 4);
      if (key.equals("automationHooks")) validateHooks(value);
      repository.upsert(key, db.json(value));
    }
    return all();
  }

  private void validateAvatar(Object value) {
    if (!(value instanceof String text) || text.length() > 400000)
      throw ApiException.bad("头像格式不合法。");
    if (text.isEmpty()) return;
    if (!text.startsWith("data:image/png;base64,")) throw ApiException.bad("头像必须为 PNG。");
    try {
      byte[] bytes = Base64.getDecoder().decode(text.substring(22));
      var buffer = java.nio.ByteBuffer.wrap(bytes);
      if (bytes.length < 80
          || buffer.getLong() != 0x89504e470d0a1a0aL
          || buffer.getInt(16) != 256
          || buffer.getInt(20) != 256) throw new IllegalArgumentException();
    } catch (Exception e) {
      throw ApiException.bad("头像必须是 256 x 256 PNG。");
    }
  }

  public static URI validateUrl(String value) {
    try {
      URI uri = URI.create(value);
      if (!Set.of("http", "https").contains(uri.getScheme())
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null) throw new IllegalArgumentException();
      if (uri.getScheme().equals("http")
          && !Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost()))
        throw ApiException.bad("远程服务地址必须使用 HTTPS。");
      return uri;
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw ApiException.bad("服务地址不合法。");
    }
  }

  private void validateHooks(Object value) {
    if (!(value instanceof List<?> list) || list.size() > 100) throw ApiException.bad("钩子数量超过限制。");
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)
          || !(map.get("command") instanceof String command)
          || command.isBlank()
          || command.length() > 4000
          || !Set.of("before-task", "before-tool", "after-task").contains(map.get("event")))
        throw ApiException.bad("钩子配置不合法。");
    }
  }

  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> collection(String key) {
    Object value = all().get(key);
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList();
  }

  public List<Map<String, Object>> memories(String projectId) {
    if (!bool("memoryEnabled", true)) return List.of();
    var rule =
        collection("memoryProjectPreferences").stream()
            .filter(p -> Objects.equals(p.get("projectId"), projectId))
            .findFirst()
            .orElse(Map.of());
    return collection("memoryEntries").stream()
        .filter(
            m ->
                "global".equals(m.get("scope"))
                    ? Database.bool(rule, "inheritGlobal", true)
                    : projectId != null
                        && Objects.equals(projectId, m.get("projectId"))
                        && Database.bool(rule, "enabled", true))
        .toList();
  }
}
