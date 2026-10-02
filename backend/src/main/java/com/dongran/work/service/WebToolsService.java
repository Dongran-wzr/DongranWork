package com.dongran.work.service;

import com.dongran.work.exception.ApiException;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import javax.swing.text.*;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Service;

@Service
public class WebToolsService {
  private final SearchProviderService searchProvider;

  public WebToolsService(SearchProviderService searchProvider) {
    this.searchProvider = searchProvider;
  }

  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  static URI validate(String url) throws IOException {
    final URI uri;
    try {
      uri = URI.create(url);
    } catch (Exception e) {
      throw ApiException.bad("网页地址无效。");
    }
    if (!Set.of("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || url.length() > 4096) throw ApiException.bad("仅支持公开 HTTP/HTTPS 网页。");
    if (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)
      throw ApiException.bad("网页仅支持 80/443 端口。");
    for (var address : InetAddress.getAllByName(uri.getHost())) {
      byte[] b = address.getAddress();
      int first = b[0] & 255, second = b[1] & 255;
      if (address.isAnyLocalAddress()
          || address.isLoopbackAddress()
          || address.isLinkLocalAddress()
          || address.isSiteLocalAddress()
          || address.isMulticastAddress()
          || b.length == 16 && (first & 254) == 252
          || b.length == 4
              && (first == 0
                  || first >= 224
                  || first == 100 && second >= 64 && second <= 127
                  || first == 198 && (second == 18 || second == 19)))
        throw ApiException.forbidden("不能访问本机、内网或保留网络地址。");
    }
    return uri;
  }

  private record Page(URI url, String type, byte[] body) {}

  private Page load(String url) throws Exception {
    URI current = validate(url);
    for (int i = 0; i < 4; i++) {
      var request =
          HttpRequest.newBuilder(current)
              .timeout(Duration.ofSeconds(20))
              .header("User-Agent", "DongranWork/0.1")
              .header("Accept", "text/html,text/plain,application/xml,application/rss+xml")
              .GET()
              .build();
      var response = client.send(request, info -> new KnowledgeModelClient.LimitedBody());
      if (Set.of(301, 302, 303, 307, 308).contains(response.statusCode())) {
        current =
            validate(
                current
                    .resolve(response.headers().firstValue("location").orElseThrow())
                    .toString());
        continue;
      }
      if (response.statusCode() != 200)
        throw ApiException.bad("网页服务返回 HTTP " + response.statusCode());
      return new Page(
          current, response.headers().firstValue("content-type").orElse(""), response.body());
    }
    throw ApiException.bad("网页重定向次数过多。");
  }

  static Map<String, Object> html(String html, String url) {
    var text = new StringBuilder();
    var title = new StringBuilder();
    try {
      new ParserDelegator()
          .parse(
              new StringReader(html),
              new HTMLEditorKit.ParserCallback() {
                int ignored;
                boolean inTitle;

                public void handleStartTag(HTML.Tag t, MutableAttributeSet a, int pos) {
                  if (t == HTML.Tag.SCRIPT || t == HTML.Tag.STYLE) ignored++;
                  if (t == HTML.Tag.TITLE) inTitle = true;
                  if (t.breaksFlow()) text.append('\n');
                }

                public void handleEndTag(HTML.Tag t, int pos) {
                  if (t == HTML.Tag.SCRIPT || t == HTML.Tag.STYLE)
                    ignored = Math.max(0, ignored - 1);
                  if (t == HTML.Tag.TITLE) inTitle = false;
                  if (t.breaksFlow()) text.append('\n');
                }

                public void handleSimpleTag(HTML.Tag t, MutableAttributeSet a, int pos) {
                  if (t == HTML.Tag.BR) text.append('\n');
                }

                public void handleText(char[] value, int pos) {
                  if (ignored == 0) {
                    if (inTitle) title.append(value);
                    else text.append(value).append(' ');
                  }
                }
              },
              true);
    } catch (IOException e) {
      throw ApiException.bad("网页文本解析失败。");
    }
    String content =
        text.toString().replaceAll("[ \t]+", " ").replaceAll("\n\s*\n+", "\n\n").strip();
    return Map.of(
        "url",
        url,
        "title",
        title.toString(),
        "content",
        content.substring(0, Math.min(content.length(), 30000)),
        "truncated",
        content.length() > 30000,
        "untrusted",
        true);
  }

  public Object fetch(String url) {
    try {
      var page = load(url);
      String type = page.type().toLowerCase(Locale.ROOT);
      Charset encoding = StandardCharsets.UTF_8;
      var match = java.util.regex.Pattern.compile("charset=[\"']?([a-zA-Z0-9_-]+)").matcher(type);
      if (match.find()) encoding = Charset.forName(match.group(1));
      String content = new String(page.body(), encoding);
      if (type.contains("html")) return html(content, page.url().toString());
      if (!type.startsWith("text/") && !type.contains("json") && !type.contains("xml"))
        throw ApiException.bad("网页工具仅支持文本页面，不支持文件下载。");
      return Map.of(
          "url",
          page.url().toString(),
          "content",
          content.substring(0, Math.min(30000, content.length())),
          "truncated",
          content.length() > 30000,
          "untrusted",
          true);
    } catch (ApiException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ApiException.bad("网页请求已取消。");
    } catch (Exception e) {
      throw ApiException.bad("网页请求失败或超时。");
    }
  }

  static List<Map<String, Object>> parseSearch(byte[] bytes) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    var items =
        factory
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(bytes))
            .getElementsByTagName("item");
    var results = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < Math.min(items.getLength(), 20); i++) {
      var item = (org.w3c.dom.Element) items.item(i);
      var row = new LinkedHashMap<String, Object>();
      for (String name : List.of("title", "link", "description")) {
        var nodes = item.getElementsByTagName(name);
        String value = nodes.getLength() > 0 ? nodes.item(0).getTextContent() : "";
        row.put(
            name.equals("link") ? "url" : name.equals("description") ? "snippet" : name,
            value.substring(0, Math.min(value.length(), 2000)));
      }
      String link = String.valueOf(row.get("url"));
      if (link.startsWith("https://") || link.startsWith("http://")) results.add(row);
    }
    return results;
  }

  static List<Map<String, Object>> relevant(String query, List<Map<String, Object>> rows) {
    var terms =
        KnowledgeRetrievalService.tokens(
                query.replace("官方文档", " ").replace("官方", " ").replace("文档", " "))
            .stream()
            .filter(
                t ->
                    t.length() > 1
                        && !Set.of("官方", "文档", "搜索", "documentation", "official").contains(t))
            .distinct()
            .toList();
    return rows.stream()
        .filter(
            row -> {
              String url = String.valueOf(row.get("url"));
              if (url.matches(
                  "(?i)https?://(?:www\\.|cn\\.)?(?:bing\\.com|google\\.[^/]+|sogou\\.com|so\\.com|17so\\.cn)/?(?:\\?.*)?"))
                return false;
              String text =
                  (row.get("title") + " " + row.get("snippet") + " " + url)
                      .toLowerCase(Locale.ROOT);
              var topical =
                  terms.stream()
                      .filter(t -> !t.matches("[0-9]+") && !Set.of("the", "and", "for").contains(t))
                      .toList();
              return topical.isEmpty()
                  || topical.stream().filter(text::contains).count()
                      >= Math.ceil(topical.size() * 0.75);
            })
        .distinct()
        .limit(5)
        .toList();
  }

  public Object search(String query) {
    query = RetrievalQuery.clean(query);
    if (searchProvider != null && !searchProvider.provider().equals("bing"))
      return searchProvider.search(query);
    try {
      var attempts = new ArrayList<String>();
      attempts.add(query);
      // A second, compact query avoids RSS providers treating long Chinese phrases as navigation.
      String refined =
          query
              .replace("虚拟线程", "virtual threads")
              .replace("官方文档", "documentation")
              .replaceAll("[，。；、？]", " ")
              .strip();
      attempts.add(refined.equals(query) ? "\"" + query + "\"" : refined);
      List<Map<String, Object>> rows = List.of();
      for (String attempt : attempts) {
        var page =
            load(
                "https://www.bing.com/search?format=rss&q="
                    + URLEncoder.encode(attempt, StandardCharsets.UTF_8));
        rows = relevant(attempt, parseSearch(page.body()));
        if (!rows.isEmpty()) break;
      }
      return Map.of(
          "query",
          query,
          "provider",
          "Bing RSS",
          "results",
          rows,
          "untrusted",
          true,
          "notice",
          rows.isEmpty() ? "搜索服务未返回相关结果，请缩短关键词或提供目标网页地址。不得用已有知识冒充搜索结果。" : "搜索摘要不是网页全文。");
    } catch (ApiException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ApiException.bad("搜索已取消。");
    } catch (Exception e) {
      throw ApiException.bad("搜索服务不可用，请稍后重试或直接提供网页 URL。");
    }
  }
}
