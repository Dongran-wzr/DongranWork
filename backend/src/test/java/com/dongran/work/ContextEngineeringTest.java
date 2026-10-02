package com.dongran.work;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.dongran.work.exception.ApiException;
import com.dongran.work.infrastructure.*;
import com.dongran.work.repository.MemoryRepository;
import com.dongran.work.service.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class ContextEngineeringTest {
  static final Path DATA = BackendIntegrationTest.temporary("context-test");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("dongran.data-dir", DATA::toString);
    r.add("dongran.token", () -> "context-test-token-0123456789abcdef");
  }

  @Autowired AgentService agents;
  @Autowired ProjectService projects;
  @Autowired Database db;
  @Autowired ContextEngine context;
  @Autowired ContextDraftService drafts;
  @Autowired MemoryRepository memories;
  @Autowired MemoryCaptureService capture;
  @Autowired PreferenceService preferences;
  @MockBean ModelClient model;
  String task;

  @BeforeEach
  void setup() {
    db.jdbc.update("DELETE FROM memories");
    preferences.patch(
        Map.of(
            "memoryEnabled",
            true,
            "memoryAutoCapture",
            false,
            "contextWindow",
            8192,
            "contextOutputReserve",
            1024,
            "contextAutoCompact",
            true));
    task = Database.id();
    db.jdbc.update(
        "INSERT INTO tasks(id,title,prompt,status,mode,created_at,updated_at) VALUES(?,?,?,'running','agent',?,?)",
        task,
        "fixture",
        "fixture",
        Database.now(),
        Database.now());
    context.begin(task, "实现功能", "execute");
  }

  long user(String content) {
    db.jdbc.update(
        "INSERT INTO messages(task_id,role,agent,content,created_at) VALUES(?,'user','lead',?,?)",
        task,
        content,
        Database.now());
    return db.jdbc.queryForObject("SELECT max(id) FROM messages WHERE task_id=?", Long.class, task);
  }

  Map<String, Object> memory(String project, String title, String content, boolean active) {
    return memories.propose(project, title, content, task, 0, content, "automatic", active);
  }

  ModelClient.Result answer(Object payload) {
    String s = db.json(payload);
    return new ModelClient.Result(s, List.of(), Map.of("role", "assistant", "content", s));
  }

  @Test
  void compactionPreservesConstraintsAndLatestAndArchivesWholeToolGroups() {
    var conversation = new ArrayList<Map<String, Object>>();
    conversation.add(Map.of("role", "system", "content", "项目助手"));
    conversation.add(Map.of("role", "user", "content", "必须保持 main 分支。"));
    conversation.add(
        Map.of(
            "role",
            "assistant",
            "content",
            "x".repeat(25000),
            "tool_calls",
            List.of(Map.of("id", "old-call"))));
    conversation.add(Map.of("role", "tool", "tool_call_id", "old-call", "content", "done"));
    conversation.add(Map.of("role", "user", "content", "继续修复终端布局"));
    var result = context.prepare(task, "lead", null, "终端", conversation, List.of(), 0);
    assertThat(db.json(result))
        .contains("必须保持 main 分支", "继续修复终端布局", "structured-extractive")
        .doesNotContain("old-call");
    assertThat(
            db.jdbc.queryForObject(
                "SELECT document FROM context_evidence WHERE task_id=? AND kind='history-archive'",
                String.class,
                task))
        .contains("old-call");
    assertThat(ContextEngine.estimate(db.json(result))).isLessThan(8192 - 1024 - 409);
  }

  @Test
  void mandatoryOversizeStopsWithoutCuttingUserRequest() {
    var input =
        List.<Map<String, Object>>of(
            Map.of("role", "system", "content", "system"),
            Map.of("role", "user", "content", "用户需求".repeat(5000)));
    assertThatThrownBy(() -> context.prepare(task, "lead", null, "", input, List.of(), 0))
        .isInstanceOf(ApiException.class);
    assertThat(input.get(1).get("content")).isEqualTo("用户需求".repeat(5000));
  }

  @Test
  void memoryRecallHonorsScopeStatusPinAndProjectOverride() {
    var global = memory(null, "语言", "Java Spring Boot", true);
    var project = memory("project-a", "语言", "Java 21", true);
    memory("project-b", "禁止泄漏", "Java other project", true);
    memory(null, "待审", "Java candidate", false);
    memories.pin((String) project.get("id"), true);
    var selected = context.selectMemories("project-a", "Java");
    assertThat(selected).extracting(m -> m.get("id")).containsExactly(project.get("id"));
    memories.change((String) project.get("id"), "archived", 2);
    assertThat(context.selectMemories("project-a", "Java"))
        .extracting(m -> m.get("id"))
        .containsExactly(global.get("id"));
    memories.change((String) global.get("id"), "deleted", 1);
    assertThat(context.selectMemories("project-a", "Java")).isEmpty();
  }

  @Test
  void staleLegacySettingsCannotResurrectDeletedMemory() {
    var m = memory(null, "习惯", "使用中文回答", true);
    var stale = memories.legacy();
    memories.change((String) m.get("id"), "deleted", 1);
    assertThatThrownBy(() -> memories.replace(stale)).isInstanceOf(ApiException.class);
    assertThat(memories.legacy()).isEmpty();
  }

  @Test
  void explicitCaptureNeedsNoModelAndRejectsSecrets() {
    user("记住以后统一使用中文回答");
    capture.explicit(task, null, "记住以后统一使用中文回答");
    capture.explicit(task, null, "记住：api_key=super-secret-key");
    assertThat(memories.legacy()).hasSize(1);
    assertThat(capture.existingExplicit(task, Map.of("content", "以后统一使用中文回答"))).isNotNull();
    verifyNoInteractions(model);
  }

  @Test
  void automaticCaptureIsOptInAndGroundedCandidatesRequireReview() throws Exception {
    long source = user("项目必须采用 Java 21，统一使用中文注释。");
    capture.capture(task, "project-a");
    verifyNoInteractions(model);
    preferences.patch(Map.of("memoryAutoCapture", true));
    var valid =
        Map.of(
            "title",
            "Java版本",
            "content",
            "项目必须采用 Java 21",
            "quote",
            "项目必须采用 Java 21",
            "scope",
            "project",
            "sourceMessageId",
            source);
    var fake =
        Map.of(
            "title",
            "虚构",
            "content",
            "统一使用 Python 开发",
            "quote",
            "统一使用 Python 开发",
            "scope",
            "project",
            "sourceMessageId",
            source);
    when(model.complete(any(), any(), any()))
        .thenReturn(answer(Map.of("memories", List.of(valid, fake))));
    capture.capture(task, "project-a");
    capture.capture(task, "project-a");
    assertThat(memories.rows()).hasSize(1);
    assertThat(memories.legacy()).isEmpty();
    var row = memories.rows().getFirst();
    memories.change((String) row.get("id"), "active", 1);
    assertThat(context.selectMemories("project-a", "Java")).hasSize(1);
    assertThatThrownBy(() -> memories.change((String) row.get("id"), "deleted", 1))
        .isInstanceOf(ApiException.class);
    memories.change((String) row.get("id"), "rejected", 2);
    capture.capture(task, "project-a");
    assertThat(memories.rows()).hasSize(1);
    assertThat(context.selectMemories("project-a", "Java")).isEmpty();
  }

  @Test
  void evidenceIsolationAndCallLedgerPreventDuplicateExecution() {
    String evidence = context.evidence(task, "developer", "file", "secret");
    assertThatThrownBy(() -> context.readEvidence(task, "tester", evidence, 0, 100))
        .isInstanceOf(ApiException.class);
    assertThat(context.readEvidence(task, "lead", evidence, 0, 100).get("content"))
        .isEqualTo("secret");
    String execution =
        context.startCall(task, "lead", "call1", "run_command", Map.of("command", "echo hi"));
    context.endCall(execution, "uncertain", Map.of());
    assertThatThrownBy(() -> context.startCall(task, "lead", "call1", "run_command", Map.of()))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void partialDraftsNeverWriteFilesAndSequenceIsStrict() {
    Path path = DATA.resolve("never-written.txt");
    var d = (Map<?, ?>) drafts.begin(task, path.toString(), "missing");
    String id = (String) d.get("draftId");
    drafts.append(task, id, 0, "first part");
    assertThatThrownBy(() -> drafts.append(task, id, 0, "duplicate"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> drafts.get("other-task", id)).isInstanceOf(ApiException.class);
    assertThat(Files.exists(path)).isFalse();
    assertThat(drafts.get(task, id).get("content")).isEqualTo("first part");
  }

  @Test
  void strictArgumentsRejectPartialDuplicateTrailingAndWrongTypes() {
    var definitions =
        List.<Map<String, Object>>of(
            Map.of(
                "function",
                Map.of(
                    "name",
                    "write_file",
                    "parameters",
                    Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("path", Map.of("type", "string")),
                        "required",
                        List.of("path"),
                        "additionalProperties",
                        false))));
    for (String args :
        List.of(
            "{", "{\"path\":\"a\",\"path\":\"b\"}", "{\"path\":\"a\"}{}", "{\"path\":1}", "{}")) {
      var call =
          Map.<String, Object>of(
              "id", "one", "function", Map.of("name", "write_file", "arguments", args));
      assertThatThrownBy(() -> ToolCallValidator.arguments(db, call, definitions))
          .isInstanceOf(ApiException.class);
    }
  }

  @Test
  void draftToolsCommitCompleteContentWithWhitespaceIntact() throws Exception {
    preferences.patch(
        Map.of("confirmPlan", false, "automationHooks", List.of(), "contextWindow", 32768));
    when(model.status()).thenReturn(Map.of("configured", true));
    Path directory = Files.createTempDirectory(DATA, "draft-project");
    String project = projects.open(directory.toString()).id();
    String first = "  first line\n", second = "\n  second line\n";
    var step = new java.util.concurrent.atomic.AtomicInteger();
    when(model.complete(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              List<Map<String, Object>> conversation = invocation.getArgument(0);
              int n = step.getAndIncrement();
              String name;
              Map<String, Object> args;
              if (n == 0) {
                name = "begin_file_draft";
                args = Map.of("path", "fixture.txt");
              } else if (n < 4) {
                var tool =
                    conversation.stream()
                        .filter(m -> "tool".equals(m.get("role")))
                        .findFirst()
                        .orElseThrow();
                String draft =
                    String.valueOf(db.object((String) tool.get("content")).get("draftId"));
                if (n < 3) {
                  name = "append_file_draft";
                  args = Map.of("id", draft, "sequence", n - 1, "content", n == 1 ? first : second);
                } else {
                  name = "commit_file_draft";
                  args =
                      Map.of(
                          "id", draft, "chunks", 2, "characters", first.length() + second.length());
                }
              } else return answer("已完成");
              var call =
                  Map.<String, Object>of(
                      "id",
                      "draft-call-" + n,
                      "type",
                      "function",
                      "function",
                      Map.of("name", name, "arguments", db.json(args)));
              return new ModelClient.Result(
                  "",
                  List.of(call),
                  Map.of("role", "assistant", "content", "", "tool_calls", List.of(call)));
            });
    String id =
        (String)
            agents
                .create(
                    Map.of("projectId", project, "prompt", "创建 fixture.txt 文件", "mode", "允许项目内修改"))
                .get("id");
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(agents.get(id).get("status")).isEqualTo("completed"));
    assertThat(Files.readString(directory.resolve("fixture.txt"))).isEqualTo(first + second);
    assertThat(
            db.jdbc.queryForObject(
                "SELECT status FROM context_drafts WHERE task_id=?", String.class, id))
        .isEqualTo("committed");
  }

  @Test
  void legacyPreferenceMigrationPreservesBothMemoryScopes() throws Exception {
    try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
          connection, new org.springframework.core.io.ClassPathResource("db/V001__initial.sql"));
      var entries =
          List.of(
              Map.of(
                  "id",
                  "global-fixture",
                  "title",
                  "全局偏好",
                  "content",
                  "中文回答",
                  "createdAt",
                  Database.now(),
                  "updatedAt",
                  Database.now()),
              Map.of(
                  "id",
                  "project-fixture",
                  "projectId",
                  "fixture-project",
                  "title",
                  "项目约定",
                  "content",
                  "Java 21",
                  "createdAt",
                  Database.now(),
                  "updatedAt",
                  Database.now()));
      try (var insert =
          connection.prepareStatement("INSERT INTO preferences VALUES('memoryEntries',?)")) {
        insert.setString(1, db.json(entries));
        insert.executeUpdate();
      }
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
          connection,
          new org.springframework.core.io.ClassPathResource("db/V009__context_memory.sql"));
      try (var statement = connection.createStatement();
          var rows =
              statement.executeQuery("SELECT count(*),sum(project_id IS NULL) FROM memories")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(2);
        assertThat(rows.getInt(2)).isEqualTo(1);
      }
    }
  }
}
