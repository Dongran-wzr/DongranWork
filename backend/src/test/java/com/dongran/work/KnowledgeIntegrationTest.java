package com.dongran.work;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dongran.work.service.KnowledgeRetrievalService;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeIntegrationTest {
  static final Path DATA = temporary();
  static final String TOKEN = "knowledge-tests-token-01234567890123456789";

  static Path temporary() {
    try {
      return Files.createTempDirectory("dongran-knowledge-test-");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("dongran.data-dir", DATA::toString);
    r.add("dongran.token", () -> TOKEN);
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  static Map<String, byte[]> fixtures = new LinkedHashMap<>();

  @BeforeAll
  static void fixtures() throws Exception {
    var output = new ByteArrayOutputStream();
    try (var doc = new XWPFDocument()) {
      var title = doc.createParagraph();
      title.setStyle("Title");
      title.createRun().setText("账号权限管理");
      doc.createParagraph().createRun().setText("离职员工必须完成账号回收。验收标准：所有访问权限在当天撤销。");
      var table = doc.createTable(2, 2);
      table.getRow(0).getCell(0).setText("责任人");
      table.getRow(0).getCell(1).setText("核验项");
      table.getRow(1).getCell(0).setText("管理员");
      table.getRow(1).getCell(1).setText("权限回收");
      doc.write(output);
      fixtures.put("requirements.docx", output.toByteArray());
    }
    output.reset();
    try (var wb = new XSSFWorkbook()) {
      var sheet = wb.createSheet("权限清单");
      sheet.createRow(0).createCell(0).setCellValue("权限回收清单");
      sheet.createRow(1).createCell(0).setCellValue("项目数据库访问");
      wb.write(output);
      fixtures.put("permissions.xlsx", output.toByteArray());
    }
    output.reset();
    try (var wb = new HSSFWorkbook()) {
      wb.createSheet("旧格式").createRow(0).createCell(0).setCellValue("权限回收旧表");
      wb.write(output);
      fixtures.put("legacy.xls", output.toByteArray());
    }
    output.reset();
    try (var show = new XMLSlideShow()) {
      show.createSlide().createTextBox().setText("权限回收方案");
      show.createSlide().createTextBox().setText("验收：员工离职后撤销所有权限");
      show.write(output);
      fixtures.put("review.pptx", output.toByteArray());
    }
    output.reset();
    try (var show = new HSLFSlideShow()) {
      show.createSlide().createTextBox().setText("Legacy permission policy");
      show.write(output);
      fixtures.put("legacy.ppt", output.toByteArray());
    }
    output.reset();
    try (var pdf = new PDDocument()) {
      for (int i = 1; i <= 2; i++) {
        var page = new PDPage();
        pdf.addPage(page);
        try (var cs = new PDPageContentStream(pdf, page)) {
          cs.beginText();
          cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 20);
          cs.newLineAtOffset(50, 740);
          cs.showText("Knowledge workspace - page " + i);
          cs.endText();
        }
      }
      pdf.save(output);
      fixtures.put("guide.pdf", output.toByteArray());
    }
    output.reset();
    try (var pdf = new PDDocument()) {
      pdf.addPage(new PDPage());
      pdf.save(output);
      fixtures.put("scan.pdf", output.toByteArray());
    }
    fixtures.put(
        "notes.md",
        "# 账号权限\n\n**验收标准**：离职后撤销权限。\n\n<script>parent.window.__unsafePreview=true</script>\n\n![remote](https://example.invalid/pixel)\n"
            .getBytes(StandardCharsets.UTF_8));
    fixtures.put("notes.txt", "本地资料：项目权限回收和工作树隔离。".getBytes(StandardCharsets.UTF_8));
    fixtures.put(
        "report.csv", "item,status\npermission,revoked\n".getBytes(StandardCharsets.UTF_8));
    fixtures.put(
        "notes.rtf",
        "{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Arial;}}\\f0\\fs24 Permission revocation guide\\par}"
            .getBytes(StandardCharsets.UTF_8));
    var dir = Path.of("../.runtime/knowledge-fixtures").toAbsolutePath().normalize();
    Files.createDirectories(dir);
    for (var entry : fixtures.entrySet())
      Files.write(dir.resolve(entry.getKey()), entry.getValue());
  }

  MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b) {
    return b.header("Host", "127.0.0.1:3210")
        .header("Authorization", "Bearer " + TOKEN)
        .header("X-Dongran-Client", "desktop")
        .with(
            r -> {
              r.setLocalPort(3210);
              return r;
            });
  }

  MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder b, Object value)
      throws Exception {
    return auth(b).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(value));
  }

  JsonNode result(MockHttpServletRequestBuilder request) throws Exception {
    return json.readTree(
        mvc.perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray());
  }

  @Test
  void officeFormatsPreserveOriginalAndExposePreviewWithoutBinaryMetadata() throws Exception {
    for (String name :
        List.of(
            "requirements.docx",
            "permissions.xlsx",
            "legacy.xls",
            "review.pptx",
            "legacy.ppt",
            "notes.rtf",
            "notes.md",
            "notes.txt",
            "report.csv",
            "guide.pdf")) {
      byte[] original = fixtures.get(name);
      var saved =
          result(
              auth(
                  multipart("/api/knowledge/upload")
                      .file(
                          new MockMultipartFile(
                              "file", name, "application/octet-stream", original))));
      String id = saved.path("id").asText();
      assertThat(saved.path("content").asText()).as(name).isNotBlank();
      assertThat(saved.has("sourceBytes")).isFalse();
      var preview = result(auth(get("/api/knowledge/" + id + "/preview")));
      assertThat(preview.path("sourceUrl").asText()).endsWith("/source");
      assertThat(
              mvc.perform(auth(get("/api/knowledge/" + id + "/source")))
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsByteArray())
          .isEqualTo(original);
      mvc.perform(
              body(put("/api/knowledge/" + id), Map.of("name", "changed", "content", "modified")))
          .andExpect(status().isConflict());
      result(auth(delete("/api/knowledge/" + id)));
      mvc.perform(auth(get("/api/knowledge/" + id + "/source"))).andExpect(status().isNotFound());
    }
  }

  @Test
  void scannedPdfRemainsPreviewableWithoutPretendingToHaveText() throws Exception {
    var row =
        result(
            auth(
                multipart("/api/knowledge/upload")
                    .file(
                        new MockMultipartFile(
                            "file", "scan.pdf", "application/pdf", fixtures.get("scan.pdf")))));
    assertThat(row.path("content").asText()).isEmpty();
    var preview = result(auth(get("/api/knowledge/" + row.path("id").asText() + "/preview")));
    assertThat(preview.path("format").asText()).isEqualTo("pdf");
  }

  @Test
  void badDocumentsReturnHelpfulClientErrors() throws Exception {
    for (String name : List.of("bad.pdf", "bad.docx", "bad.exe")) {
      mvc.perform(
              auth(
                  multipart("/api/knowledge/upload")
                      .file(
                          new MockMultipartFile(
                              "file", name, "application/octet-stream", new byte[] {0, 1, 2, 3}))))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            auth(
                multipart("/api/knowledge/upload")
                    .file(
                        new MockMultipartFile(
                            "file",
                            "bad.txt",
                            "text/plain",
                            new byte[] {(byte) 0xff, (byte) 0xfe}))))
        .andExpect(status().isBadRequest());
    mvc.perform(
            auth(
                multipart("/api/knowledge/upload")
                    .file(
                        new MockMultipartFile(
                            "file",
                            "too-large.txt",
                            "text/plain",
                            new byte[10 * 1024 * 1024 + 1]))))
        .andExpect(status().isBadRequest());
  }

  String project() throws Exception {
    return result(body(post("/api/projects"), Map.of("path", temporary().toString())))
        .path("id")
        .asText();
  }

  @Test
  void bm25FindsChineseAndEnglishChunksAndEnforcesProjectScope() throws Exception {
    String p1 = project(), p2 = project();
    String global =
        result(body(post("/api/knowledge"), Map.of("name", "全局资料", "content", "所有项目适用的权限撤销流程")))
            .path("id")
            .asText();
    String own =
        result(
                body(
                    post("/api/knowledge"),
                    Map.of(
                        "projectId",
                        p1,
                        "name",
                        "登录权限",
                        "content",
                        "登录权限回收 " + "验收标准。".repeat(600) + " ScopedTokenExample")))
            .path("id")
            .asText();
    String other =
        result(
                body(
                    post("/api/knowledge"),
                    Map.of(
                        "projectId", p2, "name", "保密资料", "content", "登录权限回收 ScopedTokenExample")))
            .path("id")
            .asText();
    var search =
        result(auth(get("/api/knowledge/search").param("projectId", p1).param("query", "登录 权限")));
    assertThat(search.path("mode").asText()).isEqualTo("bm25");
    var ids = new ArrayList<String>();
    search.path("results").forEach(r -> ids.add(r.path("id").asText()));
    assertThat(ids).contains(own, global).doesNotContain(other);
    var latin =
        result(
                auth(
                    get("/api/knowledge/search")
                        .param("projectId", p1)
                        .param("query", "ScopedTokenExample")))
            .path("results");
    assertThat(latin.size()).isPositive();
    assertThat(latin.get(0).path("location").asText()).contains("片段");
    result(auth(delete("/api/knowledge/" + own)));
    assertThat(
            result(
                    auth(
                        get("/api/knowledge/search")
                            .param("projectId", p1)
                            .param("query", "ScopedTokenExample")))
                .path("results")
                .size())
        .isZero();
    assertThat(KnowledgeRetrievalService.tokens("权限 rollback")).contains("权限", "rollback");
  }

  @Test
  void cloudSettingsRequireExplicitDestinationConsent() throws Exception {
    var old = result(auth(get("/api/knowledge/retrieval/settings")));
    var endpoint = new LinkedHashMap<String, Object>();
    endpoint.put("enabled", false);
    endpoint.put("baseUrl", "https://example.invalid/v1");
    endpoint.put("model", "embedding-fixture");
    endpoint.put("apiKey", "only-test-key");
    endpoint.put("rememberKey", false);
    var input = new LinkedHashMap<String, Object>();
    input.put("revision", old.path("revision").asLong());
    input.put("mode", "bm25");
    input.put("candidates", 30);
    input.put("topK", 8);
    input.put("embedding", endpoint);
    input.put("rerank", Map.of("enabled", false));
    var saved = result(body(put("/api/knowledge/retrieval/settings"), input));
    assertThat(saved.path("cloudRequestsAvailable").asBoolean()).isTrue();
    assertThat(saved.toString()).doesNotContain("only-test-key", "credentialId");
    mvc.perform(body(put("/api/knowledge/retrieval/settings"), input))
        .andExpect(status().isConflict());
    input.put("revision", saved.path("revision").asLong());
    endpoint.put("enabled", true);
    mvc.perform(body(put("/api/knowledge/retrieval/settings"), input))
        .andExpect(status().isBadRequest());
  }

  @Autowired KnowledgeRetrievalService retrieval;
  @Autowired com.dongran.work.repository.KnowledgeIndexRepository vectorIndex;
  @Autowired com.dongran.work.service.KnowledgeModelSettings modelSettings;

  @Test
  void localVectorsAndOptionalRerankUseConfiguredEndpointsAndFallback() throws Exception {
    var mode = new java.util.concurrent.atomic.AtomicInteger();
    var requests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          var input = json.readTree(exchange.getRequestBody());
          requests.add(input);
          assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
              .isEqualTo("Bearer vector-fixture-key");
          var data = new ArrayList<Object>();
          int index = 0;
          for (var text : input.path("input"))
            data.add(
                Map.of(
                    "index",
                    index++,
                    "embedding",
                    mode.get() == 2
                        ? List.of(1, 0, 0)
                        : text.asText().contains("orange") ? List.of(0, 1) : List.of(1, 0)));
          byte[] bytes = json.writeValueAsBytes(Map.of("data", data));
          exchange.sendResponseHeaders(mode.get() == 1 ? 503 : 200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.createContext(
        "/v1/rerank",
        exchange -> {
          var input = json.readTree(exchange.getRequestBody());
          requests.add(input);
          byte[] bytes =
              json.writeValueAsBytes(
                  Map.of(
                      "results",
                      List.of(
                          Map.of("index", 1, "relevance_score", 0.9),
                          Map.of("index", 0, "relevance_score", 0.1))));
          exchange.sendResponseHeaders(mode.get() == 3 ? 500 : 200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    var embedding = new LinkedHashMap<String, Object>();
    String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    embedding.put("enabled", true);
    embedding.put("baseUrl", url);
    embedding.put("model", "fixture-embed");
    embedding.put("apiKey", "vector-fixture-key");
    embedding.put("rememberKey", false);
    embedding.put("consentTarget", url + "/embeddings\nfixture-embed");
    var config = new LinkedHashMap<String, Object>();
    config.put("mode", "vector_bm25");
    config.put("topK", 2);
    config.put("candidates", 5);
    config.put("embedding", embedding);
    config.put("rerank", Map.of("enabled", false));
    try {
      config.put("revision", modelSettings.get().revision());
      result(body(put("/api/knowledge/retrieval/settings"), config));
      assertThat(requests).isEmpty();
      var apple =
          result(
                  body(
                      post("/api/knowledge"),
                      Map.of("name", "vector apple", "content", "apple orchard synthetic fixture")))
              .path("id")
              .asText();
      var orange =
          result(
                  body(
                      post("/api/knowledge"),
                      Map.of(
                          "name", "vector orange", "content", "orange orchard synthetic fixture")))
              .path("id")
              .asText();
      assertThat(retrieval.search(null, "orchard", 2).get("mode")).isEqualTo("bm25");
      assertThat(requests).isEmpty();
      for (String id : List.of(apple, orange)) {
        result(auth(post("/api/knowledge/" + id + "/index")));
        long until = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (Set.of("running", "queued").contains(retrieval.indexStatus(id).get("state"))
            && System.nanoTime() < until) Thread.sleep(20);
        assertThat(retrieval.indexStatus(id).get("state")).isEqualTo("completed");
        assertThat(((Number) retrieval.indexStatus(id).get("embedded")).intValue()).isEqualTo(1);
      }
      var found = json.valueToTree(retrieval.search(null, "orchard", 2));
      assertThat(found.path("mode").asText()).isEqualTo("vector");
      assertThat(found.path("results").get(0).path("id").asText()).isEqualTo(apple);
      var rerank = new LinkedHashMap<>(embedding);
      rerank.put("model", "fixture-rerank");
      rerank.put("consentTarget", url + "/rerank\nfixture-rerank");
      config.put("rerank", rerank);
      config.put("revision", modelSettings.get().revision());
      result(body(put("/api/knowledge/retrieval/settings"), config));
      found = json.valueToTree(retrieval.search(null, "orchard", 2));
      assertThat(found.path("mode").asText()).isEqualTo("vector+rerank");
      assertThat(found.path("results").get(0).path("id").asText()).isEqualTo(orange);
      mode.set(3);
      found = json.valueToTree(retrieval.search(null, "orchard", 2));
      assertThat(found.path("mode").asText()).isEqualTo("vector");
      assertThat(found.path("warnings").size()).isEqualTo(1);
      config.put("rerank", Map.of("enabled", false));
      config.put("revision", modelSettings.get().revision());
      result(body(put("/api/knowledge/retrieval/settings"), config));
      for (int failure : List.of(1, 2)) {
        mode.set(failure);
        found = json.valueToTree(retrieval.search(null, "orchard", 2));
        assertThat(found.path("mode").asText()).isEqualTo("bm25");
        assertThat(found.path("results").size()).isEqualTo(2);
        assertThat(found.path("warnings").size()).isEqualTo(1);
      }
      mode.set(0);
      embedding.put("model", "changed-model");
      embedding.put("consentTarget", url + "/embeddings\nchanged-model");
      config.put("revision", modelSettings.get().revision());
      result(body(put("/api/knowledge/retrieval/settings"), config));
      int previous = requests.size();
      assertThat(retrieval.search(null, "orchard", 2).get("mode")).isEqualTo("bm25");
      assertThat(requests).hasSize(previous);
      mvc.perform(auth(delete("/api/knowledge/" + apple))).andExpect(status().isOk());
      assertThat(vectorIndex.chunks(apple)).isEmpty();
    } finally {
      server.stop(0);
      config.put("mode", "bm25");
      config.put("embedding", Map.of("enabled", false));
      config.put("rerank", Map.of("enabled", false));
      config.put("revision", modelSettings.get().revision());
      result(body(put("/api/knowledge/retrieval/settings"), config));
    }
  }

  @Test
  void vectorScanRanksAllChunksAndRestrictsProjectScope() throws Exception {
    String ownProject = project(), otherProject = project();
    String own =
        result(
                body(
                    post("/api/knowledge"),
                    Map.of(
                        "name",
                        "Vector scan fixture",
                        "projectId",
                        ownProject,
                        "content",
                        "fixture ".repeat(5000))))
            .path("id")
            .asText();
    String foreign =
        result(
                body(
                    post("/api/knowledge"),
                    Map.of(
                        "name",
                        "Private fixture",
                        "projectId",
                        otherProject,
                        "content",
                        "private synthetic fixture")))
            .path("id")
            .asText();
    var chunks = vectorIndex.chunks(own);
    assertThat(chunks.size()).isGreaterThan(30);
    for (var chunk : chunks)
      vectorIndex.vector(String.valueOf(chunk.get("id")), new float[] {0, 1}, "scan-fixture");
    String last = String.valueOf(chunks.getLast().get("id"));
    vectorIndex.vector(last, new float[] {1, 0}, "scan-fixture");
    vectorIndex.vector(
        String.valueOf(vectorIndex.chunks(foreign).getFirst().get("id")),
        new float[] {1, 0},
        "scan-fixture");
    assertThat(
            vectorIndex
                .nearest(ownProject, "scan-fixture", new float[] {1, 0}, 1)
                .getFirst()
                .get("chunkId"))
        .isEqualTo(last);
    assertThat(vectorIndex.nearest(null, "scan-fixture", new float[] {1, 0}, 5)).isEmpty();
    retrieval.replaceText(own, "Vector scan fixture", "updated synthetic document");
    assertThat(vectorIndex.hasVectors(ownProject, "scan-fixture")).isFalse();
  }

  @Test
  void connectionProbeValidatesDraftWithoutSavingOrEnablingIt() throws Exception {
    var before = modelSettings.view();
    var requests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    var responseMode = new java.util.concurrent.atomic.AtomicInteger();
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/",
        exchange -> {
          var input = json.readTree(exchange.getRequestBody());
          requests.add(input);
          assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
              .isEqualTo("Bearer unsaved-probe-key");
          Object response =
              exchange.getRequestURI().getPath().endsWith("embeddings")
                  ? Map.of("data", List.of(Map.of("index", 0, "embedding", List.of(0.1, 0.2, 0.3))))
                  : Map.of(
                      "results",
                      List.of(
                          Map.of("index", 0, "relevance_score", 0.9),
                          Map.of("index", 1, "relevance_score", 0.1)));
          byte[] bytes =
              json.writeValueAsBytes(
                  responseMode.get() == 2 ? Map.of("unexpected", true) : response);
          exchange.sendResponseHeaders(responseMode.get() == 1 ? 401 : 200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
      for (String kind : List.of("embedding", "rerank")) {
        var draft = new LinkedHashMap<String, Object>();
        draft.put("enabled", false);
        draft.put("baseUrl", url);
        draft.put("model", "test-model");
        draft.put("apiKey", "unsaved-probe-key");
        draft.put(
            "consentTarget",
            url + "/" + (kind.equals("embedding") ? "embeddings" : "rerank") + "\ntest-model");
        var tested = result(body(post("/api/knowledge/retrieval/test/" + kind), draft));
        assertThat(tested.path("ok").asBoolean()).isTrue();
        assertThat(tested.path("elapsedMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(tested.path(kind.equals("embedding") ? "dimensions" : "results").asInt())
            .isEqualTo(kind.equals("embedding") ? 3 : 2);
        assertThat(modelSettings.view()).isEqualTo(before);
        assertThat(tested.toString()).doesNotContain("unsaved-probe-key");
        responseMode.set(1);
        mvc.perform(body(post("/api/knowledge/retrieval/test/" + kind), draft))
            .andExpect(status().isBadRequest());
        responseMode.set(2);
        mvc.perform(body(post("/api/knowledge/retrieval/test/" + kind), draft))
            .andExpect(status().isBadRequest());
        responseMode.set(0);
        int count = requests.size();
        draft.put("consentTarget", "old-target");
        mvc.perform(body(post("/api/knowledge/retrieval/test/" + kind), draft))
            .andExpect(status().isBadRequest());
        assertThat(requests).hasSize(count);
      }
      assertThat(requests.getFirst().path("input").get(0).asText())
          .isEqualTo("Dongran connection test.");
      assertThat(requests.get(3).path("query").asText())
          .isEqualTo("Which document describes an apple?");
      assertThat(modelSettings.view()).isEqualTo(before);
    } finally {
      server.stop(0);
    }
  }

  @Autowired com.dongran.work.repository.KnowledgeRepository knowledgeRepository;

  @Test
  void reextractRemovesStaleTextAndVectorsWithoutChangingOriginal() throws Exception {
    var bytes = fixtures.get("scan.pdf");
    var saved =
        result(
            auth(
                multipart("/api/knowledge/upload")
                    .file(new MockMultipartFile("file", "repair.pdf", "application/pdf", bytes))));
    String id = saved.path("id").asText();
    knowledgeRepository.update(
        "repair.pdf",
        "stale glyph garbage",
        "old-checksum",
        com.dongran.work.infrastructure.Database.now(),
        id);
    retrieval.replaceText(id, "repair.pdf", "stale glyph garbage");
    vectorIndex.vector(
        String.valueOf(vectorIndex.chunks(id).getFirst().get("id")),
        new float[] {1, 0},
        "old-model");
    var repaired = result(auth(post("/api/knowledge/" + id + "/reextract")));
    assertThat(repaired.path("extractionStatus").asText()).isEqualTo("needs_ocr");
    assertThat(repaired.path("content").asText()).isEmpty();
    assertThat(vectorIndex.chunks(id)).isEmpty();
    assertThat(knowledgeRepository.sourceBytes(id)).isEqualTo(bytes);
  }

  @Test
  void fragmentsLocateExactTextAndRejectOtherDocumentsOrStaleIds() throws Exception {
    String content = "开头\n" + "重复内容🙂权限说明\n".repeat(220) + "结束";
    String id =
        result(body(post("/api/knowledge"), Map.of("name", "定位资料", "content", content)))
            .path("id")
            .asText();
    var chunks = vectorIndex.chunks(id);
    int previous = -1;
    for (var chunk : chunks) {
      var found = result(auth(get("/api/knowledge/" + id + "/fragments/" + chunk.get("id"))));
      int start = found.path("start").asInt(), end = found.path("end").asInt();
      assertThat(start).isGreaterThan(previous);
      previous = start;
      assertThat(content.substring(start, end)).isEqualTo(chunk.get("content"));
    }
    String chunkId = String.valueOf(chunks.getLast().get("id"));
    String other =
        result(body(post("/api/knowledge"), Map.of("name", "其他资料", "content", "其他文本")))
            .path("id")
            .asText();
    mvc.perform(auth(get("/api/knowledge/" + other + "/fragments/" + chunkId)))
        .andExpect(status().isNotFound());
    result(body(put("/api/knowledge/" + id), Map.of("name", "定位资料", "content", "更新后资料")));
    mvc.perform(auth(get("/api/knowledge/" + id + "/fragments/" + chunkId)))
        .andExpect(status().isNotFound());
  }
}
