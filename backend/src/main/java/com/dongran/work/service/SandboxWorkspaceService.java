package com.dongran.work.service;

import com.dongran.work.exception.ApiException;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;

/** Snapshot and merge are host-side operations; no executable in the snapshot runs on the host. */
@Service
public class SandboxWorkspaceService {
  private static final long MAX_FILE = 32L * 1024 * 1024;
  private static final long MAX_BYTES = 256L * 1024 * 1024;
  private static final int MAX_FILES = 20000;
  private final Path base;
  private final ProjectService projects;

  public SandboxWorkspaceService(Path dataDirectory, ProjectService projects) {
    this.base = dataDirectory.resolve("sandboxes").toAbsolutePath().normalize();
    this.projects = projects;
  }

  public record Snapshot(
      String projectId, Path source, Path workspace, Map<String, String> original) {}

  public record SyncResult(String status, int files) {}

  public Snapshot prepare(String id, String projectId) throws IOException {
    if (!id.matches("[a-f0-9-]{36}")) throw new IOException("非法执行标识");
    Path root = projects.root(projectId);
    if (root.getParent() == null
        || root.equals(Path.of(System.getProperty("user.home")).toRealPath()))
      throw ApiException.bad("请选择具体项目目录，不能将磁盘根目录或用户主目录作为沙箱项目。");
    Files.createDirectories(base);
    rejectLinks(base);
    Path owned = base.resolve(id);
    Files.createDirectory(owned);
    Path workspace = Files.createDirectory(owned.resolve("workspace"));
    Map<String, Path> source = inventory(root, false);
    var hashes = new LinkedHashMap<String, String>();
    for (var item : source.entrySet()) {
      if (Thread.currentThread().isInterrupted()) throw new IOException("快照准备已取消");
      Path original = item.getValue();
      Path target = workspace.resolve(item.getKey());
      Files.createDirectories(target.getParent());
      byte[] content = readBounded(original);
      Files.write(target, content, StandardOpenOption.CREATE_NEW);
      if (!System.getProperty("os.name").startsWith("Windows") && Files.isExecutable(original))
        target.toFile().setExecutable(true, true);
      hashes.put(item.getKey(), hash(content));
    }
    return new Snapshot(projectId, root, workspace, Map.copyOf(hashes));
  }

  public synchronized SyncResult sync(Snapshot snapshot) throws IOException {
    Path root = projects.root(snapshot.projectId());
    if (!root.equals(snapshot.source())) throw ApiException.conflict("项目目录已变化，未回写沙箱文件。");
    Map<String, Path> after = inventory(snapshot.workspace(), true);
    var updates = new LinkedHashMap<String, Path>();
    var deletes = new ArrayList<String>();
    for (var entry : after.entrySet()) {
      String digest = hash(readBounded(entry.getValue()));
      if (!digest.equals(snapshot.original().get(entry.getKey())))
        updates.put(entry.getKey(), entry.getValue());
    }
    for (String relative : snapshot.original().keySet())
      if (!after.containsKey(relative)) deletes.add(relative);
    var changed = new LinkedHashSet<>(updates.keySet());
    changed.addAll(deletes);
    // Validate the entire change set before touching any host file.
    for (String relative : changed) assertUnchanged(snapshot, relative);
    for (var entry : updates.entrySet()) {
      Path target = safeTarget(snapshot, entry.getKey());
      Files.createDirectories(target.getParent());
      rejectLinks(target.getParent());
      Path temporary = Files.createTempFile(target.getParent(), ".dongran-sync-", ".tmp");
      try {
        Files.write(temporary, readBounded(entry.getValue()));
        assertUnchanged(snapshot, entry.getKey());
        if (!System.getProperty("os.name").startsWith("Windows")) {
          // Preserve an existing source file's mode; never import ACLs from the sandbox.
          if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(target));
          else if (Files.isExecutable(entry.getValue()))
            temporary.toFile().setExecutable(true, true);
        }
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
    }
    for (String relative : deletes) {
      assertUnchanged(snapshot, relative);
      Files.delete(safeTarget(snapshot, relative));
    }
    return new SyncResult(changed.isEmpty() ? "unchanged" : "applied", changed.size());
  }

  private void assertUnchanged(Snapshot snapshot, String relative) throws IOException {
    Path target = safeTarget(snapshot, relative);
    String expected = snapshot.original().get(relative);
    boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
    if (expected == null ? exists : !exists || !expected.equals(hash(readBounded(target))))
      throw ApiException.conflict(
          "项目文件已被修改，沙箱结果未覆盖该文件：" + relative + "；结果保留在 " + snapshot.workspace());
  }

  private Path safeTarget(Snapshot snapshot, String relative) throws IOException {
    if (blocked(relative)) throw ApiException.forbidden("沙箱结果包含禁止回写路径：" + relative);
    Path target = projects.resolve(snapshot.projectId(), relative, true);
    rejectLinks(target);
    return target;
  }

  private boolean blocked(String relative) {
    if (projects.excluded(relative) || projects.excluded(relative.toLowerCase(Locale.ROOT)))
      return true;
    for (String part : relative.replace('\\', '/').split("/")) {
      String name = part.toLowerCase(Locale.ROOT);
      if (Set.of(
                  ".tools",
                  ".runtime",
                  ".dongran-work",
                  ".ssh",
                  ".aws",
                  ".azure",
                  ".kube",
                  ".npmrc",
                  ".pypirc",
                  ".netrc",
                  ".credentials",
                  ".gnupg",
                  ".codex",
                  ".claude",
                  ".sandbox-tmp")
              .contains(name)
          || name.endsWith(".pem")
          || name.endsWith(".key")
          || name.startsWith("id_rsa")
          || name.startsWith("id_ed25519")) return true;
    }
    return false;
  }

  private Map<String, Path> inventory(Path root, boolean strict) throws IOException {
    var files = new LinkedHashMap<String, Path>();
    long[] bytes = {0};
    Files.walkFileTree(
        root,
        EnumSet.noneOf(FileVisitOption.class),
        100,
        new SimpleFileVisitor<>() {
          private boolean skip(Path path, BasicFileAttributes attributes) throws IOException {
            String relative = root.relativize(path).toString().replace('\\', '/');
            if (!path.equals(root)
                && (blocked(relative)
                    || !strict && path.toAbsolutePath().normalize().startsWith(base))) return true;
            boolean unsafe = attributes.isSymbolicLink() || attributes.isOther() || isReparse(path);
            if (unsafe && strict) throw new IOException("沙箱结果包含链接或特殊文件，拒绝回写：" + relative);
            return unsafe;
          }

          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            return skip(dir, attrs) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (skip(file, attrs)) return FileVisitResult.CONTINUE;
            if (!attrs.isRegularFile()) throw new IOException("不支持的项目文件");
            bytes[0] += attrs.size();
            if (attrs.size() > MAX_FILE || bytes[0] > MAX_BYTES || files.size() >= MAX_FILES)
              throw new IOException("项目快照超过限制（单文件 32 MB、总计 256 MB、20000 个文件），请缩小项目或排除生成目录。");
            files.put(root.relativize(file).toString().replace('\\', '/'), file);
            return FileVisitResult.CONTINUE;
          }
        });
    return files;
  }

  static void rejectLinks(Path path) throws IOException {
    for (Path current = path.toAbsolutePath(); current != null; current = current.getParent())
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
          && (Files.isSymbolicLink(current) || isReparse(current)))
        throw new IOException("项目或工作区路径包含链接，拒绝访问：" + current);
  }

  private static boolean isReparse(Path path) throws IOException {
    if (!System.getProperty("os.name").startsWith("Windows")) return false;
    Object attributes = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
    return attributes instanceof Number n && (n.intValue() & 0x400) != 0;
  }

  private static byte[] readBounded(Path path) throws IOException {
    rejectLinks(path);
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("只允许常规文件");
    try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = stream.readNBytes((int) MAX_FILE + 1);
      if (bytes.length > MAX_FILE) throw new IOException("文件超过 32 MB");
      return bytes;
    }
  }

  private static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
