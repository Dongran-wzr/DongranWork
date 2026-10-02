package com.dongran.work.service;

import com.dongran.work.exception.ApiException;
import com.dongran.work.infrastructure.CredentialService;
import com.dongran.work.infrastructure.Database;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;

/** Only called for explicitly enabled destinations. Redirects never receive credentials or data. */
@Service
public class KnowledgeModelClient {
  private final Database db;
  private final CredentialService credentials;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public KnowledgeModelClient(Database db, CredentialService credentials) {
    this.db = db;
    this.credentials = credentials;
  }

  private JsonNode call(
      KnowledgeModelSettings.Endpoint endpoint, String path, Object body, String key) {
    if (!endpoint.enabled()) throw ApiException.bad("模型尚未启用。");
    try {
      var builder =
          HttpRequest.newBuilder(URI.create(endpoint.baseUrl() + path))
              .timeout(Duration.ofSeconds(45))
              .header("Content-Type", "application/json");
      if (!key.isBlank()) builder.header("Authorization", "Bearer " + key);
      var response =
          client.send(
              builder.POST(HttpRequest.BodyPublishers.ofString(db.json(body))).build(),
              info -> new LimitedBody());
      if (response.statusCode() != 200)
        throw ApiException.bad("模型服务返回 HTTP " + response.statusCode());
      if (response.body().length > 8 * 1024 * 1024) throw ApiException.bad("模型响应过大。");
      return db.mapper.readTree(response.body());
    } catch (com.dongran.work.exception.ApiException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ApiException.bad("模型请求已中断。");
    } catch (Exception e) {
      throw ApiException.bad("模型请求失败，请检查地址、凭据和接口兼容性。");
    }
  }

  public Map<String, Object> test(
      KnowledgeModelSettings.Endpoint endpoint, String kind, String key) {
    long start = System.nanoTime();
    var result = new LinkedHashMap<String, Object>();
    if (kind.equals("embedding")) {
      var vectors = embed(endpoint, List.of("Dongran connection test."), key);
      result.put("dimensions", vectors.getFirst().length);
    } else {
      var rows =
          rerank(
              endpoint,
              "Which document describes an apple?",
              List.of(
                  Map.of("excerpt", "An apple is a fruit."),
                  Map.of("excerpt", "A train is a vehicle.")),
              2,
              key);
      result.put("results", rows.size());
    }
    result.put("ok", true);
    result.put("elapsedMs", (System.nanoTime() - start) / 1_000_000);
    return result;
  }

  static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate =
        HttpResponse.BodySubscribers.ofByteArray();
    private java.util.concurrent.Flow.Subscription subscription;
    private long count;
    private boolean failed;

    public java.util.concurrent.CompletionStage<byte[]> getBody() {
      return delegate.getBody();
    }

    public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
      subscription = s;
      delegate.onSubscribe(s);
    }

    public void onNext(List<java.nio.ByteBuffer> buffers) {
      if (failed) return;
      for (var buffer : buffers) count += buffer.remaining();
      if (count > 8 * 1024 * 1024) {
        failed = true;
        subscription.cancel();
        delegate.onError(new java.io.IOException("Response limit exceeded"));
        return;
      }
      delegate.onNext(buffers);
    }

    public void onError(Throwable error) {
      if (!failed) delegate.onError(error);
    }

    public void onComplete() {
      if (!failed) delegate.onComplete();
    }
  }

  public List<float[]> embed(KnowledgeModelSettings.Endpoint endpoint, List<String> input) {
    return embed(endpoint, input, credentials.get(endpoint.credentialId()));
  }

  private List<float[]> embed(
      KnowledgeModelSettings.Endpoint endpoint, List<String> input, String key) {
    JsonNode data =
        call(
                endpoint,
                "/embeddings",
                Map.of("model", endpoint.model(), "input", input, "encoding_format", "float"),
                key)
            .path("data");
    if (!data.isArray() || data.size() != input.size())
      throw ApiException.bad("Embedding 返回数量不匹配。");
    float[][] output = new float[input.size()][];
    int dimension = 0;
    for (var item : data) {
      int i = item.path("index").asInt(-1);
      var values = item.path("embedding");
      if (i < 0
          || i >= output.length
          || output[i] != null
          || !values.isArray()
          || values.size() < 1
          || values.size() > 16384) throw ApiException.bad("Embedding 返回格式无效。");
      if (dimension != 0 && dimension != values.size()) throw ApiException.bad("Embedding 维度不一致。");
      dimension = values.size();
      float[] vector = new float[dimension];
      double norm = 0;
      for (int j = 0; j < dimension; j++) {
        if (!values.get(j).isNumber()) throw ApiException.bad("向量必须为数值。");
        vector[j] = (float) values.get(j).asDouble();
        if (!Float.isFinite(vector[j])) throw ApiException.bad("向量包含非法数值。");
        norm += (double) vector[j] * vector[j];
      }
      if (norm == 0) throw ApiException.bad("模型返回了零向量。");
      norm = Math.sqrt(norm);
      for (int j = 0; j < dimension; j++) vector[j] /= norm;
      output[i] = vector;
    }
    return Arrays.asList(output);
  }

  public List<Map<String, Object>> rerank(
      KnowledgeModelSettings.Endpoint endpoint,
      String query,
      List<Map<String, Object>> rows,
      int limit) {
    return rerank(endpoint, query, rows, limit, credentials.get(endpoint.credentialId()));
  }

  private List<Map<String, Object>> rerank(
      KnowledgeModelSettings.Endpoint endpoint,
      String query,
      List<Map<String, Object>> rows,
      int limit,
      String key) {
    var data =
        call(
                endpoint,
                "/rerank",
                Map.of(
                    "model",
                    endpoint.model(),
                    "query",
                    query,
                    "documents",
                    rows.stream().map(r -> r.get("excerpt")).toList(),
                    "top_n",
                    Math.min(limit, rows.size()),
                    "return_documents",
                    false),
                key)
            .path("results");
    if (!data.isArray() || data.size() != Math.min(limit, rows.size()))
      throw ApiException.bad("Rerank 返回数量不匹配。");
    var seen = new HashSet<Integer>();
    var result = new ArrayList<Map<String, Object>>();
    for (var item : data) {
      int i = item.path("index").asInt(-1);
      double score = item.path("relevance_score").asDouble(Double.NaN);
      if (i < 0 || i >= rows.size() || !seen.add(i) || !Double.isFinite(score))
        throw ApiException.bad("Rerank 返回格式无效。");
      var row = new LinkedHashMap<>(rows.get(i));
      row.put("rerankScore", score);
      result.add(row);
    }
    result.sort(
        Comparator.comparingDouble(
                (Map<String, Object> r) -> ((Number) r.get("rerankScore")).doubleValue())
            .reversed());
    return result;
  }
}
