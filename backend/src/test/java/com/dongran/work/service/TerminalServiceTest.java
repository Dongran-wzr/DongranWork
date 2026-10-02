package com.dongran.work.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TerminalServiceTest {
  @TempDir Path root;
  TerminalService terminals;
  boolean windows = System.getProperty("os.name").startsWith("Windows");

  @BeforeEach
  void setup() throws Exception {
    var projects = mock(ProjectService.class);
    when(projects.root("project")).thenReturn(root);
    terminals = new TerminalService(projects);
    Files.createDirectories(root.resolve("sub"));
  }

  @AfterEach
  void close() {
    terminals.shutdown();
  }

  String create() {
    return String.valueOf(
        terminals.create("project", windows ? "powershell" : "bash", 100, 24, true).get("id"));
  }

  @SuppressWarnings("unchecked")
  String output(String id) {
    return String.valueOf(
        ((List<Map<String, Object>>) terminals.poll(Map.of(id, 0L))).getFirst().get("data"));
  }

  void waitFor(String id, String marker) {
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(output(id)).contains(marker));
  }

  @Test
  void hostRequiresExplicitSelectionAndValidSize() {
    assertThatThrownBy(() -> terminals.create("project", "powershell", 80, 24, false))
        .hasMessageContaining("主动选择");
    assertThatThrownBy(() -> terminals.create("project", "powershell", 0, 24, true))
        .hasMessageContaining("尺寸");
    assertThat(terminals.list()).isEmpty();
  }

  @Test
  void realPtyRetainsDirectoryEnvironmentAndInteractiveInput() throws Exception {
    String id = create();
    terminals.input(id, windows ? "cd sub\r" : "cd sub\r");
    terminals.input(id, windows ? "$env:PTY_TEST = 'retained'\r" : "export PTY_TEST=retained\r");
    terminals.input(
        id,
        windows
            ? "Write-Output ('CURRENT_' + (Split-Path -Leaf (Get-Location)))\r"
            : "printf 'CURRENT_%s\\n' \"${PWD##*/}\"\r");
    waitFor(id, "CURRENT_sub");
    terminals.input(
        id,
        windows
            ? "Write-Output ('VALUE_' + $env:PTY_TEST)\r"
            : "printf 'VALUE_%s\\n' \"$PTY_TEST\"\r");
    waitFor(id, "VALUE_retained");
    terminals.input(
        id, windows ? "$reply=Read-Host 'Your name'\r" : "read -p 'Your name: ' reply\r");
    waitFor(id, "Your name");
    terminals.input(id, "terminal-user\r");
    terminals.input(
        id, windows ? "Write-Output ('INPUT_' + $reply)\r" : "printf 'INPUT_%s\\n' \"$reply\"\r");
    waitFor(id, "INPUT_terminal-user");
    terminals.resize(id, 120, 32);
    terminals.rename(id, "验收会话");
    assertThat(terminals.list().getFirst().get("name")).isEqualTo("验收会话");
    String second = create();
    terminals.input(
        second,
        windows
            ? "Write-Output ('OTHER_' + [string]::IsNullOrEmpty($env:PTY_TEST))\r"
            : "printf 'OTHER_%s\\n' \"${PTY_TEST:-empty}\"\r");
    waitFor(second, windows ? "OTHER_True" : "OTHER_empty");
    terminals.close(id);
    assertThatThrownBy(() -> terminals.input(id, "x")).hasMessageContaining("已关闭");
    assertThat(terminals.list()).hasSize(1);
  }

  @Test
  void ctrlCInterruptsCommandWithoutClosingShell() throws Exception {
    String id = create();
    terminals.input(
        id, windows ? "Write-Output ('START_'+'READY')\r" : "printf 'START_%s\\n' READY\r");
    waitFor(id, "START_READY");
    terminals.input(id, windows ? "Start-Sleep -Seconds 30\r" : "sleep 30\r");
    Thread.sleep(500);
    terminals.input(id, "\u0003");
    Thread.sleep(250);
    terminals.input(
        id, windows ? "Write-Output ('AFTER_'+'INTERRUPT')\r" : "printf 'AFTER_%s\\n' INTERRUPT\r");
    waitFor(id, "AFTER_INTERRUPT");
    terminals.input(id, "exit\r");
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> assertThat(terminals.list().getFirst().get("status")).isEqualTo("exited"));
  }
}
