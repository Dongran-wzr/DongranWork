package com.dongran.work.service;

import com.dongran.work.exception.ApiException;
import com.dongran.work.infrastructure.Database;
import com.dongran.work.infrastructure.SandboxExecutor;
import com.dongran.work.repository.CommandRepository;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

@Service
public class CommandService {
  private final CommandRepository repository;
  private final Database db;
  private final ProjectService projects;
  private final PreferenceService preferences;
  private final SandboxExecutor sandbox;
  private final SandboxWorkspaceService workspaces;
  private final ConcurrentMap<String, SandboxExecutor.Run> sandboxRuns = new ConcurrentHashMap<>();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Semaphore slots = new Semaphore(4);
  private final ConcurrentMap<String, Process> processes = new ConcurrentHashMap<>();
  private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
  private final ConcurrentMap<String, Object> commandLocks = new ConcurrentHashMap<>();
  private volatile boolean closing;

  public CommandService(
      CommandRepository repository,
      Database db,
      ProjectService projects,
      PreferenceService preferences,
      SandboxExecutor sandbox,
      SandboxWorkspaceService workspaces) {
    this.repository = repository;
    this.db = db;
    this.projects = projects;
    this.preferences = preferences;
    this.sandbox = sandbox;
    this.workspaces = workspaces;
  }

  public Map<String, Object> get(String id) {
    return repository.findRequired(id);
  }

  public String start(String projectId, String taskId, String command, int timeout) {
    if (closing) throw ApiException.conflict("应用正在退出。");
    if (command.isBlank() || command.length() > 4000 || timeout < 1 || timeout > 3600)
      throw ApiException.bad("命令或超时不合法。");
    sandbox.requireAvailable();
    projects.root(projectId);
    String id = Database.id();
    repository.insertQueued(id, projectId, taskId, command, Database.now(), Database.now());
    commandLocks.put(id, new Object());
    executor.submit(() -> execute(id, projectId, command, timeout));
    return id;
  }

  private List<String> shell(String command) {
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    if (!windows) return List.of("/bin/sh", "-c", command);
    if (preferences.string("shell", "PowerShell").equals("Git Bash")) {
      for (String base :
          List.of(
              System.getenv().getOrDefault("ProgramFiles", "C:/Program Files"),
              System.getenv().getOrDefault("LOCALAPPDATA", "C:/Users/Public") + "/Programs")) {
        Path bash = Path.of(base, "Git", "bin", "bash.exe");
        if (java.nio.file.Files.isRegularFile(bash)) return List.of(bash.toString(), "-c", command);
      }
      throw ApiException.bad("未找到 Git Bash，请安装 Git 或选择其他 Shell。");
    }
    if (preferences.string("shell", "PowerShell").equals("Command Prompt"))
      return List.of(
          Path.of(System.getenv().getOrDefault("SystemRoot", "C:/Windows"), "System32", "cmd.exe")
              .toString(),
          "/d",
          "/s",
          "/c",
          command);
    return List.of(
        Path.of(
                System.getenv().getOrDefault("SystemRoot", "C:/Windows"),
                "System32",
                "WindowsPowerShell",
                "v1.0",
                "powershell.exe")
            .toString(),
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        "$ErrorActionPreference = 'Stop'; New-PSDrive -Name DongranTask -PSProvider FileSystem -Root $env:DONGRAN_SANDBOX_WORKSPACE -Scope Global -ErrorAction Stop | Out-Null; Set-Location 'DongranTask:\\' -ErrorAction Stop; [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(); "
            + command
            + "; if ($null -ne $LASTEXITCODE) { exit $LASTEXITCODE }");
  }

  private void execute(String id, String projectId, String command, int timeout) {
    boolean acquired = false;
    SandboxExecutor.Run run = null;
    SandboxWorkspaceService.Snapshot snapshot = null;
    String backend = "native";
    var output = new StringBuilder();
    long[] lastFlush = {0};
    java.util.function.Consumer<String> append =
        chunk -> {
          synchronized (output) {
            output.append(chunk);
            if (output.length() > 262144) output.delete(0, output.length() - 262144);
            if (System.nanoTime() - lastFlush[0] > 150_000_000) {
              repository.updateOutput(output.toString(), Database.now(), id);
              lastFlush[0] = System.nanoTime();
            }
          }
        };
    try {
      slots.acquire();
      acquired = true;
      if (closing || cancelled.contains(id)) return;
      snapshot = workspaces.prepare(id, projectId);
      backend = String.valueOf(sandbox.status(false).get("backend"));
      String workspace = snapshot.workspace().toString();
      repository.sandbox(id, backend, workspace, "pending");
      if (closing || cancelled.contains(id)) {
        repository.sandbox(id, backend, workspace, "not-applied");
        return;
      }
      run = sandbox.launch(id, snapshot.workspace(), shell(command), timeout);
      sandboxRuns.put(id, run);
      if (closing || cancelled.contains(id)) run.cancel();
      SandboxExecutor.Run active = run;
      var reader =
          executor.submit(
              () ->
                  sandbox.consume(
                      active,
                      append,
                      name -> {
                        synchronized (commandLocks.get(id)) {
                          if (!cancelled.contains(id)) {
                            repository.sandbox(id, name, workspace, "pending");
                            repository.markRunning(Database.now(), id);
                          }
                        }
                      }));
      boolean done = run.process().waitFor(timeout + 15L, TimeUnit.SECONDS);
      if (!done) {
        run.cancel();
        if (!run.process().waitFor(3, TimeUnit.SECONDS)) run.forceStop();
      }
      SandboxExecutor.Result result;
      try {
        result = reader.get(4, TimeUnit.SECONDS);
      } catch (Exception e) {
        reader.cancel(true);
        if (!done) result = new SandboxExecutor.Result("timed_out", null);
        else throw e;
      }
      synchronized (commandLocks.get(id)) {
        String status =
            cancelled.contains(id) || closing ? "cancelled" : !done ? "timed_out" : result.status();
        if ("completed".equals(status) && run.process().exitValue() != 0)
          throw new IOException("沙箱助手异常退出，结果未回写");
        if ("completed".equals(status)) {
          try {
            var sync = workspaces.sync(snapshot);
            repository.sandbox(id, backend, workspace, sync.status());
            if (sync.files() > 0) append.accept("\n[沙箱] 已回写 " + sync.files() + " 个文件变更。\n");
          } catch (Exception e) {
            repository.sandbox(id, backend, workspace, "conflict");
            append.accept("\n[沙箱] 回写停止：" + e.getMessage() + "\n");
            status = "failed";
          }
        } else repository.sandbox(id, backend, workspace, "not-applied");
        synchronized (output) {
          repository.updateOutput(output.toString(), Database.now(), id);
        }
        repository.finish(status, result.exitCode(), Database.now(), id);
      }
    } catch (InterruptedException e) {
      Thread.interrupted();
      repository.markCancelled(Database.now(), id);
      if (snapshot != null)
        repository.sandbox(id, backend, snapshot.workspace().toString(), "not-applied");
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      append.accept(
          "\n无法完成沙箱命令："
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
              + "\n");
      synchronized (output) {
        if (cancelled.contains(id) || closing) {
          repository.updateOutput(output.toString(), Database.now(), id);
          repository.markCancelled(Database.now(), id);
        } else repository.markFailed(output.toString(), Database.now(), id);
      }
      if (snapshot != null)
        repository.sandbox(id, backend, snapshot.workspace().toString(), "not-applied");
    } finally {
      if (run != null) {
        if (run.process().isAlive()) {
          run.cancel();
          run.forceStop();
        }
        run.closeInput();
      }
      sandboxRuns.remove(id);
      cancelled.remove(id);
      commandLocks.remove(id);
      if (acquired) slots.release();
    }
  }

  public Map<String, Object> await(String id) throws InterruptedException {
    while (true) {
      var run = get(id);
      if (!Set.of("queued", "running").contains(run.get("status"))) return run;
      Thread.sleep(150);
    }
  }

  public void cancel(String id) {
    get(id);
    Object lock = commandLocks.get(id);
    if (lock == null) return;
    synchronized (lock) {
      if (!Set.of("queued", "running").contains(get(id).get("status"))) return;
      cancelled.add(id);
      SandboxExecutor.Run run = sandboxRuns.get(id);
      if (run != null) {
        run.cancel();
        executor.submit(
            () -> {
              try {
                if (!run.process().waitFor(3, TimeUnit.SECONDS)) run.forceStop();
              } catch (InterruptedException e) {
                run.forceStop();
                Thread.currentThread().interrupt();
              }
            });
      }
      repository.cancelActive(Database.now(), id);
    }
  }

  public void cancelTask(String taskId) {
    repository.findActiveByTask(taskId).forEach(r -> cancel(String.valueOf(r.get("id"))));
  }

  public static void terminate(Process process) {
    process
        .descendants()
        .forEach(
            child -> {
              try {
                child.destroyForcibly();
              } catch (Exception ignored) {
              }
            });
    process.destroyForcibly();
  }

  public Map<String, Object> direct(Path directory, List<String> arguments, int timeout) {
    Process process = null;
    String directId = Database.id();
    try {
      var builder =
          new ProcessBuilder(arguments).directory(directory.toFile()).redirectErrorStream(true);
      builder.environment().put("GIT_TERMINAL_PROMPT", "0");
      builder.environment().remove("DONGRAN_TOKEN");
      builder.environment().remove("DONGRAN_MODEL_API_KEY");
      if (closing) throw ApiException.conflict("应用正在退出。");
      process = builder.start();
      processes.put(directId, process);
      if (closing) {
        terminate(process);
        throw ApiException.conflict("应用正在退出。");
      }
      Process running = process;
      var output =
          executor.submit(
              () -> {
                try (var stream = running.getInputStream()) {
                  var buffer = new ByteArrayOutputStream();
                  byte[] chunk = new byte[4096];
                  int n;
                  while ((n = stream.read(chunk)) != -1)
                    if (buffer.size() < 1_000_000)
                      buffer.write(chunk, 0, Math.min(n, 1_000_000 - buffer.size()));
                  return buffer.toString(StandardCharsets.UTF_8);
                }
              });
      if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
        terminate(process);
        throw ApiException.conflict("操作超时。");
      }
      return Map.of("exitCode", process.exitValue(), "output", output.get(3, TimeUnit.SECONDS));
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw ApiException.bad("无法执行外部程序，请确认已安装 Git 或相应工具。");
    } finally {
      processes.remove(directId);
      if (process != null && process.isAlive()) terminate(process);
    }
  }

  @PreDestroy
  void close() {
    closing = true;
    sandboxRuns.values().forEach(SandboxExecutor.Run::cancel);
    processes.values().forEach(CommandService::terminate);
    executor.shutdownNow();
    try {
      executor.awaitTermination(3, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
