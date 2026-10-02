package com.dongran.work.service;

import com.dongran.work.dto.GitOperationRequest;
import com.dongran.work.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Structured data and conservative mutations for the Git tool window. */
@Service
public class GitWorkspaceService {
  private final ProjectService projects;
  private final CommandService commands;

  public GitWorkspaceService(ProjectService projects, CommandService commands) {
    this.projects = projects;
    this.commands = commands;
  }

  private Map<String, Object> run(String id, String... args) {
    Path root = projects.root(id);
    var command =
        new ArrayList<>(
            List.of(
                "git",
                "--literal-pathspecs",
                "-c",
                "safe.directory=" + root,
                "-c",
                "core.quotepath=false",
                "-C",
                root.toString()));
    command.addAll(List.of(args));
    return commands.direct(root, command, 60);
  }

  private String output(String id, String... args) {
    var result = run(id, args);
    if (!Objects.equals(result.get("exitCode"), 0))
      throw ApiException.conflict("Git 操作失败：" + result.get("output"));
    return String.valueOf(result.get("output"));
  }

  private String optional(String id, String... args) {
    var result = run(id, args);
    return Objects.equals(result.get("exitCode"), 0)
        ? String.valueOf(result.get("output")).strip()
        : "";
  }

  private void requireRepositoryRoot(String id) {
    String top = optional(id, "rev-parse", "--show-toplevel");
    if (top.isBlank()) throw ApiException.bad("请先初始化 Git 仓库。");
    try {
      if (!Files.isSameFile(projects.root(id), Path.of(top)))
        throw ApiException.conflict("请将仓库根目录作为项目打开后管理 Git：" + top);
    } catch (java.io.IOException e) {
      throw ApiException.bad("无法访问仓库根目录。");
    }
  }

  public List<Map<String, Object>> changes(String id) {
    requireRepositoryRoot(id);
    String raw = output(id, "status", "--porcelain=v1", "-z", "--untracked-files=all");
    String[] records = raw.split("\0", -1);
    var files = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < records.length; i++) {
      String row = records[i];
      if (row.length() < 4) continue;
      String status = row.substring(0, 2), path = row.substring(3), original = "";
      if (status.indexOf('R') >= 0 || status.indexOf('C') >= 0) {
        if (i + 1 < records.length) original = records[++i];
      }
      boolean conflict = Set.of("DD", "AU", "UD", "UA", "DU", "AA", "UU").contains(status);
      var file = new LinkedHashMap<String, Object>();
      file.put("path", path);
      file.put("originalPath", original);
      file.put("index", status.substring(0, 1));
      file.put("worktree", status.substring(1, 2));
      file.put("untracked", status.equals("??"));
      file.put("staged", status.charAt(0) != ' ' && !status.equals("??"));
      file.put("unstaged", status.charAt(1) != ' ');
      file.put("conflict", conflict);
      files.add(file);
    }
    return files;
  }

  public Map<String, Object> snapshot(String id) {
    boolean repository = !optional(id, "rev-parse", "--show-toplevel").isEmpty();
    if (!repository)
      return Map.of(
          "repository",
          false,
          "branch",
          "",
          "files",
          List.of(),
          "branches",
          List.of(),
          "commits",
          List.of(),
          "remotes",
          List.of(),
          "upstream",
          "",
          "ahead",
          0,
          "behind",
          0);
    String branch = optional(id, "symbolic-ref", "--short", "HEAD");
    String head = optional(id, "rev-parse", "--short", "HEAD");
    String upstream =
        optional(id, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}");
    int ahead = 0, behind = 0;
    if (!upstream.isBlank()) {
      String[] counts =
          optional(id, "rev-list", "--left-right", "--count", "HEAD...@{upstream}").split("\\s+");
      if (counts.length == 2) {
        ahead = Integer.parseInt(counts[0]);
        behind = Integer.parseInt(counts[1]);
      }
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("repository", true);
    result.put("branch", branch);
    result.put("head", head);
    result.put("detached", branch.isBlank());
    result.put("upstream", upstream);
    result.put("ahead", ahead);
    result.put("behind", behind);
    result.put("files", changes(id));
    result.put("branches", branches(id));
    result.put("commits", history(id));
    result.put("remotes", optional(id, "remote").lines().filter(s -> !s.isBlank()).toList());
    return result;
  }

  public List<Map<String, Object>> branches(String id) {
    var result = new ArrayList<Map<String, Object>>();
    String data =
        output(
            id,
            "for-each-ref",
            "--sort=-committerdate",
            "--format=%(refname:short)%09%(HEAD)%09%(objectname:short)%09%(upstream:short)%09%(symref)%09%(refname)",
            "refs/heads",
            "refs/remotes");
    for (String line : data.lines().toList()) {
      String[] fields = line.split("\t", -1);
      if (fields.length < 6 || !fields[4].isBlank()) continue;
      result.add(
          Map.of(
              "name",
              fields[0],
              "current",
              fields[1].equals("*"),
              "hash",
              fields[2],
              "upstream",
              fields[3],
              "remote",
              fields[5].startsWith("refs/remotes/")));
    }
    return result;
  }

  public List<Map<String, Object>> history(String id) {
    if (optional(id, "rev-parse", "--verify", "HEAD").isBlank()) return List.of();
    String data =
        output(
            id,
            "log",
            "--all",
            "--topo-order",
            "-60",
            "--format=%H%x00%h%x00%P%x00%s%x00%an%x00%aI%x00%D%x1e");
    var commits = new ArrayList<Map<String, Object>>();
    for (String row : data.split("\u001e")) {
      String[] fields = row.strip().split("\0", -1);
      if (fields.length < 7) continue;
      commits.add(
          Map.of(
              "hash",
              fields[0],
              "shortHash",
              fields[1],
              "parents",
              fields[2].isBlank() ? List.of() : List.of(fields[2].split(" ")),
              "subject",
              fields[3],
              "author",
              fields[4],
              "date",
              fields[5],
              "refs",
              fields[6]));
    }
    return commits;
  }

  public Map<String, Object> diff(String id, String path, boolean staged) {
    requireRepositoryRoot(id);
    Path file = projects.resolve(id, path, false);
    String version = fingerprint(id, path);
    String text;
    boolean untracked =
        changes(id).stream()
            .anyMatch(
                row -> path.equals(row.get("path")) && Boolean.TRUE.equals(row.get("untracked")));
    if (untracked) {
      if (!Files.isRegularFile(file)) throw ApiException.bad("此文件无法预览。");
      try {
        if (Files.size(file) > 512_000)
          return Map.of(
              "path",
              path,
              "diff",
              "",
              "binary",
              true,
              "fingerprint",
              "",
              "message",
              "文件超过 500 KB，请在编辑器中查看。");
        byte[] bytes = Files.readAllBytes(file);
        for (byte b : bytes)
          if (b == 0)
            return Map.of(
                "path",
                path,
                "diff",
                "",
                "binary",
                true,
                "fingerprint",
                "",
                "message",
                "二进制文件，无文本预览。");
        String content = new String(bytes, StandardCharsets.UTF_8);
        text =
            "--- /dev/null\n+++ b/"
                + path
                + "\n@@ -0,0 +1,"
                + content.lines().count()
                + " @@\n"
                + content
                    .lines()
                    .map(line -> "+" + line + "\n")
                    .collect(java.util.stream.Collectors.joining());
      } catch (java.io.IOException e) {
        throw ApiException.bad("文件读取失败。");
      }
    } else {
      var args =
          new ArrayList<>(
              List.of("diff", "--no-ext-diff", "--no-textconv", "--no-color", "--unified=4"));
      if (staged) args.add("--cached");
      args.add("--");
      args.add(path);
      if (staged)
        changes(id).stream()
            .filter(
                row ->
                    path.equals(row.get("path"))
                        && !String.valueOf(row.get("originalPath")).isBlank())
            .forEach(row -> args.add(String.valueOf(row.get("originalPath"))));
      text = output(id, args.toArray(String[]::new));
    }
    if (!version.equals(fingerprint(id, path))) throw ApiException.conflict("文件已改变，请重新选择文件读取差异。");
    boolean binary = text.contains("Binary files ") || text.contains("GIT binary patch");
    if (text.getBytes(StandardCharsets.UTF_8).length >= 999_990)
      return Map.of(
          "path",
          path,
          "diff",
          "",
          "binary",
          false,
          "fingerprint",
          "",
          "message",
          "差异超过 1 MB，请在编辑器中查看。");
    return Map.of(
        "path",
        path,
        "diff",
        text,
        "binary",
        binary,
        "fingerprint",
        version,
        "message",
        text.isBlank() ? "当前区域没有差异。" : "");
  }

  private String fingerprint(String id, String path) {
    Path file = projects.resolve(id, path, false);
    try {
      if (Files.exists(file) && (!Files.isRegularFile(file) || Files.size(file) > 2_000_000))
        return "";
      String working =
          Files.exists(file) ? ProjectService.hash(Files.readAllBytes(file)) : "deleted";
      String index = optional(id, "ls-files", "--stage", "--", path);
      return ProjectService.hash((working + "\n" + index).getBytes(StandardCharsets.UTF_8));
    } catch (java.io.IOException e) {
      throw ApiException.bad("无法读取文件版本。");
    }
  }

  private List<String> paths(String id, List<String> paths) {
    if (paths == null || paths.isEmpty() || paths.size() > 200)
      throw ApiException.bad("请选择 1 至 200 个文件。");
    for (String path : paths) {
      if (path.isBlank()) throw ApiException.bad("文件路径为空。");
      projects.resolve(id, path, true);
    }
    return paths;
  }

  private String ref(String id, String branch) {
    if (branch == null || branch.isBlank() || branch.startsWith("-") || branch.length() > 200)
      throw ApiException.bad("分支名称不合法。");
    output(id, "check-ref-format", "--branch", branch);
    return branch;
  }

  public synchronized Map<String, Object> operate(String id, GitOperationRequest request) {
    if (!Boolean.TRUE.equals(request.confirmed())) throw ApiException.forbidden("请确认 Git 操作。");
    requireRepositoryRoot(id);
    var args = new ArrayList<String>();
    switch (request.operation()) {
      case "stage" -> {
        args.add("add");
        args.add("--");
        args.addAll(paths(id, request.paths()));
      }
      case "unstage" -> {
        if (optional(id, "rev-parse", "--verify", "HEAD").isBlank())
          args.addAll(List.of("rm", "--cached"));
        else args.addAll(List.of("restore", "--staged"));
        var selected = new LinkedHashSet<>(paths(id, request.paths()));
        for (var row : changes(id))
          if (selected.contains(row.get("path"))
              && !String.valueOf(row.get("originalPath")).isBlank()) {
            String original = String.valueOf(row.get("originalPath"));
            projects.resolve(id, original, true);
            selected.add(original);
          }
        args.add("--");
        args.addAll(selected);
      }
      case "discard" -> {
        List<String> paths = paths(id, request.paths());
        if (paths.size() != 1) throw ApiException.bad("一次只能还原一个文件。");
        String path = paths.getFirst(), current = fingerprint(id, path);
        if (current.isBlank() || !current.equals(request.fingerprint()))
          throw ApiException.conflict("文件已改变或无法安全还原，请刷新差异后重试。");
        if (changes(id).stream()
            .anyMatch(
                row -> path.equals(row.get("path")) && Boolean.TRUE.equals(row.get("untracked"))))
          throw ApiException.forbidden("不会自动删除未跟踪文件。");
        args.addAll(List.of("restore", "--worktree", "--", path));
      }
      case "commit" -> {
        if (request.message() == null || request.message().isBlank())
          throw ApiException.bad("请填写提交说明。");
        var files = changes(id);
        if (files.stream().anyMatch(row -> Boolean.TRUE.equals(row.get("conflict"))))
          throw ApiException.conflict("请先解决冲突。");
        if (files.stream().noneMatch(row -> Boolean.TRUE.equals(row.get("staged"))))
          throw ApiException.bad("请先暂存需要提交的文件。");
        args.addAll(List.of("commit", "-m", request.message()));
      }
      case "checkout", "create-branch" -> {
        if (!changes(id).isEmpty()) throw ApiException.conflict("请先处理未提交的修改，再切换分支；当前修改已保留。");
        String name = ref(id, request.branch());
        if (request.operation().equals("checkout")) {
          output(id, "show-ref", "--verify", "refs/heads/" + name);
          args.addAll(List.of("switch", name));
        } else {
          String base =
              request.base() == null || request.base().isBlank() ? "HEAD" : request.base();
          if (base.startsWith("-") || base.length() > 200) throw ApiException.bad("基准引用不合法。");
          output(id, "rev-parse", "--verify", base + "^{commit}");
          args.addAll(List.of("switch", "-c", name, base));
        }
      }
      case "fetch" -> args.addAll(List.of("fetch", "--prune"));
      case "pull" -> {
        if (!changes(id).isEmpty()) throw ApiException.conflict("请先处理工作区修改，再更新分支。");
        args.addAll(List.of("pull", "--ff-only"));
      }
      case "push" -> args.add("push");
      default -> throw ApiException.bad("不支持的 Git 操作。");
    }
    String log = output(id, args.toArray(String[]::new));
    return Map.of("output", log, "workspace", snapshot(id));
  }
}
