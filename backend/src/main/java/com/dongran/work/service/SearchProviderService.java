package com.dongran.work.service;

import com.dongran.work.exception.ApiException;
import com.dongran.work.infrastructure.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SearchProviderService {
  private final PreferenceService preferences;
  private final CredentialService credentials;
  private final Database db;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public SearchProviderService(
      PreferenceService preferences, CredentialService credentials, Database db) {
    this.preferences = preferences;
    this.credentials = credentials;
    this.db = db;
  }

  public String provider() {
    return preferences.string("webSearchProvider", "bing");
  }

  public Map<String, Object> view() {
    return Map.of(
        "provider",
        provider(),
        "tavilyConfigured",
        !credentials.get("search-tavily").isBlank(),
        "braveConfigured",
        !credentials.get("search-brave").isBlank());
  }

  public synchronized Map<String, Object> save(Map<String, Object> body) {
    String provider = Database.required(body, "provider", 20);
    if (!Set.of("bing", "tavily", "brave").contains(provider)) throw ApiException.bad("搜索服务不支持。");
    Object raw = body.get("apiKey");
    if (raw != null && !(raw instanceof String)) throw ApiException.bad("密钥格式不合法。");
    String key = raw == null ? "" : (String) raw;
    String storage = "unchanged";
    if (!provider.equals("bing")) {
      if (Database.bool(body, "clearKey", false)) credentials.delete("search-" + provider);
      else if (!key.isBlank())
        storage =
            String.valueOf(
                credentials
                    .save("search-" + provider, key, Database.bool(body, "rememberKey", false))
                    .get("storage"));
    }
    preferences.patch(Map.of("webSearchProvider", provider));
    var result = new LinkedHashMap<>(view());
    result.put("credentialStorage", storage);
    return result;
  }

  public Object search(String query) {
    if (!preferences.bool("allowNetwork", false))
      throw ApiException.forbidden("请先在权限与安全中允许工具网络访问。");
    String provider = provider(), key = credentials.get("search-" + provider);
    if (key.isBlank()) throw ApiException.bad("请在设置 → 网页搜索中配置 " + provider + " API Key。");
    try {
      var response =
          http.send(
              request(provider, key, query, db), info -> new KnowledgeModelClient.LimitedBody());
      if (response.statusCode() != 200)
        throw ApiException.bad("搜索 API 返回 HTTP " + response.statusCode() + "，请检查密钥、额度和网络。");
      return Map.of(
          "query",
          query,
          "provider",
          provider,
          "results",
          parse(provider, db.object(new String(response.body(), StandardCharsets.UTF_8))),
          "untrusted",
          true,
          "notice",
          "搜索摘要不是网页全文。");
    } catch (ApiException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ApiException.bad("搜索已取消。");
    } catch (Exception e) {
      throw ApiException.bad("搜索 API 请求失败或超时。");
    }
  }

  static HttpRequest request(String provider, String key, String query, Database db) {
    if (provider.equals("tavily"))
      return HttpRequest.newBuilder(URI.create("https://api.tavily.com/search"))
          .timeout(Duration.ofSeconds(20))
          .header("Authorization", "Bearer " + key)
          .header("Content-Type", "application/json")
          .POST(
              HttpRequest.BodyPublishers.ofString(
                  db.json(
                      Map.of(
                          "query",
                          query,
                          "max_results",
                          5,
                          "search_depth",
                          "basic",
                          "include_answer",
                          false))))
          .build();
    if (!provider.equals("brave")) throw ApiException.bad("搜索服务不支持。");
    return HttpRequest.newBuilder(
            URI.create(
                "https://api.search.brave.com/res/v1/web/search?count=5&q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)))
        .timeout(Duration.ofSeconds(20))
        .header("X-Subscription-Token", key)
        .header("Accept", "application/json")
        .GET()
        .build();
  }

  static List<Map<String, Object>> parse(String provider, Map<String, Object> payload) {
    Object raw =
        provider.equals("brave") && payload.get("web") instanceof Map<?, ?> web
            ? web.get("results")
            : payload.get("results");
    if (!(raw instanceof List<?> list)) throw ApiException.bad("搜索 API 响应格式无效。");
    var rows = new ArrayList<Map<String, Object>>();
    for (var item : list) {
      if (!(item instanceof Map<?, ?> row)) continue;
      String url = Objects.toString(row.get("url"), "");
      try {
        var uri = URI.create(url);
        if (!Set.of("http", "https").contains(uri.getScheme())
            || uri.getHost() == null
            || uri.getUserInfo() != null) continue;
      } catch (Exception e) {
        continue;
      }
      String snippet =
          Objects.toString(row.get(provider.equals("brave") ? "description" : "content"), "");
      rows.add(
          Map.of(
              "url",
              url,
              "title",
              Objects.toString(row.get("title"), url),
              "snippet",
              snippet.substring(0, Math.min(4000, snippet.length()))));
      if (rows.size() == 5) break;
    }
    return rows;
  }
}
