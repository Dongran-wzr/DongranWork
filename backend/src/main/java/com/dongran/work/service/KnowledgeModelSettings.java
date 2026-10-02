package com.dongran.work.service;

import com.dongran.work.dto.KnowledgeRetrievalRequest;
import com.dongran.work.exception.ApiException;
import com.dongran.work.infrastructure.*;
import com.dongran.work.repository.KnowledgeIndexRepository;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeModelSettings {
  public record Endpoint(
      boolean enabled, String baseUrl, String model, String providerId, String credentialId) {}

  public record Configuration(
      long revision, String mode, int candidates, int topK, Endpoint embedding, Endpoint rerank) {}

  private final Database db;
  private final KnowledgeIndexRepository repository;
  private final ModelProviderService providers;
  private final CredentialService credentials;

  public KnowledgeModelSettings(
      Database db,
      KnowledgeIndexRepository repository,
      ModelProviderService providers,
      CredentialService credentials) {
    this.db = db;
    this.repository = repository;
    this.providers = providers;
    this.credentials = credentials;
  }

  public Configuration get() {
    var row = repository.settings();
    if (row == null)
      return new Configuration(
          0,
          "bm25",
          30,
          8,
          new Endpoint(false, "", "", "", "kb-embedding"),
          new Endpoint(false, "", "", "", "kb-rerank"));
    try {
      return db.mapper.readValue(String.valueOf(row.get("document")), Configuration.class);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid retrieval configuration", e);
    }
  }

  public Map<String, Object> view() {
    Configuration c = get();
    return Map.of(
        "revision",
        c.revision(),
        "mode",
        c.mode(),
        "candidates",
        c.candidates(),
        "topK",
        c.topK(),
        "embedding",
        view(c.embedding()),
        "rerank",
        view(c.rerank()),
        "cloudRequestsAvailable",
        true);
  }

  private Map<String, Object> view(Endpoint e) {
    return Map.of(
        "enabled",
        e.enabled(),
        "baseUrl",
        e.baseUrl(),
        "model",
        e.model(),
        "providerId",
        e.providerId(),
        "credentialConfigured",
        !credentials.get(e.credentialId()).isBlank());
  }

  @Transactional
  public synchronized Map<String, Object> save(KnowledgeRetrievalRequest request) {
    var old = get();
    if (request.revision() != old.revision()) throw ApiException.conflict("检索配置已改变，请重新打开设置。");
    if (request.topK() > request.candidates()) throw ApiException.bad("返回条数不能大于候选条数。");
    if (!Set.of("bm25", "hybrid", "vector_bm25").contains(request.mode()))
      throw ApiException.bad("检索方式不合法。");
    Endpoint embedding = endpoint(request.embedding(), "embedding"),
        rerank = endpoint(request.rerank(), "rerank");
    if (!request.mode().equals("bm25") && !embedding.enabled())
      throw ApiException.bad("向量检索需要先启用 Embedding。");
    var next =
        new Configuration(
            old.revision() + 1,
            request.mode(),
            request.candidates(),
            request.topK(),
            embedding,
            rerank);
    if (repository.saveSettings(db.json(next), old.revision()) != 1)
      throw ApiException.conflict("检索配置已改变，请刷新。");
    var storage = new LinkedHashMap<String, String>();
    storage.put("embedding", key(request.embedding(), embedding));
    storage.put("rerank", key(request.rerank(), rerank));
    var result = new LinkedHashMap<>(view());
    result.put("credentialStorage", storage);
    return result;
  }

  public Map<String, Object> test(
      String kind, KnowledgeRetrievalRequest.Endpoint request, KnowledgeModelClient client) {
    if (!Set.of("embedding", "rerank").contains(kind)) throw ApiException.bad("模型类型不合法。");
    // A one-shot probe validates the draft without enabling or saving it.
    var probe =
        new KnowledgeRetrievalRequest.Endpoint(
            true,
            request.consentTarget(),
            request.baseUrl(),
            request.model(),
            request.providerId(),
            request.apiKey(),
            false,
            request.clearKey());
    var target = endpoint(probe, kind);
    String key =
        probe.clearKey()
            ? ""
            : probe.apiKey() != null && !probe.apiKey().isBlank()
                ? probe.apiKey()
                : credentials.get(target.credentialId());
    return client.test(target, kind, key);
  }

  private Endpoint endpoint(KnowledgeRetrievalRequest.Endpoint r, String kind) {
    String base = r.baseUrl() == null ? "" : r.baseUrl().strip().replaceAll("/+$", ""),
        model = r.model() == null ? "" : r.model().strip(),
        provider = r.providerId() == null ? "" : r.providerId().strip();
    String credential = "kb-" + kind;
    if (!provider.isEmpty()) {
      var p = providers.get(provider);
      credential = p.credentialId();
      if (base.isEmpty()) base = p.baseUrl();
    }
    if (!base.isEmpty()) PreferenceService.validateUrl(base);
    if (r.enabled() && (base.isEmpty() || model.isEmpty()))
      throw ApiException.bad(kind + " 需要填写 API 根地址和模型 ID。");
    if (model.indexOf('\0') >= 0 || model.contains("\n")) throw ApiException.bad("模型 ID 不合法。");
    if (r.apiKey() != null && (r.apiKey().contains("\r") || r.apiKey().contains("\n")))
      throw ApiException.bad("密钥不能包含换行。");
    if (!provider.isEmpty() && (r.clearKey() || r.apiKey() != null && !r.apiKey().isBlank()))
      throw ApiException.bad("复用供应商凭据时，请在供应商页面修改密钥。");
    if (r.enabled()
        && !(base + "/" + (kind.equals("embedding") ? "embeddings" : "rerank") + "\n" + model)
            .equals(r.consentTarget())) throw ApiException.bad("请确认当前模型地址及发送数据范围后再启用。");
    return new Endpoint(r.enabled(), base, model, provider, credential);
  }

  private String key(KnowledgeRetrievalRequest.Endpoint r, Endpoint e) {
    if (!e.providerId().isEmpty()) return "provider";
    if (r.clearKey()) {
      if (!credentials.get(e.credentialId()).isBlank()) credentials.delete(e.credentialId());
      return "none";
    }
    if (r.apiKey() != null && !r.apiKey().isBlank())
      return String.valueOf(
          credentials
              .save(e.credentialId(), r.apiKey(), !Boolean.FALSE.equals(r.rememberKey()))
              .get("storage"));
    return "unchanged";
  }

  public String signature(Endpoint e) {
    return ProjectService.hash(
        (e.baseUrl() + "\n" + e.model() + "\nparagraph-v1-1200-160")
            .getBytes(StandardCharsets.UTF_8));
  }
}
