package com.dongran.work.infrastructure;

import com.dongran.work.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Trusted broker for the native helper. Never executes the requested argv on the host. */
@Component
public class SandboxExecutor {
  private final ObjectMapper json;
  private final String configuredHelper;
  private volatile Map<String, Object> cached;
  private volatile long checkedAt;

  public SandboxExecutor(ObjectMapper json, @Value("${dongran.sandbox-helper:}") String helper) {
    this.json = json;
    this.configuredHelper = helper;
  }

  Path helper() {
    String name =
        System.getProperty("os.name").startsWith("Windows")
            ? "dongran-sandbox.exe"
            : "dongran-sandbox";
    if (!configuredHelper.isBlank()) return Path.of(configuredHelper).toAbsolutePath().normalize();
    var candidates = new ArrayList<Path>();
    try {
      Path location =
          Path.of(
              SandboxExecutor.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      candidates.add(location.resolveSibling(name));
    } catch (Exception ignored) {
    }
    // Development lookup only; released applications pass an absolute installed helper path.
    Path cwd = Path.of("").toAbsolutePath();
    candidates.add(cwd.resolve("sandbox/target/release").resolve(name));
    candidates.add(cwd.resolve("../sandbox/target/release").normalize().resolve(name));
    return candidates.stream()
        .filter(Files::isRegularFile)
        .findFirst()
        .orElse(candidates.getLast());
  }

  public synchronized Map<String, Object> status(boolean refresh) {
    if (!refresh && cached != null && System.nanoTime() - checkedAt < TimeUnit.SECONDS.toNanos(15))
      return cached;
    var result = new LinkedHashMap<String, Object>();
    result.put("protocolVersion", 1);
    result.put("available", false);
    result.put("platform", System.getProperty("os.name"));
    result.put("backend", "unavailable");
    result.put("networkIsolation", false);
    result.put("reason", "未找到本机沙箱执行助手，请构建助手或检查安装包。");
    result.put(
        "policy",
        Map.of(
            "mode",
            "required",
            "network",
            "deny",
            "memoryMb",
            512,
            "maxProcesses",
            32,
            "workspace",
            "snapshot",
            "sync",
            "on-success-conflict-check"));
    result.put("scope", "agent-commands");
    Process process = null;
    try {
      Path binary = helper();
      if (Files.isRegularFile(binary) && !Files.isSymbolicLink(binary)) {
        process = builder(binary, "probe").start();
        Process probe = process;
        var output =
            CompletableFuture.supplyAsync(
                () -> {
                  try {
                    return readLine(probe.getInputStream(), 32768);
                  } catch (IOException e) {
                    throw new CompletionException(e);
                  }
                });
        if (!process.waitFor(12, TimeUnit.SECONDS)) throw new IOException("沙箱自检超时");
        JsonNode info = json.readTree(output.get(1, TimeUnit.SECONDS));
        boolean available =
            process.exitValue() == 0
                && info.path("protocolVersion").asInt() == 1
                && info.path("available").asBoolean()
                && info.path("networkIsolation").asBoolean();
        result.put("available", available);
        result.put("networkIsolation", available);
        result.put("backend", info.path("backend").asText("unavailable"));
        result.put("reason", available ? "" : info.path("reason").asText("沙箱自检未通过。"));
      }
    } catch (Exception e) {
      result.put("reason", "沙箱自检失败：" + e.getMessage());
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
    }
    cached = Collections.unmodifiableMap(result);
    checkedAt = System.nanoTime();
    return cached;
  }

  public void requireAvailable() {
    var state = status(false);
    if (!Boolean.TRUE.equals(state.get("available")))
      throw ApiException.conflict(String.valueOf(state.get("reason")) + " 命令已阻止，未切换为宿主机执行。");
  }

  public Run launch(String id, Path workspace, List<String> argv, int timeout) throws IOException {
    requireAvailable();
    if (!Path.of(argv.getFirst()).isAbsolute()) throw new IOException("Shell 必须为绝对路径");
    Process process = builder(helper(), "run").start();
    var run = new Run(process);
    try {
      var request = new LinkedHashMap<String, Object>();
      request.put("protocolVersion", 1);
      request.put("executionId", id);
      request.put("workspace", workspace.toRealPath().toString());
      request.put("argv", argv);
      request.put("timeoutSeconds", timeout);
      request.put("memoryMb", 512);
      request.put("maxProcesses", 32);
      request.put("network", "deny");
      request.put("readRoots", toolRoots());
      run.send(json.writeValueAsString(request));
      return run;
    } catch (Exception e) {
      run.forceStop();
      throw e;
    }
  }

  private List<String> toolRoots() {
    var roots = new LinkedHashSet<String>();
    roots.add(Path.of(System.getProperty("java.home")).toAbsolutePath().normalize().toString());
    for (String key : List.of("JAVA_HOME", "MAVEN_HOME", "M2_HOME")) {
      String value = System.getenv(key);
      if (value == null || value.isBlank()) continue;
      try {
        Path root = Path.of(value).toRealPath();
        if (root.getNameCount() > 1 && !root.equals(Path.of(System.getProperty("user.home"))))
          roots.add(root.toString());
      } catch (IOException ignored) {
      }
    }
    return List.copyOf(roots);
  }

  private ProcessBuilder builder(Path binary, String operation) {
    var builder = new ProcessBuilder(binary.toString(), operation);
    var inherited = new HashMap<>(builder.environment());
    builder.environment().clear();
    for (String key :
        List.of("SystemRoot", "WINDIR", "TEMP", "TMP", "PATH", "LANG", "LC_ALL", "HOME"))
      if (inherited.containsKey(key)) builder.environment().put(key, inherited.get(key));
    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    return builder;
  }

  public Result consume(Run run, Consumer<String> output, Consumer<String> started)
      throws IOException {
    return consume(run.process.getInputStream(), output, started);
  }

  Result consume(InputStream input, Consumer<String> output, Consumer<String> started)
      throws IOException {
    Result result = null;
    boolean hasStarted = false;
    boolean hasError = false;
    String line;
    while ((line = readLine(input, 65536)) != null) {
      if (result != null) throw new IOException("沙箱退出后仍返回消息");
      JsonNode event;
      try (var parser = json.createParser(line)) {
        event = json.readTree(parser);
        if (event == null || !event.isObject() || parser.nextToken() != null)
          throw new IOException("沙箱消息必须是单个 JSON 对象");
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new IOException("沙箱消息不是有效的 JSON 对象");
      }
      if (!event.path("type").isTextual()) throw new IOException("沙箱消息类型不合法");
      switch (event.get("type").textValue()) {
        case "started" -> {
          if (hasStarted || hasError) throw new IOException("沙箱开始事件顺序不合法");
          if (!event.path("backend").isTextual() || event.get("backend").textValue().isBlank())
            throw new IOException("沙箱开始事件缺少执行后端");
          hasStarted = true;
          started.accept(event.get("backend").textValue());
        }
        case "output" -> {
          if (!hasStarted) throw new IOException("沙箱未开始执行即返回输出");
          if (!event.path("text").isTextual()) throw new IOException("沙箱输出必须为文本");
          output.accept(event.get("text").textValue());
        }
        case "error" -> {
          if (event.has("message") && !event.get("message").isTextual())
            throw new IOException("沙箱错误消息必须为文本");
          String message = event.path("message").asText("沙箱执行失败。");
          output.accept("\n[沙箱] " + message + "\n");
          hasError = true;
        }
        case "exit" -> {
          String status = event.path("status").asText();
          if (!Set.of("completed", "failed", "timed_out", "cancelled").contains(status))
            throw new IOException("非法沙箱退出状态");
          if (!event.path("exitCode").isInt()) throw new IOException("沙箱退出码必须为整数");
          int code = event.get("exitCode").intValue();
          if (!hasStarted && !(hasError && "failed".equals(status)))
            throw new IOException("沙箱缺少开始事件");
          if (hasError && !"failed".equals(status)) throw new IOException("沙箱已报告错误，不能返回其他退出状态");
          if ("completed".equals(status) && code != 0) throw new IOException("沙箱退出状态不一致");
          result = new Result(status, code);
        }
        default -> throw new IOException("不支持的沙箱消息");
      }
    }
    if (result == null) throw new IOException("沙箱未返回执行结果，命令未视为成功");
    return result;
  }

  static String readLine(InputStream input, int limit) throws IOException {
    var buffer = new ByteArrayOutputStream();
    int b;
    while ((b = input.read()) != -1 && b != '\n') {
      if (buffer.size() >= limit) throw new IOException("沙箱消息超过限制");
      buffer.write(b);
    }
    if (b == -1) {
      if (buffer.size() == 0) return null;
      throw new IOException("沙箱消息在换行前被截断");
    }
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(buffer.toByteArray()))
        .toString();
  }

  public record Result(String status, Integer exitCode) {}

  public static final class Run {
    private final Process process;
    private final BufferedWriter input;

    private Run(Process process) {
      this.process = process;
      this.input =
          new BufferedWriter(
              new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    }

    public Process process() {
      return process;
    }

    private synchronized void send(String message) throws IOException {
      input.write(message);
      input.newLine();
      input.flush();
    }

    public void cancel() {
      try {
        send("{\"type\":\"cancel\"}");
      } catch (IOException ignored) {
      }
    }

    public void forceStop() {
      try {
        input.close();
      } catch (IOException ignored) {
      }
      process.destroyForcibly();
    }

    public void closeInput() {
      try {
        input.close();
      } catch (IOException ignored) {
      }
    }
  }
}
