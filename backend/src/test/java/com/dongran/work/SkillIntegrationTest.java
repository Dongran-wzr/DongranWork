package com.dongran.work;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import com.dongran.work.exception.ApiException;
import com.dongran.work.infrastructure.*;
import com.dongran.work.service.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class SkillIntegrationTest {
  static final Path DATA = BackendIntegrationTest.temporary("skills-test");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("dongran.data-dir", DATA::toString);
    r.add("dongran.token", () -> "skill-test-token-0123456789abcdef");
  }

  @Autowired SkillService skills;
  @Autowired SkillResolver resolver;
  @Autowired SkillContextService context;
  @Autowired Database db;
  @Autowired AgentService agents;
  @Autowired ProjectService projects;
  @Autowired PreferenceService prefs;
  @MockBean ModelClient model;
  @MockBean CommandService commands;
  @Autowired ApprovalService approvals;
  String task;

  static String body(String name) {
    return "---\nname: "
        + name
        + "\ndescription: 代码审查 code review 安全缺陷\nversion: 1.0.0\n---\n\n先读真实文件，再提供审查结果。";
  }

  @BeforeEach
  void setup() {
    db.jdbc.update("DELETE FROM skills");
    prefs.patch(Map.of("confirmPlan", false, "memoryAutoCapture", false, "contextWindow", 32768));
    task = Database.id();
    db.jdbc.update(
        "INSERT INTO tasks(id,title,prompt,status,mode,created_at,updated_at) VALUES(?,?,?,'completed','修改前询问',?,?)",
        task,
        "fixture",
        "fixture",
        Database.now(),
        Database.now());
  }

  String id(Map<String, Object> row) {
    return (String) row.get("id");
  }

  byte[] zip(Map<String, byte[]> files) throws Exception {
    var out = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(out)) {
      for (var e : files.entrySet()) {
        zip.putNextEntry(new ZipEntry(e.getKey()));
        zip.write(e.getValue());
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }

  @Test
  void directoryImportCopiesResourcesAndProjectOverridesGlobalEvenDisabled() throws Exception {
    Path source = Files.createTempDirectory(DATA, "source");
    Files.writeString(source.resolve("SKILL.md"), body("review"));
    Files.createDirectories(source.resolve("references"));
    Files.writeString(source.resolve("references/style.md"), "原始引用");
    var global = skills.importDirectory(null, source.toString());
    assertThat(global.get("root_path")).isNotEqualTo(source.toString());
    String project = projects.open(Files.createTempDirectory(DATA, "project").toString()).id();
    var local = skills.importDirectory(project, source.toString());
    assertThat(skills.effective(project))
        .extracting(r -> r.get("id"))
        .containsExactly(local.get("id"));
    assertThatThrownBy(() -> skills.allowed("other-project", id(local)))
        .isInstanceOf(ApiException.class);
    skills.set(id(local), false, true);
    assertThat(skills.effective(project)).isEmpty();
    skills.delete(id(local));
    assertThat(skills.effective(project))
        .extracting(r -> r.get("id"))
        .containsExactly(global.get("id"));
    assertThat(Files.exists(source.resolve("SKILL.md"))).isTrue();
  }

  @Test
  void zipRejectsTraversalDuplicatesOversizeAndUnixLinks() throws Exception {
    for (var files :
        List.of(
            Map.of(
                "../escape", new byte[1], "SKILL.md", body("bad").getBytes(StandardCharsets.UTF_8)),
            Map.of(
                "SKILL.md",
                body("bad").getBytes(StandardCharsets.UTF_8),
                "large.txt",
                new byte[200001])))
      assertThatThrownBy(() -> skills.importZip(null, new ByteArrayInputStream(zip(files))))
          .isInstanceOf(ApiException.class);
    var out = new ByteArrayOutputStream();
    try (var z = new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(out)) {
      var link =
          new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("references/link.md");
      var manifest = new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("SKILL.md");
      z.putArchiveEntry(manifest);
      z.write(body("linked-package").getBytes(StandardCharsets.UTF_8));
      z.closeArchiveEntry();
      link.setUnixMode(0120777);
      z.putArchiveEntry(link);
      z.write("/etc/passwd".getBytes(StandardCharsets.UTF_8));
      z.closeArchiveEntry();
    }
    assertThatThrownBy(() -> skills.importZip(null, new ByteArrayInputStream(out.toByteArray())))
        .isInstanceOf(ApiException.class);
    assertThat(skills.list(null)).isEmpty();
  }

  @Test
  void zipWrapperImportsAndEditsUseHashAndChangedDiskRequiresReview() throws Exception {
    var row =
        skills.importZip(
            null,
            new ByteArrayInputStream(
                zip(
                    Map.of(
                        "bundle/SKILL.md",
                        body("review").getBytes(StandardCharsets.UTF_8),
                        "bundle/templates/result.md",
                        "template".getBytes(StandardCharsets.UTF_8)))));
    assertThat(skills.resource(null, id(row), "templates/result.md", 0).toString())
        .contains("template");
    assertThatThrownBy(() -> skills.edit(id(row), body("review"), "stale"))
        .isInstanceOf(ApiException.class);
    Path root = Path.of((String) row.get("root_path"));
    Files.writeString(root.resolve("templates/result.md"), "changed");
    assertThatThrownBy(() -> skills.load(null, id(row))).isInstanceOf(ApiException.class);
    assertThat(skills.detail(id(row)).get("changed")).isEqualTo(true);
    skills.refresh(id(row));
    assertThat(skills.load(null, id(row))).containsKey("hash");
    assertThatThrownBy(() -> skills.resource(null, id(row), "../outside", 0))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void explicitSelectionWorksWhenAutoOffAndUnselectedLoadIsDenied() {
    var row = skills.create(null, body("review"));
    skills.set(id(row), true, false);
    context.select(task, List.of(id(row)), false);
    assertThat(context.instruction(task, "lead", null, "检查代码")).contains("先读真实文件", "用户选择");
    context.requireLoaded(task, "lead", null, id(row));
    assertThatThrownBy(() -> context.requireLoaded(task, "tester", null, id(row)))
        .isInstanceOf(ApiException.class);
    var another = skills.create(null, body("second"));
    assertThatThrownBy(() -> context.load(task, "lead", null, id(another), "自动"))
        .isInstanceOf(ApiException.class);
    assertThat(resolver.candidates(null, "代码审查 安全缺陷"))
        .extracting(r -> r.get("id"))
        .doesNotContain(row.get("id"));
  }

  @Test
  void autoCandidatesAreMetadataOnlyAndAgentLoadsRealSkill() throws Exception {
    var row = skills.create(null, body("code-review"));
    String skill = id(row);
    assertThat(resolver.candidates(null, "代码审查 code review"))
        .hasSize(1)
        .allMatch(r -> !r.containsKey("content"));
    when(model.status()).thenReturn(Map.of("configured", true));
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    when(model.complete(any(), any(), any()))
        .thenAnswer(
            inv -> {
              List<Map<String, Object>> messages = inv.getArgument(0);
              if (calls.getAndIncrement() == 0) {
                assertThat(db.json(messages))
                    .contains("可能相关的技能目录", "code-review")
                    .doesNotContain("先读真实文件");
                var call =
                    Map.<String, Object>of(
                        "id",
                        "skill-load",
                        "type",
                        "function",
                        "function",
                        Map.of("name", "load_skill", "arguments", db.json(Map.of("id", skill))));
                return new ModelClient.Result(
                    "",
                    List.of(call),
                    Map.of("role", "assistant", "content", "", "tool_calls", List.of(call)));
              }
              assertThat(db.json(messages)).contains("先读真实文件");
              return new ModelClient.Result(
                  "这里是审查说明", List.of(), Map.of("role", "assistant", "content", "这里是审查说明"));
            });
    String run =
        (String)
            agents.create(Map.of("prompt", "解释代码审查 code review 流程", "mode", "修改前询问")).get("id");
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(agents.get(run).get("status")).isEqualTo("completed"));
    assertThat(
            db.jdbc.queryForObject(
                "SELECT count(*) FROM skill_loads WHERE task_id=?", Integer.class, run))
        .isEqualTo(1);
  }

  @Test
  void projectScanRegistersSharedSkillsAndInvalidMetadataDoesNotInstall() throws Exception {
    Path directory = Files.createTempDirectory(DATA, "shared");
    String project = projects.open(directory.toString()).id();
    Path skill = directory.resolve(".dongran/skills/shared-review");
    Files.createDirectories(skill);
    Files.writeString(skill.resolve("SKILL.md"), body("shared-review"));
    assertThat(skills.scan(project)).hasSize(1);
    assertThat(skills.scan(project)).hasSize(1);
    assertThatThrownBy(() -> skills.create(null, "# missing metadata"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void scriptVersionAndScopeAreVerifiedBeforeExecution() throws Exception {
    String project =
        projects.open(Files.createTempDirectory(DATA, "script-project").toString()).id();
    var row =
        skills.importZip(
            project,
            new ByteArrayInputStream(
                zip(
                    Map.of(
                        "SKILL.md",
                        body("script").getBytes(StandardCharsets.UTF_8),
                        "scripts/check.py",
                        "print('fixture')".getBytes(StandardCharsets.UTF_8)))));
    assertThatThrownBy(() -> skills.scriptFiles(project, id(row), "scripts/check.py", "stale"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                skills.scriptFiles(project, id(row), "SKILL.md", (String) row.get("content_hash")))
        .isInstanceOf(ApiException.class);
    assertThat(
            skills.scriptFiles(
                project, id(row), "scripts/check.py", (String) row.get("content_hash")))
        .containsKey("scripts/check.py");
  }

  @Test
  void scriptApprovalIsSingleAndDenialNeverStartsExecution() throws Exception {
    String project =
        projects.open(Files.createTempDirectory(DATA, "approval-project").toString()).id();
    var row =
        skills.importZip(
            project,
            new ByteArrayInputStream(
                zip(
                    Map.of(
                        "SKILL.md",
                        body("approval-skill").getBytes(StandardCharsets.UTF_8),
                        "scripts/check.py",
                        "print('fixture')".getBytes(StandardCharsets.UTF_8)))));
    prefs.patch(Map.of("confirmPlan", true));
    for (boolean approve : List.of(false, true)) {
      reset(commands);
      when(commands.startSkill(any(), any(), any(), any(), any(), anyInt()))
          .thenReturn("synthetic-run");
      when(commands.await("synthetic-run"))
          .thenReturn(Map.of("status", "completed", "exitCode", 0));
      when(model.status()).thenReturn(Map.of("configured", true));
      var count = new java.util.concurrent.atomic.AtomicInteger();
      when(model.complete(any(), any(), any()))
          .thenAnswer(
              inv -> {
                if (count.getAndIncrement() == 0) {
                  var call =
                      Map.<String, Object>of(
                          "id",
                          "script-approval",
                          "type",
                          "function",
                          "function",
                          Map.of(
                              "name",
                              "run_skill_script",
                              "arguments",
                              db.json(
                                  Map.of(
                                      "id",
                                      id(row),
                                      "path",
                                      "scripts/check.py",
                                      "hash",
                                      row.get("content_hash")))));
                  return new ModelClient.Result(
                      "",
                      List.of(call),
                      Map.of("role", "assistant", "content", "", "tool_calls", List.of(call)));
                }
                return new ModelClient.Result(
                    "实际执行结果见工具记录",
                    List.of(),
                    Map.of("role", "assistant", "content", "实际执行结果见工具记录"));
              });
      String run =
          (String)
              agents
                  .create(
                      Map.of(
                          "projectId",
                          project,
                          "prompt",
                          "运行技能脚本检查项目",
                          "mode",
                          "允许项目内修改",
                          "skillIds",
                          List.of(id(row)),
                          "autoSkills",
                          false))
                  .get("id");
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(10))
          .until(() -> "awaiting_approval".equals(agents.get(run).get("status")));
      var pending = approvals.list(run);
      assertThat(pending).hasSize(1);
      assertThat(pending.getFirst().get("action")).isEqualTo("run_skill_script");
      verify(commands, never()).startSkill(any(), any(), any(), any(), any(), anyInt());
      approvals.resolve((String) pending.getFirst().get("id"), approve);
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(agents.get(run).get("status"))
                      .isEqualTo(approve ? "completed" : "failed"));
      assertThat(approvals.list(run)).hasSize(1);
      verify(commands, times(approve ? 1 : 0))
          .startSkill(any(), any(), any(), any(), any(), anyInt());
    }
  }
}
