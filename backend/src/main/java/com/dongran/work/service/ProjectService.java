package com.dongran.work.service;

import com.dongran.work.exception.ApiException;
import com.dongran.work.infrastructure.Database;
import com.dongran.work.model.Project;
import com.dongran.work.repository.ProjectRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
  private final PreferenceService preferences;
  private final ProjectRepository repository;

  public ProjectService(PreferenceService preferences, ProjectRepository repository) {
    this.preferences = preferences;
    this.repository = repository;
  }

  public List<Project> list() {
    return repository.findRecent();
  }

  public Project get(String id) {
    return repository.findById(id).orElseThrow(() -> ApiException.missing("项目不存在。"));
  }

  @Transactional
  public Project open(String directory) {
    Path root;
    try {
      root = Path.of(directory).toRealPath();
      if (!Files.isDirectory(root)) throw ApiException.bad("请选择存在的目录。");
    } catch (Exception e) {
      throw ApiException.bad("项目目录不存在或不可访问。");
    }
    String path = root.toString();
    var existing = repository.findByPath(path, System.getProperty("os.name").startsWith("Windows"));
    String id = existing.map(Project::id).orElseGet(Database::id);
    if (existing.isEmpty())
      repository.insert(
          id,
          root.getFileName() == null ? path : root.getFileName().toString(),
          path,
          Database.now(),
          Database.now());
    else repository.touch(id, Database.now());
    return get(id);
  }

  public Path root(String projectId) {
    try {
      return Path.of(get(projectId).path()).toRealPath();
    } catch (java.io.IOException e) {
      throw ApiException.missing("项目目录已移动或不可访问。");
    }
  }

  public Path resolve(String projectId, String relative, boolean writing) {
    Path root = root(projectId);
    String normalized = relative.replace('\\', '/');
    if (relative.indexOf('\0') >= 0
        || normalized.startsWith("/")
        || normalized.matches("^[a-zA-Z]:.*")
        || Arrays.asList(normalized.split("/")).contains(".."))
      throw ApiException.forbidden("路径必须位于当前项目内。");
    if (excluded(normalized)) throw ApiException.forbidden("此路径已被排除，不能访问。");
    Path target = root.resolve(relative).normalize();
    if (!target.startsWith(root) || (writing && target.equals(root)))
      throw ApiException.forbidden("路径必须位于当前项目内。");
    try {
      Path ancestor = target;
      while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
      if (ancestor == null || !ancestor.toRealPath().startsWith(root))
        throw ApiException.forbidden("符号链接指向项目之外。");
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !target.toRealPath().startsWith(root))
        throw ApiException.forbidden("符号链接指向项目之外。");
    } catch (java.io.IOException e) {
      throw ApiException.bad("路径不可访问。");
    }
    return target;
  }

  boolean excluded(String relative) {
    var blocked = new HashSet<>(List.of(".git", ".dongran", "node_modules"));
    for (String entry :
        preferences.string("excludedPaths", ".env\nnode_modules\n.git").split("\\R"))
      if (!entry.isBlank()) blocked.add(entry.trim().replace('\\', '/'));
    for (String part : relative.split("/"))
      if (blocked.contains(part)
          || part.equals(".env")
          || part.startsWith(".env.") && !part.endsWith(".example")) return true;
    return blocked.stream()
        .anyMatch(value -> relative.equals(value) || relative.startsWith(value + "/"));
  }

  public List<Map<String, Object>> files(String projectId, String path) {
    Path directory = resolve(projectId, path, false);
    Path root = root(projectId);
    if (!Files.isDirectory(directory)) throw ApiException.bad("该路径不是目录。");
    try (Stream<Path> entries = Files.list(directory)) {
      return entries
          .filter(p -> !excluded(root.relativize(p).toString().replace('\\', '/')))
          .sorted(
              Comparator.comparing((Path p) -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                  .thenComparing(p -> p.getFileName().toString()))
          .limit(500)
          .map(
              p -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", p.getFileName().toString());
                item.put("path", root.relativize(p).toString().replace('\\', '/'));
                item.put("directory", Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS));
                item.put("symlink", Files.isSymbolicLink(p));
                return item;
              })
          .toList();
    } catch (java.io.IOException e) {
      throw ApiException.bad("无法读取目录。");
    }
  }

  public Map<String, Object> read(String projectId, String path) {
    Path file = resolve(projectId, path, false);
    try {
      if (!Files.isRegularFile(file) || Files.size(file) > 1024 * 1024)
        throw ApiException.bad("只能读取不超过 1 MB 的文本文件。");
      byte[] bytes = Files.readAllBytes(file);
      for (byte b : bytes) if (b == 0) throw ApiException.bad("不支持读取二进制文件。");
      return Map.of(
          "path",
          path,
          "content",
          new String(bytes, StandardCharsets.UTF_8),
          "sha256",
          hash(bytes));
    } catch (java.io.IOException e) {
      throw ApiException.bad("文件不可读取。");
    }
  }

  public synchronized Map<String, Object> write(
      String projectId, String path, String content, String expected) {
    if (content.length() > 1_000_000) throw ApiException.bad("文件内容超过限制。");
    Path file = resolve(projectId, path, true);
    try {
      if (Files.exists(file)) {
        if (Files.size(file) > 1024 * 1024
            || expected == null
            || !expected.equals(hash(Files.readAllBytes(file))))
          throw ApiException.conflict("文件已变化，请重新读取后再保存。");
      } else if (expected != null && !expected.isBlank())
        throw ApiException.conflict("文件已被删除，请重新确认。");
      Files.createDirectories(file.getParent());
      resolve(projectId, path, true);
      Path temporary = Files.createTempFile(file.getParent(), ".dongran-write-", ".tmp");
      try {
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
          Files.move(
              temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(temporary);
      }
      return Map.of(
          "path",
          path,
          "sha256",
          hash(content.getBytes(StandardCharsets.UTF_8)),
          "bytes",
          content.getBytes(StandardCharsets.UTF_8).length);
    } catch (java.io.IOException e) {
      throw ApiException.bad("文件保存失败。");
    }
  }

  public static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public Map<String, Object> browse(String directory) {
    Path path =
        Path.of(
                directory == null || directory.isBlank()
                    ? System.getProperty("user.home")
                    : directory)
            .toAbsolutePath()
            .normalize();
    try (Stream<Path> entries = Files.list(path)) {
      var result = new LinkedHashMap<String, Object>();
      result.put("path", path.toString());
      result.put("parent", path.getParent() == null ? null : path.getParent().toString());
      result.put(
          "directories",
          entries
              .filter(Files::isDirectory)
              .filter(p -> !p.getFileName().toString().startsWith("."))
              .sorted()
              .limit(200)
              .map(p -> Map.of("name", p.getFileName().toString(), "path", p.toString()))
              .toList());
      result.put(
          "roots",
          Arrays.stream(java.io.File.listRoots()).map(java.io.File::getAbsolutePath).toList());
      return result;
    } catch (java.io.IOException e) {
      throw ApiException.bad("无法打开此目录。");
    }
  }
}
