package com.dongran.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.dongran.work.infrastructure.ModelClient;
import com.dongran.work.service.AgentService;
import com.dongran.work.service.ApprovalService;
import com.dongran.work.service.GitService;
import com.dongran.work.service.PreferenceService;
import com.dongran.work.service.ProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AgentGitApprovalIntegrationTest {
  static final Path DATA = temporary();
  @TempDir Path directory;
  @Autowired AgentService agents;
  @Autowired ApprovalService approvals;
  @Autowired PreferenceService preferences;
  @Autowired ProjectService projects;
  @MockBean ModelClient model;
  // Keep the host-execution boundary observable; the real Agent and approval persistence run.
  @MockBean GitService git;
  private String projectId;

  private static Path temporary() {
    try {
      return Files.createTempDirectory("dongran-host-git-approval-");
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("dongran.data-dir", DATA::toString);
    registry.add("dongran.token", () -> "host-git-approval-integration-token");
  }

  @BeforeEach
  void configure() throws Exception {
    preferences.patch(
        Map.of("confirmPlan", false, "commandApproval", false, "automationHooks", List.of()));
    when(model.status()).thenReturn(Map.of("configured", true));
    projectId = projects.open(directory.toString()).id();
  }

  @ParameterizedTest
  @ValueSource(strings = {"git_status", "git_diff"})
  void rejectedHostGitReadNeverReachesGitEvenWhenProjectWritesAreAllowed(String operation)
      throws Exception {
    String taskId = requestGitRead(operation);
    try {
      Map<String, Object> approval = pending(taskId, operation);
      verifyNoInteractions(git);

      approvals.resolve(String.valueOf(approval.get("id")), false);
      completed(taskId);

      verifyNoInteractions(git);
      assertThat(approvals.list(taskId).getFirst().get("status")).isEqualTo("denied");
    } finally {
      agents.cancel(taskId);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"git_status", "git_diff"})
  void approvedHostGitReadExecutesOnlyTheRequestedOperation(String operation) throws Exception {
    when(git.status(anyString())).thenReturn(Map.of("repository", false));
    when(git.diff(anyString())).thenReturn(Map.of("staged", "", "unstaged", ""));
    String taskId = requestGitRead(operation);
    try {
      Map<String, Object> approval = pending(taskId, operation);
      verifyNoInteractions(git);

      approvals.resolve(String.valueOf(approval.get("id")), true);
      completed(taskId);

      if (operation.equals("git_status")) verify(git).status(projectId);
      else verify(git).diff(projectId);
      verifyNoMoreInteractions(git);
      assertThat(approvals.list(taskId).getFirst().get("status")).isEqualTo("approved");
    } finally {
      agents.cancel(taskId);
    }
  }

  private String requestGitRead(String operation) throws Exception {
    AtomicInteger calls = new AtomicInteger();
    when(model.complete(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              if (calls.getAndIncrement() > 0)
                return new ModelClient.Result(
                    "已处理授权结果。", List.of(), Map.of("role", "assistant", "content", "已处理授权结果。"));
              Map<String, Object> call =
                  Map.of(
                      "id",
                      "host-git-call",
                      "type",
                      "function",
                      "function",
                      Map.of("name", operation, "arguments", "{}"));
              return new ModelClient.Result(
                  "",
                  List.of(call),
                  Map.of("role", "assistant", "content", "", "tool_calls", List.of(call)));
            });
    return String.valueOf(
        agents
            .create(Map.of("prompt", "检查项目变更", "projectId", projectId, "mode", "允许项目内修改"))
            .get("id"));
  }

  private Map<String, Object> pending(String taskId, String operation) {
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> assertThat(agents.get(taskId).get("status")).isEqualTo("awaiting_approval"));
    var pending = approvals.list(taskId);
    assertThat(pending).hasSize(1);
    var approval = pending.getFirst();
    assertThat(approval.get("action")).isEqualTo("host_git_read");
    assertThat(approval.get("status")).isEqualTo("pending");
    assertThat(approval.get("arguments"))
        .isEqualTo(
            Map.of(
                "operation",
                operation,
                "directory",
                projects.root(projectId).toString(),
                "reason",
                "此操作在宿主机执行，Git 配置的文件监视器或过滤器可能启动额外程序；不受命令沙箱限制。"));
    return approval;
  }

  private void completed(String taskId) {
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(agents.get(taskId).get("status")).isEqualTo("completed"));
  }
}
