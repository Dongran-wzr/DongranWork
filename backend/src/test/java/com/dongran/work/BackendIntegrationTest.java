package com.dongran.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dongran.work.infrastructure.ModelClient;
import com.dongran.work.service.AgentService;
import com.dongran.work.service.PreferenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class BackendIntegrationTest {
  static final String TOKEN = "integration-test-token-0123456789abcdef";
  static final Path DATA = temporary("dongran-api-test");
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired AgentService agents;
  @Autowired PreferenceService preferences;
  @Autowired com.dongran.work.infrastructure.SandboxExecutor sandbox;
  @MockBean ModelClient model;
  @MockBean com.dongran.work.service.WebToolsService web;

  static Path temporary(String prefix) {
    try {
      return Files.createTempDirectory(prefix);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("dongran.data-dir", DATA::toString);
    r.add("dongran.token", () -> TOKEN);
  }

  @BeforeEach
  void configure() throws Exception {
    when(model.status()).thenReturn(Map.of("configured", true));
    when(model.complete(any(), any(), any())).thenReturn(answer("真实测试响应"));
    preferences.patch(Map.of("confirmPlan", false, "automationHooks", List.of()));
  }

  ModelClient.Result answer(String text) {
    return new ModelClient.Result(text, List.of(), Map.of("role", "assistant", "content", text));
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

  JsonNode result(MockHttpServletRequestBuilder b) throws Exception {
    return json.readTree(
        mvc.perform(b)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray());
  }

  String project(Path path) throws Exception {
    return result(body(post("/api/projects"), Map.of("path", path.toString()))).get("id").asText();
  }

  @Test
  void requiresAuthenticationAndProtectsSessionBootstrap() throws Exception {
    mvc.perform(
            get("/api/settings")
                .header("Host", "127.0.0.1:3210")
                .with(
                    r -> {
                      r.setLocalPort(3210);
                      return r;
                    }))
        .andExpect(status().isUnauthorized());
    mvc.perform(auth(post("/api/session")).header("Origin", "https://evil.example"))
        .andExpect(status().isForbidden());
    mvc.perform(auth(post("/api/session")).header("Sec-Fetch-Site", "cross-site"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/session")
                .header("Host", "127.0.0.1:3210")
                .with(
                    r -> {
                      r.setLocalPort(3210);
                      return r;
                    }))
        .andExpect(status().isForbidden());
    mvc.perform(auth(post("/api/session")).header("Origin", "http://127.0.0.1:3210"))
        .andExpect(status().isOk())
        .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")));
    mvc.perform(
            post("/api/session")
                .header("Host", "evil.example:3210")
                .header("X-Dongran-Client", "desktop")
                .with(
                    r -> {
                      r.setLocalPort(3210);
                      return r;
                    }))
        .andExpect(status().isForbidden());
  }

  @Test
  void profilePersistsAndInvalidPatchRollsBack() throws Exception {
    result(body(patch("/api/account"), Map.of("accountNickname", "后端测试")));
    assertThat(
            result(auth(get("/api/bootstrap"))).path("settings").path("accountNickname").asText())
        .isEqualTo("后端测试");
    mvc.perform(body(patch("/api/account"), Map.of("accountNickname", "")))
        .andExpect(status().isBadRequest());
    assertThat(result(auth(get("/api/account"))).path("accountNickname").asText())
        .isEqualTo("后端测试");
    mvc.perform(body(patch("/api/settings"), Map.of("apiKey", "never-store")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void projectReopenReturnsSameIdentity() throws Exception {
    Path path = temporary("dongran-project");
    assertThat(project(path)).isEqualTo(project(path));
    mvc.perform(body(post("/api/projects"), Map.of("path", ""))).andExpect(status().isBadRequest());
  }

  @Test
  void fileWriteUsesApprovalAndOptimisticLockAndBlocksTraversal() throws Exception {
    Path path = temporary("dongran-files");
    String id = project(path);
    String endpoint = "/api/projects/" + id + "/file";
    mvc.perform(body(put(endpoint), Map.of("path", "doc.md", "content", "first")))
        .andExpect(status().isForbidden());
    result(body(put(endpoint), Map.of("path", "doc.md", "content", "first", "confirmed", true)));
    JsonNode read = result(auth(get(endpoint).param("path", "doc.md")));
    mvc.perform(
            body(
                put(endpoint),
                Map.of(
                    "path",
                    "doc.md",
                    "content",
                    "stale",
                    "expectedSha256",
                    "wrong",
                    "confirmed",
                    true)))
        .andExpect(status().isConflict());
    result(
        body(
            put(endpoint),
            Map.of(
                "path",
                "doc.md",
                "content",
                "",
                "expectedSha256",
                read.get("sha256").asText(),
                "confirmed",
                true)));
    assertThat(Files.readString(path.resolve("doc.md"))).isEmpty();
    mvc.perform(auth(get(endpoint).param("path", "../outside"))).andExpect(status().isForbidden());
    mvc.perform(auth(get(endpoint).param("path", ".env"))).andExpect(status().isForbidden());
    mvc.perform(body(put(endpoint), Map.of("path", "doc.md", "confirmed", true)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void knowledgeCrudAndChineseSearch() throws Exception {
    JsonNode doc =
        result(body(post("/api/knowledge"), Map.of("name", "需求验收", "content", "这里记录项目的权限管理验收标准。")));
    String id = doc.get("id").asText();
    assertThat(result(auth(get("/api/knowledge").param("query", "权限管理"))).toString()).contains(id);
    result(body(put("/api/knowledge/" + id), Map.of("name", "修改后的资料", "content", "新的验收内容")));
    result(auth(delete("/api/knowledge/" + id)));
    mvc.perform(auth(get("/api/knowledge/" + id))).andExpect(status().isNotFound());
  }

  @Test
  void scheduleReplacementRejectsStaleRevision() throws Exception {
    JsonNode initial = result(auth(get("/api/schedules")));
    result(
        body(
            post("/api/schedules"),
            Map.of(
                "name",
                "测试计划",
                "prompt",
                "检查需求",
                "scope",
                "global",
                "projectName",
                "",
                "frequency",
                "daily",
                "time",
                "09:00",
                "weekdays",
                List.of(),
                "date",
                "",
                "enabled",
                false)));
    mvc.perform(
            body(
                put("/api/schedules"),
                Map.of("tasks", List.of(), "revision", initial.get("revision").asText())))
        .andExpect(status().isConflict());
    JsonNode current = result(auth(get("/api/schedules")));
    assertThat(current.get("tasks").size()).isGreaterThan(0);
  }

  @Test
  void taskRunsPersistsMessagesAndCanBeContinued() throws Exception {
    JsonNode created = result(body(post("/api/tasks"), Map.of("prompt", "分析需求", "mode", "仅规划")));
    String id = created.get("id").asText();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(agents.get(id).get("status")).isEqualTo("completed"));
    assertThat(agents.get(id).get("messages").toString()).contains("真实测试响应");
    result(body(post("/api/tasks/" + id + "/messages"), Map.of("prompt", "补充验收标准")));
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(agents.get(id).get("status")).isEqualTo("completed"));
  }

  @Test
  void modelToolWriteWaitsForUserApproval() throws Exception {
    Path path = temporary("dongran-agent");
    String projectId = project(path);
    AtomicInteger calls = new AtomicInteger();
    when(model.complete(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              if (calls.getAndIncrement() > 0) return answer("文件已保存。");
              Map<String, Object> call =
                  Map.of(
                      "id",
                      "call-write",
                      "type",
                      "function",
                      "function",
                      Map.of(
                          "name",
                          "write_file",
                          "arguments",
                          "{\"path\":\"requirements.md\",\"content\":\"验收标准\"}"));
              return new ModelClient.Result(
                  "",
                  List.of(call),
                  Map.of("role", "assistant", "content", "", "tool_calls", List.of(call)));
            });
    String id =
        result(
                body(
                    post("/api/tasks"),
                    Map.of("prompt", "生成需求", "projectId", projectId, "mode", "修改前询问")))
            .get("id")
            .asText();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> assertThat(agents.get(id).get("status")).isEqualTo("awaiting_approval"));
    assertThat(Files.exists(path.resolve("requirements.md"))).isFalse();
    JsonNode task = result(auth(get("/api/tasks/" + id)));
    result(
        body(
            post("/api/approvals/" + task.path("approvals").get(0).path("id").asText()),
            Map.of("approved", true)));
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(agents.get(id).get("status")).isEqualTo("completed"));
    assertThat(Files.readString(path.resolve("requirements.md"))).isEqualTo("验收标准");
  }

  @Test
  void cancellationExpiresPendingApproval() throws Exception {
    preferences.patch(Map.of("confirmPlan", true));
    String projectId = project(temporary("dongran-plan-cancel"));
    when(model.complete(any(), any(), any())).thenReturn(plannedWrite());
    String id =
        result(
                body(
                    post("/api/tasks"),
                    Map.of("prompt", "写入需求文件", "projectId", projectId, "mode", "修改前询问")))
            .get("id")
            .asText();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> assertThat(agents.get(id).get("status")).isEqualTo("awaiting_approval"));
    result(auth(post("/api/tasks/" + id + "/cancel")));
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(agents.get(id).get("status")).isEqualTo("cancelled");
              assertThat(agents.get(id).get("approvals").toString())
                  .doesNotContain("status=pending");
            });
  }

  @Test
  void commandsRequireConfirmationAndReturnRealOutput() throws Exception {
    String id = project(temporary("dongran-command"));
    mvc.perform(
            body(
                post("/api/commands"),
                Map.of("projectId", id, "command", "echo dongran-command-ok")))
        .andExpect(status().isForbidden());
    if (!Boolean.TRUE.equals(sandbox.status(false).get("available"))) {
      mvc.perform(
              body(
                  post("/api/commands"),
                  Map.of("projectId", id, "command", "echo dongran-command-ok", "confirmed", true)))
          .andExpect(status().isConflict());
      return;
    }
    String run =
        result(
                body(
                    post("/api/commands"),
                    Map.of(
                        "projectId", id, "command", "echo dongran-command-ok", "confirmed", true)))
            .get("id")
            .asText();
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> {
              JsonNode output = result(auth(get("/api/commands/" + run)));
              assertThat(output.path("status").asText()).isEqualTo("completed");
              assertThat(output.path("output").asText()).contains("dongran-command-ok");
            });
  }

  @Test
  void planningModeRejectsUnexpectedModelToolCalls() throws Exception {
    Path directory = temporary("dongran-planning");
    String projectId = project(directory);
    Map<String, Object> call =
        Map.of(
            "id",
            "unexpected",
            "type",
            "function",
            "function",
            Map.of(
                "name",
                "write_file",
                "arguments",
                "{\"path\":\"forbidden.md\",\"content\":\"must not write\"}"));
    when(model.complete(any(), any(), any()))
        .thenReturn(
            new ModelClient.Result(
                "",
                List.of(call),
                Map.of("role", "assistant", "content", "", "tool_calls", List.of(call))));
    String id =
        result(
                body(
                    post("/api/tasks"),
                    Map.of("prompt", "只做规划", "projectId", projectId, "mode", "仅规划")))
            .get("id")
            .asText();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(agents.get(id).get("status")).isEqualTo("failed"));
    assertThat(Files.exists(directory.resolve("forbidden.md"))).isFalse();
    assertThat(agents.get(id).get("approvals").toString()).isEqualTo("[]");
  }

  @Test
  void manualScheduleRunCreatesHistory() throws Exception {
    JsonNode schedule =
        result(
            body(
                post("/api/schedules"),
                Map.of(
                    "name",
                    "手动验证",
                    "prompt",
                    "检查需求",
                    "scope",
                    "global",
                    "projectName",
                    "",
                    "frequency",
                    "daily",
                    "time",
                    "09:00",
                    "weekdays",
                    List.of(),
                    "date",
                    "",
                    "enabled",
                    false)));
    String scheduleId = schedule.get("id").asText();
    String taskId = result(auth(post("/api/schedules/" + scheduleId + "/run"))).get("id").asText();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(agents.get(taskId).get("status")).isEqualTo("completed"));
    JsonNode runs = result(auth(get("/api/schedules/" + scheduleId + "/runs")));
    assertThat(runs.get(0).get("task_id").asText()).isEqualTo(taskId);
    assertThat(runs.get(0).get("taskStatus").asText()).isEqualTo("completed");
  }

  @Test
  void memoryRespectsProjectAndGlobalScopes() throws Exception {
    String id = project(temporary("dongran-memory"));
    result(body(post("/api/memories"), Map.of("title", "全局约定", "content", "使用中文")));
    result(
        body(post("/api/memories"), Map.of("title", "项目约定", "content", "运行项目测试", "projectId", id)));
    JsonNode visible =
        result(auth(get("/api/memories").param("projectId", id).param("effective", "true")));
    assertThat(visible.toString()).contains("全局约定", "项目约定");
    preferences.patch(
        Map.of(
            "memoryProjectPreferences",
            List.of(Map.of("projectId", id, "enabled", true, "inheritGlobal", false))));
    JsonNode scoped =
        result(auth(get("/api/memories").param("projectId", id).param("effective", "true")));
    assertThat(scoped.toString()).contains("项目约定").doesNotContain("全局约定");
  }

  @Test
  void invalidSettingsAreRejectedAtomically() throws Exception {
    preferences.patch(Map.of("theme", "light"));
    var patch = new java.util.LinkedHashMap<String, Object>();
    patch.put("theme", "dark");
    patch.put("commandApproval", "false");
    mvc.perform(body(patch("/api/settings"), patch)).andExpect(status().isBadRequest());
    assertThat(preferences.string("theme", "")).isEqualTo("light");
    mvc.perform(body(patch("/api/settings"), Map.of("parallelAgents", 99)))
        .andExpect(status().isBadRequest());
    var position = new java.util.LinkedHashMap<String, Object>();
    position.put("petCustomPosition", null);
    result(body(patch("/api/settings"), position));
  }

  private ModelClient.Result plannedWrite() {
    Map<String, Object> call =
        Map.of(
            "id",
            "planned-write",
            "type",
            "function",
            "function",
            Map.of(
                "name",
                "write_file",
                "arguments",
                "{\"path\":\"planned.md\",\"content\":\"验收标准\"}"));
    return new ModelClient.Result(
        "", List.of(call), Map.of("role", "assistant", "content", "", "tool_calls", List.of(call)));
  }

  @Test
  void ordinaryAnswersDoNotRequestPlanConfirmationOrRunTwice() throws Exception {
    preferences.patch(Map.of("confirmPlan", true));
    AtomicInteger calls = new AtomicInteger();
    when(model.complete(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              calls.incrementAndGet();
              return answer("这是普通问题的回答。");
            });
    String id =
        result(body(post("/api/tasks"), Map.of("prompt", "你有什么知识？", "mode", "修改前询问")))
            .get("id")
            .asText();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(agents.get(id).get("status")).isEqualTo("completed"));
    assertThat((List<?>) agents.get(id).get("approvals")).isEmpty();
    assertThat(calls.get()).isEqualTo(1);
    assertThat(
            ((List<Map<String, Object>>) agents.get(id).get("messages"))
                .stream().filter(m -> !"route".equals(m.get("role"))).toList())
        .hasSize(2);
  }

  @Test
  void planConfirmationApprovesConcreteWriteExactlyOnce() throws Exception {
    preferences.patch(Map.of("confirmPlan", true));
    Path directory = temporary("dongran-concrete-plan");
    String projectId = project(directory);
    AtomicInteger calls = new AtomicInteger();
    when(model.complete(any(), any(), any()))
        .thenAnswer(invocation -> calls.getAndIncrement() == 0 ? plannedWrite() : answer("文件已保存。"));
    String id =
        result(
                body(
                    post("/api/tasks"),
                    Map.of("prompt", "写入需求文件", "projectId", projectId, "mode", "修改前询问")))
            .get("id")
            .asText();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> assertThat(agents.get(id).get("status")).isEqualTo("awaiting_approval"));
    JsonNode task = result(auth(get("/api/tasks/" + id)));
    assertThat(task.path("approvals").size()).isEqualTo(1);
    JsonNode approval = task.path("approvals").get(0);
    assertThat(approval.path("action").asText()).isEqualTo("confirm_plan");
    assertThat(approval.path("arguments").path("operation").asText()).isEqualTo("write_file");
    assertThat(Files.exists(directory.resolve("planned.md"))).isFalse();
    result(body(post("/api/approvals/" + approval.path("id").asText()), Map.of("approved", true)));
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(agents.get(id).get("status")).isEqualTo("completed"));
    assertThat((List<?>) agents.get(id).get("approvals")).hasSize(1);
    assertThat(Files.readString(directory.resolve("planned.md"))).isEqualTo("验收标准");
  }

  @Test
  void rejectedPlanDoesNotWriteOrAskAgainForRepeatedToolRequest() throws Exception {
    preferences.patch(Map.of("confirmPlan", true));
    Path directory = temporary("dongran-denied-plan");
    String projectId = project(directory);
    AtomicInteger calls = new AtomicInteger();
    when(model.complete(any(), any(), any()))
        .thenAnswer(invocation -> calls.getAndIncrement() < 2 ? plannedWrite() : answer("已停止执行。"));
    String id =
        result(
                body(
                    post("/api/tasks"),
                    Map.of("prompt", "写入需求文件", "projectId", projectId, "mode", "修改前询问")))
            .get("id")
            .asText();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> assertThat(agents.get(id).get("status")).isEqualTo("awaiting_approval"));
    JsonNode approval = result(auth(get("/api/tasks/" + id))).path("approvals").get(0);
    result(body(post("/api/approvals/" + approval.path("id").asText()), Map.of("approved", false)));
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(agents.get(id).get("status")).isEqualTo("failed"));
    assertThat((List<?>) agents.get(id).get("approvals")).hasSize(1);
    assertThat(Files.exists(directory.resolve("planned.md"))).isFalse();
  }

  private ModelClient.Result readTool(String name, String arguments) {
    var call =
        Map.of(
            "id",
            "fixture-read",
            "type",
            "function",
            "function",
            Map.of("name", name, "arguments", arguments));
    return new ModelClient.Result(
        "", List.of(call), Map.of("role", "assistant", "content", "", "tool_calls", List.of(call)));
  }

  @Test
  void knowledgeToolPersistsClickableSources() throws Exception {
    String doc =
        result(body(post("/api/knowledge"), Map.of("name", "引用样例", "content", "引用定位独有词")))
            .path("id")
            .asText();
    when(model.complete(any(), any(), any())).thenReturn(answer("已找到资料"));
    String id =
        result(body(post("/api/tasks"), Map.of("prompt", "知识库里引用定位独有词是什么", "mode", "修改前询问")))
            .path("id")
            .asText();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(agents.get(id).get("status")).isEqualTo("completed"));
    var task = result(auth(get("/api/tasks/" + id)));
    assertThat(task.path("messages").toString()).contains("knowledge", doc, "chunkId", "引用定位独有词");
  }

  @Test
  void webToolsAreExposedOnlyWhenNetworkIsAllowed() throws Exception {
    for (boolean allowed : List.of(false, true)) {
      preferences.patch(Map.of("allowNetwork", allowed));
      AtomicInteger calls = new AtomicInteger();
      when(web.search("public fixture")).thenReturn(Map.of("results", List.of()));
      when(model.complete(any(), any(), any()))
          .thenAnswer(
              invocation -> {
                String definitions = String.valueOf((Object) invocation.getArgument(1));
                assertThat(definitions.contains("web_search")).isEqualTo(allowed);
                assertThat(definitions.contains("web_fetch")).isEqualTo(allowed);
                return calls.getAndIncrement() == 0
                    ? readTool("web_search", "{\"query\":\"public fixture\"}")
                    : answer("完成");
              });
      String id =
          result(body(post("/api/tasks"), Map.of("prompt", "搜索公开资料", "mode", "修改前询问")))
              .path("id")
              .asText();
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () ->
                  assertThat(agents.get(id).get("status"))
                      .isEqualTo(allowed ? "completed" : "failed"));
    }
    org.mockito.Mockito.verify(web, org.mockito.Mockito.times(1)).search("public fixture");
    preferences.patch(Map.of("allowNetwork", false));
  }

  @Test
  void followupUrlIsFetchedEvenWhenModelNeverCallsTools() throws Exception {
    preferences.patch(Map.of("allowNetwork", true));
    when(model.complete(any(), any(), any())).thenReturn(answer("仅文本模型回答"));
    when(web.fetch("https://example.com"))
        .thenReturn(Map.of("url", "https://example.com", "content", "Example Domain"));
    String id =
        result(body(post("/api/tasks"), Map.of("prompt", "你好", "mode", "修改前询问")))
            .path("id")
            .asText();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(agents.get(id).get("status")).isEqualTo("completed"));
    result(
        body(post("/api/tasks/" + id + "/messages"), Map.of("prompt", "帮我总结 https://example.com")));
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(agents.get(id).get("status")).isEqualTo("completed"));
    org.mockito.Mockito.verify(web).fetch("https://example.com");
    assertThat(result(auth(get("/api/tasks/" + id))).path("messages").toString())
        .contains("web_fetch");
    preferences.patch(Map.of("allowNetwork", false));
  }

  @Test
  void explicitReadFailureCannotBeMarkedCompleted() throws Exception {
    preferences.patch(Map.of("allowNetwork", true));
    when(web.fetch("https://example.com"))
        .thenThrow(com.dongran.work.exception.ApiException.bad("fixture offline"));
    String id =
        result(
                body(
                    post("/api/tasks"),
                    Map.of("prompt", "总结 https://example.com", "mode", "修改前询问")))
            .path("id")
            .asText();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(agents.get(id).get("status")).isEqualTo("failed"));
    assertThat(agents.get(id).get("error")).isEqualTo("fixture offline");
    preferences.patch(Map.of("allowNetwork", false));
  }
}
