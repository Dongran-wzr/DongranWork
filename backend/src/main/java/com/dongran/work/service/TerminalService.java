package com.dongran.work.service;

import com.dongran.work.exception.ApiException;
import com.pty4j.*;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

/** User-operated host PTYs. Deliberately not registered as an Agent tool. */
@Service
public class TerminalService {
  private final ProjectService projects;
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
  private boolean closing;

  private record Profile(String id, String label, String executable, List<String> args) {}

  private static final class Session {
    final String id = UUID.randomUUID().toString(), projectId, directory, profile;
    final PtyProcess process;
    final StringBuilder output = new StringBuilder();
    String name;
    long base;
    volatile boolean finished;
    volatile Integer exitCode;

    Session(String project, String directory, Profile profile, PtyProcess process) {
      this.projectId = project;
      this.directory = directory;
      this.profile = profile.id();
      this.name = profile.label();
      this.process = process;
    }

    synchronized void append(String text) {
      output.append(text);
      if (output.length() > 524288) {
        int trim = output.length() - 524288;
        if (Character.isLowSurrogate(output.charAt(trim))) trim++;
        output.delete(0, trim);
        base += trim;
      }
    }

    synchronized Map<String, Object> view() {
      var row = new LinkedHashMap<String, Object>();
      row.put("id", id);
      row.put("projectId", projectId);
      row.put("directory", directory);
      row.put("profile", profile);
      row.put("name", name);
      row.put("status", finished ? "exited" : "running");
      row.put("exitCode", exitCode);
      return row;
    }

    synchronized Map<String, Object> output(long after) {
      var result = new LinkedHashMap<>(view());
      long end = base + output.length();
      int position = (int) Math.max(0, Math.min(output.length(), after - base));
      result.put("data", output.substring(position));
      result.put("cursor", end);
      result.put("truncated", after < base || after > end);
      return result;
    }
  }

  public TerminalService(ProjectService projects) {
    this.projects = projects;
  }

  private List<Profile> availableProfiles() {
    var profiles = new ArrayList<Profile>();
    if (System.getProperty("os.name").startsWith("Windows")) {
      String system = System.getenv().getOrDefault("SystemRoot", "C:/Windows");
      profiles.add(
          new Profile(
              "powershell",
              "PowerShell",
              system + "/System32/WindowsPowerShell/v1.0/powershell.exe",
              List.of(
                  "-NoLogo",
                  "-NoProfile",
                  "-NoExit",
                  "-Command",
                  "[Console]::InputEncoding = [System.Text.UTF8Encoding]::new(); [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(); $OutputEncoding = [Console]::OutputEncoding")));
      profiles.add(
          new Profile(
              "cmd",
              "Command Prompt",
              system + "/System32/cmd.exe",
              List.of("/D", "/K", "chcp 65001 >nul")));
      String programFiles = System.getenv().getOrDefault("ProgramFiles", "C:/Program Files");
      profiles.add(
          new Profile(
              "pwsh", "PowerShell 7", programFiles + "/PowerShell/7/pwsh.exe", List.of("-NoLogo")));
      profiles.add(
          new Profile(
              "git-bash",
              "Git Bash",
              programFiles + "/Git/bin/bash.exe",
              List.of("--login", "-i")));
    } else {
      profiles.add(new Profile("bash", "Bash", "/bin/bash", List.of("-i")));
      profiles.add(new Profile("zsh", "Zsh", "/bin/zsh", List.of("-i")));
      profiles.add(new Profile("sh", "Shell", "/bin/sh", List.of("-i")));
    }
    return profiles.stream().filter(p -> Files.isRegularFile(Path.of(p.executable()))).toList();
  }

  public Object profiles() {
    return availableProfiles().stream().map(p -> Map.of("id", p.id(), "label", p.label())).toList();
  }

  public List<Map<String, Object>> list() {
    return sessions.values().stream().map(Session::view).toList();
  }

  public synchronized Map<String, Object> create(
      String project, String profile, int cols, int rows, boolean confirmed) {
    if (!confirmed) throw ApiException.forbidden("本机终端需要用户主动选择；不受 Agent 沙箱限制。");
    if (closing) throw ApiException.conflict("应用正在退出。");
    if (sessions.size() >= 12) throw ApiException.conflict("最多保留 12 个终端，请先关闭不用的会话。");
    size(cols, rows);
    Path root = projects.root(project);
    var selected =
        availableProfiles().stream()
            .filter(p -> p.id().equals(profile))
            .findFirst()
            .orElseThrow(() -> ApiException.bad("所选 Shell 未安装或不可用。"));
    PtyProcess process = null;
    try {
      var command = new ArrayList<String>();
      command.add(selected.executable());
      command.addAll(selected.args());
      var env = new HashMap<>(System.getenv());
      env.keySet().removeIf(k -> k.toUpperCase(Locale.ROOT).startsWith("DONGRAN_"));
      // Let a new PowerShell compute its native module paths rather than inheriting
      // launcher-injected modules (which can trigger publisher prompts before the prompt).
      if (selected.id().equals("powershell") || selected.id().equals("pwsh"))
        env.keySet().removeIf(k -> k.equalsIgnoreCase("PSModulePath"));
      env.put("TERM", "xterm-256color");
      env.put("COLORTERM", "truecolor");
      env.put("TERM_PROGRAM", "DongranWork");
      process =
          new PtyProcessBuilder(command.toArray(String[]::new))
              .setDirectory(root.toString())
              .setEnvironment(env)
              .setInitialColumns(cols)
              .setInitialRows(rows)
              .setUseWinConPty(true)
              .setWindowsAnsiColorEnabled(true)
              .setRedirectErrorStream(true)
              .start();
      var session = new Session(project, root.toString(), selected, process);
      sessions.put(session.id, session);
      readers.submit(() -> read(session));
      return session.view();
    } catch (Exception | LinkageError e) {
      if (process != null) process.destroyForcibly();
      throw ApiException.bad(
          "无法启动交互终端：" + e.getClass().getSimpleName() + "。请确认本机 Shell 和原生 PTY 运行库可用。");
    }
  }

  private void read(Session session) {
    try (var reader =
        new InputStreamReader(session.process.getInputStream(), StandardCharsets.UTF_8)) {
      char[] chars = new char[8192];
      int n;
      while ((n = reader.read(chars)) != -1) session.append(new String(chars, 0, n));
      session.exitCode = session.process.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      session.append("\r\n[终端输出连接已关闭]\r\n");
    } finally {
      session.finished = true;
    }
  }

  private Session required(String id) {
    var session = sessions.get(id);
    if (session == null) throw ApiException.missing("终端会话已关闭。");
    return session;
  }

  public Object poll(Map<String, Long> cursors) {
    if (cursors == null || cursors.size() > 12) throw ApiException.bad("终端请求不合法。");
    var result = new ArrayList<Map<String, Object>>();
    for (var entry : cursors.entrySet()) {
      if (entry.getValue() == null || entry.getValue() < 0) throw ApiException.bad("输出位置不合法。");
      var session = sessions.get(entry.getKey());
      if (session != null) result.add(session.output(entry.getValue()));
      else
        result.add(
            Map.of(
                "id", entry.getKey(), "status", "closed", "data", "", "cursor", entry.getValue()));
    }
    return result;
  }

  public void input(String id, String data) {
    if (data == null || data.length() > 65536) throw ApiException.bad("输入过长。");
    var session = required(id);
    if (session.finished) throw ApiException.conflict("终端进程已退出，请新建会话。");
    synchronized (session.process) {
      try {
        session.process.getOutputStream().write(data.getBytes(StandardCharsets.UTF_8));
        session.process.getOutputStream().flush();
      } catch (IOException e) {
        throw ApiException.conflict("终端输入已关闭。");
      }
    }
  }

  static void size(int cols, int rows) {
    if (cols < 2 || cols > 500 || rows < 1 || rows > 300) throw ApiException.bad("终端尺寸不合法。");
  }

  public void resize(String id, int cols, int rows) {
    size(cols, rows);
    var s = required(id);
    if (!s.finished) s.process.setWinSize(new WinSize(cols, rows));
  }

  public void rename(String id, String name) {
    if (name == null || name.isBlank() || name.length() > 50)
      throw ApiException.bad("会话名称需为 1–50 个字符。");
    var s = required(id);
    synchronized (s) {
      s.name = name.strip();
    }
  }

  public synchronized void close(String id) {
    var session = sessions.get(id);
    if (session != null) {
      terminate(session);
      sessions.remove(id);
    }
  }

  private void terminate(Session session) {
    try {
      ProcessHandle.of(session.process.pid())
          .ifPresent(
              p ->
                  p.descendants()
                      .forEach(
                          child -> {
                            try {
                              child.destroyForcibly();
                            } catch (Exception ignored) {
                            }
                          }));
    } catch (Exception ignored) {
    }
    session.process.destroyForcibly();
    try {
      if (!session.process.waitFor(5, TimeUnit.SECONDS))
        throw ApiException.conflict("终端进程未能退出，请稍后重试。");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    try {
      session.process.getOutputStream().close();
    } catch (IOException ignored) {
    }
    try {
      session.process.getInputStream().close();
    } catch (IOException ignored) {
    }
    session.finished = true;
  }

  public synchronized void closeAll() {
    for (String id : List.copyOf(sessions.keySet())) close(id);
  }

  @PreDestroy
  public synchronized void shutdown() {
    closing = true;
    sessions.values().forEach(this::terminate);
    sessions.clear();
    readers.shutdownNow();
  }
}
