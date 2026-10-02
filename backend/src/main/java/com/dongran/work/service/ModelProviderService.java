package com.dongran.work.service;

import com.dongran.work.dto.ModelProviderRequest;
import com.dongran.work.exception.ApiException;
import com.dongran.work.infrastructure.CredentialService;
import com.dongran.work.infrastructure.Database;
import com.dongran.work.model.ModelProvider;
import com.dongran.work.repository.ModelProviderRepository;
import jakarta.annotation.PostConstruct;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelProviderService {
  private final ModelProviderRepository repository;
  private final com.dongran.work.infrastructure.Database db;
  private final CredentialService credentials;
  private final PreferenceService preferences;

  public ModelProviderService(
      ModelProviderRepository repository,
      com.dongran.work.infrastructure.Database db,
      CredentialService credentials,
      PreferenceService preferences) {
    this.repository = repository;
    this.db = db;
    this.credentials = credentials;
    this.preferences = preferences;
  }

  @PostConstruct
  void migrateLegacy() {
    if (repository.findAll().isEmpty() && !preferences.string("modelName", "").isBlank()) {
      var settings = preferences.all();
      var p =
          new ModelProvider(
              "legacy-model",
              "原有模型配置",
              "custom",
              preferences.string("baseUrl", "http://localhost:11434/v1"),
              preferences.string("modelName", ""),
              "从原有设置迁移",
              settings.get("temperature") instanceof Number n ? n.doubleValue() : 0.7,
              Database.number(settings, "contextWindow", 32768, 4096, 131072),
              "model",
              true,
              1,
              Database.now(),
              Database.now(),
              null,
              null,
              null);
      repository.insert(p);
    }
  }

  public ModelProvider get(String id) {
    return repository.find(id).orElseThrow(() -> ApiException.missing("供应商配置不存在。"));
  }

  public List<Map<String, Object>> list() {
    return repository.findAll().stream().map(this::view).toList();
  }

  public Map<String, Object> view(ModelProvider p) {
    var result = new LinkedHashMap<String, Object>();
    result.put("id", p.id());
    result.put("name", p.name());
    result.put("kind", p.kind());
    result.put("baseUrl", p.baseUrl());
    result.put("modelName", p.modelName());
    result.put("notes", p.notes());
    result.put("temperature", p.temperature());
    result.put("contextWindow", p.contextWindow());
    result.put("active", p.active());
    result.put("revision", p.revision());
    result.put("credentialConfigured", !credentials.get(p.credentialId()).isBlank());
    result.put("testedAt", p.testedAt());
    result.put("latencyMs", p.latencyMs());
    result.put("testError", p.testError());
    var capability =
        db.jdbc.queryForList(
            "SELECT document FROM model_capabilities WHERE provider_id=? AND revision=?",
            p.id(),
            p.revision());
    result.put(
        "capabilities",
        capability.isEmpty()
            ? Map.of("state", "unknown")
            : db.object(String.valueOf(capability.getFirst().get("document"))));
    return result;
  }

  @Transactional
  public synchronized Map<String, Object> save(String id, ModelProviderRequest body) {
    PreferenceService.validateUrl(body.baseUrl());
    if (body.name().isBlank() || body.modelName().isBlank() || body.baseUrl().indexOf('\0') >= 0)
      throw ApiException.bad("配置内容不合法。");
    if (body.apiKey() != null && (body.apiKey().contains("\r") || body.apiKey().contains("\n")))
      throw ApiException.bad("密钥不能包含换行。");
    if (body.contextWindow() % 1024 != 0) throw ApiException.bad("上下文上限应为 1024 的整数倍。");
    ModelProvider old = id == null ? null : get(id);
    if (old != null && (body.revision() == null || body.revision() != old.revision()))
      throw ApiException.conflict("配置已更新，请重新打开编辑窗口。");
    if (old == null && repository.findAll().size() >= 50) throw ApiException.bad("最多保存 50 套供应商配置。");
    String nextId = old == null ? Database.id() : id;
    var next =
        new ModelProvider(
            nextId,
            body.name().trim(),
            body.kind(),
            body.baseUrl().trim().replaceAll("/+$", ""),
            body.modelName().trim(),
            body.notes() == null ? "" : body.notes(),
            body.temperature(),
            body.contextWindow(),
            old == null ? "provider-" + nextId : old.credentialId(),
            old == null ? repository.active().isEmpty() : old.active(),
            old == null ? 1 : old.revision() + 1,
            old == null ? Database.now() : old.createdAt(),
            Database.now(),
            null,
            null,
            null);
    if (old == null) repository.insert(next);
    else if (repository.update(next, old.revision()) != 1)
      throw ApiException.conflict("配置已更新，请刷新。");
    String storage = "unchanged";
    if (Boolean.TRUE.equals(body.clearKey())) {
      if (!credentials.get(next.credentialId()).isBlank()) credentials.delete(next.credentialId());
      storage = "none";
    } else if (body.apiKey() != null && !body.apiKey().isBlank()) {
      var saved =
          credentials.save(
              next.credentialId(), body.apiKey(), !Boolean.FALSE.equals(body.rememberKey()));
      storage = String.valueOf(saved.get("storage"));
    }
    var result = view(get(nextId));
    result.put("credentialStorage", storage);
    return result;
  }

  @Transactional
  public synchronized Map<String, Object> activate(String id) {
    get(id);
    repository.activate(id);
    return view(get(id));
  }

  @Transactional
  public synchronized Map<String, Object> duplicate(String id) {
    ModelProvider source = get(id);
    return save(
        null,
        new ModelProviderRequest(
            source.name().substring(0, Math.min(source.name().length(), 76)) + " 副本",
            source.kind(),
            source.baseUrl(),
            source.modelName(),
            source.notes(),
            source.temperature(),
            source.contextWindow(),
            null,
            false,
            true,
            null));
  }

  @Transactional
  public synchronized void delete(String id) {
    var p = get(id);
    if (p.active()) throw ApiException.conflict("请先启用另一套配置，再删除当前供应商。");
    repository.delete(id);
    if (!credentials.get(p.credentialId()).isBlank()) credentials.delete(p.credentialId());
  }

  public record RuntimeConfiguration(
      String providerId,
      String name,
      String baseUrl,
      String modelName,
      String credentialId,
      double temperature,
      int contextWindow,
      long revision) {}

  public RuntimeConfiguration configuration(String id) {
    var selected = id == null ? repository.active().orElse(null) : get(id);
    if (selected != null)
      return new RuntimeConfiguration(
          selected.id(),
          selected.name(),
          selected.baseUrl(),
          selected.modelName(),
          selected.credentialId(),
          selected.temperature(),
          selected.contextWindow(),
          selected.revision());
    var settings = preferences.all();
    return new RuntimeConfiguration(
        "",
        "默认配置",
        Database.text(settings, "baseUrl", "http://localhost:11434/v1"),
        Database.text(settings, "modelName", ""),
        "model",
        settings.get("temperature") instanceof Number n ? n.doubleValue() : 0.7,
        Database.number(settings, "contextWindow", 32768, 4096, 131072),
        0);
  }

  public void tested(String id, long revision, long latency, String error) {
    repository.testResult(id, revision, Database.now(), latency, error);
  }
}
