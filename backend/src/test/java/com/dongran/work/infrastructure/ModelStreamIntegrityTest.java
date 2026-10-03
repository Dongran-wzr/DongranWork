package com.dongran.work.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dongran.work.exception.ModelIncompleteException;
import com.dongran.work.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

class ModelStreamIntegrityTest {
  HttpServer server;
  ModelClient client;
  String response;
  Map<String, Object> request;

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          request =
              new ObjectMapper().readValue(exchange.getRequestBody().readAllBytes(), Map.class);
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    var credentials = mock(CredentialService.class);
    when(credentials.get("fixture")).thenReturn("");
    var prefs = mock(PreferenceService.class);
    when(prefs.all()).thenReturn(Map.of());
    client =
        new ModelClient(
            new Database(null, new ObjectMapper()),
            mock(ModelProviderService.class),
            credentials,
            prefs);
  }

  @AfterEach
  void close() {
    client.close();
    server.stop(0);
  }

  ModelClient.Result complete() throws Exception {
    return complete(0, t -> {});
  }

  ModelClient.Result complete(int reserve, java.util.function.Consumer<String> delta)
      throws Exception {
    var cfg =
        new ModelProviderService.RuntimeConfiguration(
            "fixture",
            "fixture",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
            "fixture",
            "fixture",
            0,
            32768,
            1);
    return client.completeWithConfiguration(
        cfg,
        List.of(Map.of("role", "user", "content", "synthetic fixture")),
        List.of(),
        delta,
        reserve);
  }

  String chunk(Object delta, Object reason) throws Exception {
    var choice = new LinkedHashMap<String, Object>();
    choice.put("delta", delta);
    choice.put("finish_reason", reason);
    return "data: "
        + new ObjectMapper().writeValueAsString(Map.of("choices", List.of(choice)))
        + "\n\n";
  }

  String tool(String arguments, boolean first) throws Exception {
    var t = new LinkedHashMap<String, Object>();
    t.put("index", 0);
    var f = new LinkedHashMap<String, Object>();
    f.put("arguments", arguments);
    if (first) {
      t.put("id", "call-fixture");
      f.put("name", "write_file");
    }
    t.put("function", f);
    return chunk(Map.of("tool_calls", List.of(t)), null);
  }

  @Test
  void fragmentedCallIsAssembledOnlyAfterExplicitFinish() throws Exception {
    response =
        tool("{\"path\":", true)
            + tool("\"demo.txt\"}", false)
            + chunk(Map.of(), "tool_calls")
            + "data: [DONE]\n\n";
    var r = complete();
    assertThat(r.calls()).hasSize(1);
    assertThat(((Map<?, ?>) r.calls().getFirst().get("function")).get("arguments"))
        .isEqualTo("{\"path\":\"demo.txt\"}");
  }

  @Test
  void lengthLimitedToolCannotBecomeExecutableEvenWithValidJson() throws Exception {
    response = tool("{}", true) + chunk(Map.of(), "length") + "data: [DONE]\n\n";
    assertThatThrownBy(this::complete).isInstanceOf(ModelIncompleteException.class);
  }

  @Test
  void doneWithoutToolFinishAndUnexpectedEofAreRejected() throws Exception {
    response = tool("{}", true) + "data: [DONE]\n\n";
    assertThatThrownBy(this::complete).isInstanceOf(ModelIncompleteException.class);
    response = tool("{", true);
    assertThatThrownBy(this::complete).isInstanceOf(ModelIncompleteException.class);
  }

  @Test
  void dsmlFragmentsNeverReachDisplayedText() throws Exception {
    response =
        chunk(Map.of("content", "<｜DS"), null)
            + chunk(
                Map.of("content", "ML｜function_calls><｜DSML｜invoke name=\"search_knowledge\">"),
                "stop")
            + "data: [DONE]\n\n";
    var shown = new StringBuilder();
    assertThatThrownBy(() -> complete(0, shown::append))
        .isInstanceOfSatisfying(
            ModelIncompleteException.class, e -> assertThat(e.reason()).isEqualTo("protocol"));
    assertThat(shown).isEmpty();
  }

  @Test
  void outputBudgetActuallyChangesAndMarkdownStillStreams() throws Exception {
    String markdown = "# 实施计划\n" + "分析并验证。".repeat(80);
    response = chunk(Map.of("content", markdown), "stop") + "data: [DONE]\n\n";
    var shown = new StringBuilder();
    assertThat(complete(8192, shown::append).text()).isEqualTo(markdown);
    assertThat(request.get("max_tokens")).isEqualTo(8192);
    assertThat(shown.toString()).isEqualTo(markdown);
    complete(16384, t -> {});
    assertThat(request.get("max_tokens")).isEqualTo(32768 / 3);
  }
}
