package com.dongran.work;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.dongran.work.infrastructure.SandboxExecutor;
import com.dongran.work.service.CommandService;
import com.dongran.work.service.PreferenceService;
import com.dongran.work.service.ProjectService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@EnabledOnOs(OS.WINDOWS)
class SandboxIntegrationTest {
  static final Path DATA = temporary();
  @Autowired SandboxExecutor sandbox;
  @Autowired CommandService commands;
  @Autowired ProjectService projects;
  @Autowired PreferenceService preferences;

  static Path temporary() {
    try {
      return Files.createTempDirectory("dongran-sandbox-api-");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("dongran.data-dir", DATA::toString);
    registry.add("dongran.token", () -> "sandbox-integration-token-0123456789");
  }

  @BeforeEach
  void shell() {
    preferences.patch(Map.of("shell", "PowerShell", "excludedPaths", ".git\n.env"));
  }

  Map<String, Object> finished(String run) {
    await()
        .atMost(Duration.ofSeconds(25))
        .until(() -> !Set.of("queued", "running").contains(commands.get(run).get("status")));
    return commands.get(run);
  }

  static String quoted(Path path) {
    return "'" + path.toString().replace("'", "''") + "'";
  }

  @Test
  void realSandboxUsesSnapshotAndSyncsOnlySuccessfulChanges() throws Exception {
    assertThat(sandbox.status(true).get("available")).isEqualTo(true);
    Path root = temporary();
    Files.writeString(root.resolve("source.txt"), "original");
    Files.writeString(root.resolve(".env"), "local-secret");
    String project = projects.open(root.toString()).id();
    var result =
        finished(
            commands.start(
                project,
                null,
                "if (Test-Path .env) { throw 'secret leaked' }; Set-Content source.txt 'changed'; Write-Output 'sandbox-ok'",
                15));
    assertThat(result.get("status")).as(result.toString()).isEqualTo("completed");
    assertThat(result.get("executionMode")).isEqualTo("sandbox-required");
    assertThat(result.get("syncStatus")).isEqualTo("applied");
    assertThat(result.get("output").toString()).contains("sandbox-ok");
    assertThat(Files.readString(root.resolve("source.txt"))).contains("changed");
    assertThat(Files.readString(root.resolve(".env"))).isEqualTo("local-secret");
    assertThat(Path.of(result.get("workspacePath").toString())).isNotEqualTo(root);

    var failure =
        finished(
            commands.start(project, null, "Set-Content source.txt 'must-not-sync'; exit 7", 15));
    assertThat(failure.get("status")).isEqualTo("failed");
    assertThat(failure.get("syncStatus")).isEqualTo("not-applied");
    assertThat(Files.readString(root.resolve("source.txt"))).contains("changed");
  }

  @Test
  void outsidePrivateFileCannotBeReadOrWritten() throws Exception {
    Path root = temporary(), outside = temporary().resolve("private.txt");
    Files.writeString(outside, "private-canary-content");
    String project = projects.open(root.toString()).id();
    var read = finished(commands.start(project, null, "Get-Content " + quoted(outside), 15));
    assertThat(read.get("status")).as(read.toString()).isEqualTo("failed");
    assertThat(read.get("output").toString()).doesNotContain("private-canary-content");
    var write =
        finished(
            commands.start(project, null, "Set-Content " + quoted(outside) + " 'overwritten'", 15));
    assertThat(write.get("status")).isEqualTo("failed");
    assertThat(Files.readString(outside)).isEqualTo("private-canary-content");
  }

  @Test
  void hostEditsDuringRunPreventWriteback() throws Exception {
    Path root = temporary();
    Files.writeString(root.resolve("source.txt"), "original");
    String project = projects.open(root.toString()).id();
    String run =
        commands.start(
            project, null, "Start-Sleep -Seconds 2; Set-Content source.txt 'agent-edit'", 15);
    await()
        .atMost(Duration.ofSeconds(15))
        .until(() -> "running".equals(commands.get(run).get("status")));
    Files.writeString(root.resolve("source.txt"), "user-edit");
    var result = finished(run);
    assertThat(result.get("status")).as(result.toString()).isEqualTo("failed");
    assertThat(result.get("syncStatus")).isEqualTo("conflict");
    assertThat(Files.readString(root.resolve("source.txt"))).isEqualTo("user-edit");
  }

  @Test
  void cancellationAndTimeoutNeverWriteBack() throws Exception {
    Path root = temporary();
    String project = projects.open(root.toString()).id();
    String run =
        commands.start(
            project, null, "Set-Content cancelled.txt 'in-snapshot'; Start-Sleep -Seconds 30", 40);
    await()
        .atMost(Duration.ofSeconds(15))
        .until(() -> "running".equals(commands.get(run).get("status")));
    commands.cancel(run);
    assertThat(finished(run).get("status")).isEqualTo("cancelled");
    assertThat(commands.get(run).get("syncStatus")).isEqualTo("not-applied");
    assertThat(Files.exists(root.resolve("cancelled.txt"))).isFalse();
    var timeout =
        finished(
            commands.start(
                project,
                null,
                "Set-Content timeout.txt 'in-snapshot'; Start-Sleep -Seconds 30",
                1));
    assertThat(timeout.get("status")).as(timeout.toString()).isEqualTo("timed_out");
    assertThat(Files.exists(root.resolve("timeout.txt"))).isFalse();
  }

  @Test
  void missingHelperIsFailClosed() {
    var unavailable =
        new SandboxExecutor(new ObjectMapper(), DATA.resolve("missing-helper.exe").toString());
    assertThat(unavailable.status(true).get("available")).isEqualTo(false);
    assertThatThrownBy(unavailable::requireAvailable).hasMessageContaining("未切换为宿主机执行");
  }
}
