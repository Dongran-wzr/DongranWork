package com.dongran.work.service;

import com.dongran.work.exception.ApiException;
import com.dongran.work.infrastructure.Database;
import com.dongran.work.repository.KnowledgeIndexRepository;
import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Local SQLite vector retrieval with BM25 fallback. */
@Service
public class KnowledgeRetrievalService {
  private final KnowledgeIndexRepository index;
  private final TransactionTemplate transactions;
  private final KnowledgeModelSettings settings;
  private final KnowledgeModelClient models;
  private final java.util.concurrent.ExecutorService worker =
      java.util.concurrent.Executors.newSingleThreadExecutor();
  private final java.util.concurrent.ConcurrentHashMap<String, Map<String, Object>> jobs =
      new java.util.concurrent.ConcurrentHashMap<>();

  @jakarta.annotation.PreDestroy
  void stop() {
    worker.shutdownNow();
  }

  public KnowledgeRetrievalService(
      KnowledgeIndexRepository index,
      PlatformTransactionManager tx,
      KnowledgeModelSettings settings,
      KnowledgeModelClient models) {
    this.index = index;
    this.settings = settings;
    this.models = models;
    transactions = new TransactionTemplate(tx);
  }

  @PostConstruct
  void migrate() {
    for (var row : index.unindexed())
      replaceText(
          String.valueOf(row.get("id")),
          String.valueOf(row.get("name")),
          String.valueOf(row.get("content")));
  }

  public static List<String> tokens(String text) {
    var output = new ArrayList<String>();
    var matcher =
        Pattern.compile("[\\p{IsHan}]+|[\\p{L}\\p{N}_]+").matcher(text.toLowerCase(Locale.ROOT));
    while (matcher.find()) {
      String word = matcher.group();
      int[] points = word.codePoints().toArray();
      if (Character.UnicodeScript.of(points[0]) == Character.UnicodeScript.HAN) {
        for (int i = 0; i < points.length; i++) {
          output.add(new String(points, i, 1));
          if (i + 1 < points.length) output.add(new String(points, i, 2));
        }
      } else output.add(word);
    }
    return output;
  }

  public void replaceText(String id, String name, String text) {
    transactions.executeWithoutResult(
        status -> {
          index.deleteChunks(id);
          int position = 0, ordinal = 0;
          while (position < text.length()) {
            int end = Math.min(text.length(), position + 1200);
            if (end < text.length()) {
              int breakAt = text.lastIndexOf('\n', end);
              if (breakAt > position + 700) end = breakAt + 1;
            }
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            String chunk = text.substring(position, end);
            index.addChunk(
                Database.id(),
                id,
                ordinal++,
                chunk,
                String.join(" ", tokens(name)),
                String.join(" ", tokens(chunk)));
            if (end == text.length()) break;
            position = Math.max(position + 1, end - 160);
            if (Character.isLowSurrogate(text.charAt(position))) position++;
          }
        });
  }

  public Map<String, Object> indexStatus(String id) {
    var result =
        new LinkedHashMap<>(index.status(id, settings.signature(settings.get().embedding())));
    result.putAll(jobs.getOrDefault(id, Map.of("state", "idle")));
    if ("completed".equals(result.get("state"))
        && ((Number) result.get("embedded")).intValue()
            < ((Number) result.get("chunks")).intValue()) {
      result.put("state", "idle");
      result.put("message", "文档或模型已改变，请重新建立索引");
    }
    return result;
  }

  public synchronized Map<String, Object> buildIndex(String id) {
    var config = settings.get();
    if (!config.embedding().enabled()) throw ApiException.bad("请先在检索设置启用 Embedding。");
    if (jobs.containsKey(id) && Set.of("queued", "running").contains(jobs.get(id).get("state")))
      return indexStatus(id);
    if (jobs.values().stream()
            .filter(v -> Set.of("queued", "running").contains(v.get("state")))
            .count()
        >= 8) throw ApiException.bad("索引队列已满，请稍后重试。");
    if (jobs.size() > 128)
      jobs.entrySet()
          .removeIf(entry -> Set.of("completed", "failed").contains(entry.getValue().get("state")));
    var chunks = index.chunks(id);
    if (chunks.isEmpty()) throw ApiException.bad("文档没有可建立索引的文本。");
    String signature = settings.signature(config.embedding());
    jobs.put(id, Map.of("state", "queued", "message", "等待建立索引"));
    worker.submit(
        () -> {
          try {
            jobs.put(id, Map.of("state", "running", "message", "正在建立向量索引"));
            int dimension = 0;
            for (int start = 0; start < chunks.size(); start += 16) {
              var current = settings.get();
              if (!current.embedding().enabled() || current.revision() != config.revision())
                throw ApiException.bad("配置已改变，请重新建立索引。");
              var batch = chunks.subList(start, Math.min(chunks.size(), start + 16));
              var vectors =
                  models.embed(
                      config.embedding(),
                      batch.stream().map(r -> String.valueOf(r.get("content"))).toList());
              if (dimension != 0 && dimension != vectors.getFirst().length)
                throw ApiException.bad("模型维度发生改变。");
              dimension = vectors.getFirst().length;
              transactions.executeWithoutResult(
                  tx -> {
                    if (settings.get().revision() != config.revision())
                      throw ApiException.bad("配置已改变。");
                    for (int i = 0; i < batch.size(); i++)
                      if (index.vector(
                              String.valueOf(batch.get(i).get("id")), vectors.get(i), signature)
                          != 1) throw ApiException.bad("文档已修改或删除，请重新建立索引。");
                  });
            }
            jobs.put(id, Map.of("state", "completed", "message", "向量索引已建立"));
          } catch (Exception e) {
            jobs.put(
                id,
                Map.of(
                    "state",
                    "failed",
                    "message",
                    e instanceof ApiException ? e.getMessage() : "索引失败，请重试。"));
          }
        });
    return indexStatus(id);
  }

  public Map<String, Object> search(String project, String query, int limit) {
    if (query == null || query.isBlank() || query.length() > 200)
      throw ApiException.bad("请输入 1 至 200 字的查询。");
    var config = settings.get();
    int top = Math.max(1, Math.min(config.topK(), limit));
    var warnings = new ArrayList<String>();
    List<Map<String, Object>> results = null;
    String mode = "bm25";
    if (config.embedding().enabled() && !config.mode().equals("bm25")) {
      String signature = settings.signature(config.embedding());
      try {
        if (!index.hasVectors(project, signature)) throw ApiException.bad("当前范围没有可用向量索引");
        var vector = models.embed(config.embedding(), List.of(query)).getFirst();
        results = index.nearest(project, signature, vector, config.candidates());
        if (results.isEmpty()) throw ApiException.bad("向量维度不匹配，请重新建立索引");
        mode = "vector";
      } catch (Exception e) {
        results = null;
        warnings.add("向量检索不可用，已降级 BM25。" + (e instanceof ApiException ? e.getMessage() : ""));
      }
    }
    if (results == null) {
      var terms = tokens(query).stream().distinct().limit(40).toList();
      String expression =
          terms.stream()
              .map(v -> "\"" + v.replace("\"", "\"\"") + "\"")
              .collect(java.util.stream.Collectors.joining(" OR "));
      results =
          terms.isEmpty()
              ? new ArrayList<>()
              : index.lexical(project, expression, config.candidates());
    }
    if (config.rerank().enabled() && !results.isEmpty()) {
      try {
        results = models.rerank(config.rerank(), query, results, top);
        mode += "+rerank";
      } catch (Exception e) {
        warnings.add("重排不可用，保留原检索排序。");
      }
    }
    results = new ArrayList<>(results.subList(0, Math.min(top, results.size())));
    for (var row : results)
      row.put("location", "片段 " + (((Number) row.get("ordinal")).intValue() + 1));
    return Map.of("results", results, "mode", mode, "warnings", warnings);
  }
}
