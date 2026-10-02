package com.dongran.work.service;

import com.dongran.work.exception.ApiException;
import com.dongran.work.infrastructure.Database;
import com.dongran.work.repository.SkillRepository;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

@Service
public class SkillService {
  private final SkillRepository repo;
  private final Path data;
  private final ProjectService projects;

  public SkillService(SkillRepository repo, Path dataDirectory, ProjectService projects) {
    this.repo = repo;
    this.data = dataDirectory;
    this.projects = projects;
  }

  private static final int MAX_FILE = 200_000, MAX_TOTAL = 2_000_000;

  public List<Map<String, Object>> list(String project) {
    if (project != null) projects.get(project);
    var names = new HashSet<String>();
    return repo.list(project).stream()
        .map(
            r -> {
              var m = new LinkedHashMap<>(r);
              m.put("effective", names.add(String.valueOf(r.get("name"))));
              return (Map<String, Object>) m;
            })
        .toList();
  }

  public Map<String, Object> get(String id) {
    return repo.get(id);
  }

  public List<Map<String, Object>> effective(String project) {
    return list(project).stream()
        .filter(
            r ->
                Boolean.TRUE.equals(r.get("effective"))
                    && ((Number) r.get("enabled")).intValue() == 1)
        .toList();
  }

  public Map<String, Object> allowed(String project, String id) {
    return effective(project).stream()
        .filter(r -> id.equals(r.get("id")))
        .findFirst()
        .orElseThrow(() -> ApiException.forbidden("技能不在当前项目的已启用范围内。"));
  }

  public List<Map<String, Object>> catalog(String project) {
    return effective(project).stream()
        .map(
            r ->
                Map.of(
                    "id",
                    r.get("id"),
                    "name",
                    r.get("name"),
                    "description",
                    r.get("description"),
                    "version",
                    r.get("version"),
                    "autoMatch",
                    ((Number) r.get("auto_match")).intValue() == 1))
        .toList();
  }

  private static void noLinks(Path target) throws IOException {
    SandboxWorkspaceService.rejectLinks(target);
  }

  private static String safeRelative(String name) {
    String n = name.replace('\\', '/');
    if (n.isBlank()
        || n.startsWith("/")
        || n.contains(":")
        || n.indexOf('\0') >= 0
        || n.length() > 300
        || n.split("/").length > 10
        || Arrays.asList(n.split("/")).contains("..")
        || Arrays.asList(n.split("/")).contains(".")
        || n.contains("//")) throw ApiException.bad("技能资源路径不合法。");
    return n;
  }

  private static byte[] bounded(InputStream input, int limit) throws IOException {
    byte[] bytes = input.readNBytes(limit + 1);
    if (bytes.length > limit) throw ApiException.bad("技能资源超过大小限制。");
    return bytes;
  }

  private Map<String, byte[]> tree(Path root) throws IOException {
    noLinks(root);
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw ApiException.bad("技能目录不存在。");
    var files = new TreeMap<String, byte[]>();
    try (var paths = Files.walk(root, 12)) {
      long total = 0;
      int count = 0;
      for (Path p : paths.limit(451).toList()) {
        if (++count > 450) throw ApiException.bad("技能包目录数量过多。");
        noLinks(p);
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) continue;
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) throw ApiException.bad("不支持特殊文件。");
        if (files.size() >= 200) throw ApiException.bad("技能最多 200 个文件。");
        byte[] bytes;
        try (var in = Files.newInputStream(p)) {
          bytes = bounded(in, MAX_FILE);
        }
        total += bytes.length;
        if (total > MAX_TOTAL) throw ApiException.bad("技能解压后最多 2MB。");
        files.put(safeRelative(root.relativize(p).toString()), bytes);
      }
    }
    return files;
  }

  private Map<String, String> metadata(byte[] bytes) {
    if (bytes == null) throw ApiException.bad("技能包缺少 SKILL.md。");
    String s = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
    if (!s.startsWith("---\n"))
      throw ApiException.bad("SKILL.md 必须以 YAML 元数据开始，包含 name 和 description。");
    int end = s.indexOf("\n---", 4);
    if (end < 0 || end > 12000) throw ApiException.bad("技能元数据未闭合或过大。");
    try {
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      options.setMaxAliasesForCollections(0);
      Object parsed = new Yaml(new SafeConstructor(options)).load(s.substring(4, end));
      if (!(parsed instanceof Map<?, ?> m)) throw new IllegalArgumentException();
      String name = Objects.toString(m.get("name"), "").strip(),
          desc = Objects.toString(m.get("description"), "").strip(),
          version = Objects.toString(m.get("version"), "1.0.0").strip();
      if (!name.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}_ .-]{0,79}")
          || desc.isBlank()
          || desc.length() > 500
          || version.length() > 40) throw new IllegalArgumentException();
      return Map.of("name", name, "description", desc, "version", version);
    } catch (Exception e) {
      throw ApiException.bad("技能元数据不合法，名称最多 80 字，描述最多 500 字。");
    }
  }

  private static String hash(Map<String, byte[]> files) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (var e : new TreeMap<>(files).entrySet()) {
        digest.update(e.getKey().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(e.getValue());
        digest.update((byte) 0);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Path base(String project) throws IOException {
    Path root =
        project == null
            ? data.resolve("skills")
            : projects.root(project).resolve(".dongran/skills");
    noLinks(root);
    Files.createDirectories(root);
    return root;
  }

  private synchronized Map<String, Object> install(
      String project, Map<String, byte[]> files, String source) throws IOException {
    if (files.size() > 200
        || files.values().stream().anyMatch(v -> v.length > MAX_FILE)
        || files.values().stream().mapToInt(v -> v.length).sum() > MAX_TOTAL)
      throw ApiException.bad("技能包超过限制。");
    var meta = metadata(files.get("SKILL.md"));
    if (repo.list(project).stream()
        .anyMatch(
            r ->
                Objects.equals(project, r.get("project_id"))
                    && meta.get("name").equals(r.get("name"))))
      throw ApiException.conflict("同范围已有同名技能，请编辑现有条目或先卸载。");
    if (repo.list(project).size() >= 100) throw ApiException.bad("当前范围最多 100 个技能。");
    String id = Database.id();
    Path target = base(project).resolve(id);
    Files.createDirectory(target);
    try {
      for (var e : files.entrySet()) {
        Path file = target.resolve(safeRelative(e.getKey())).normalize();
        if (!file.startsWith(target)) throw ApiException.bad("资源路径越界。");
        Files.createDirectories(file.getParent());
        Files.write(file, e.getValue(), StandardOpenOption.CREATE_NEW);
      }
      repo.insert(
          id,
          project,
          meta.get("name"),
          meta.get("description"),
          meta.get("version"),
          target.toString(),
          hash(files),
          source);
      return get(id);
    } catch (Exception e) {
      removeTree(target);
      throw e;
    }
  }

  private static void removeTree(Path root) throws IOException {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
    noLinks(root);
    try (var paths = Files.walk(root)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
        noLinks(p);
        Files.delete(p);
      }
    }
  }

  public Map<String, Object> importDirectory(String project, String path) {
    try {
      return install(project, tree(Path.of(path).toAbsolutePath().normalize()), "directory");
    } catch (IOException e) {
      throw ApiException.bad("无法导入技能目录：" + e.getMessage());
    }
  }

  public Map<String, Object> importZip(String project, InputStream input) {
    try (var zip =
        ZipFile.builder()
            .setSeekableByteChannel(new SeekableInMemoryByteChannel(bounded(input, 4_000_000)))
            .get()) {
      var files = new TreeMap<String, byte[]>();
      int entries = 0, total = 0;
      var entriesInZip = zip.getEntries();
      while (entriesInZip.hasMoreElements()) {
        ZipArchiveEntry e = entriesInZip.nextElement();
        String n = safeRelative(e.getName());
        if (++entries > 250 || e.isUnixSymlink() || !zip.canReadEntryData(e))
          throw ApiException.bad("压缩包包含链接、过多文件或不支持的条目。");
        if (e.isDirectory()) continue;
        byte[] bytes;
        try (var stream = zip.getInputStream(e)) {
          bytes = bounded(stream, MAX_FILE);
        }
        total += bytes.length;
        if (total > MAX_TOTAL || files.putIfAbsent(n, bytes) != null)
          throw ApiException.bad("技能包过大或包含重复路径。");
      }
      var roots =
          files.keySet().stream()
              .filter(n -> n.equals("SKILL.md") || n.endsWith("/SKILL.md"))
              .toList();
      if (roots.size() != 1) throw ApiException.bad("每个技能包必须且只能包含一份 SKILL.md。");
      String prefix = roots.getFirst().substring(0, roots.getFirst().length() - 8);
      var normalized = new TreeMap<String, byte[]>();
      for (var item : files.entrySet()) {
        if (!item.getKey().startsWith(prefix)) throw ApiException.bad("压缩包中有技能目录之外的文件。");
        normalized.put(item.getKey().substring(prefix.length()), item.getValue());
      }
      return install(project, normalized, "zip");
    } catch (IOException e) {
      throw ApiException.bad("技能 ZIP 读取失败。");
    }
  }

  public Map<String, Object> create(String project, String content) {
    try {
      return install(
          project, Map.of("SKILL.md", content.getBytes(StandardCharsets.UTF_8)), "manual");
    } catch (IOException e) {
      throw ApiException.bad("创建技能失败。");
    }
  }

  public String content(String id) {
    try {
      var files = tree(root(get(id)));
      return new String(files.getOrDefault("SKILL.md", new byte[0]), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw ApiException.bad("技能内容不可读取。");
    }
  }

  private Path root(Map<String, Object> row) throws IOException {
    Path root = Path.of(String.valueOf(row.get("root_path"))).toAbsolutePath().normalize();
    Path base = base((String) row.get("project_id")).toAbsolutePath().normalize();
    if (!root.startsWith(base) || root.equals(base)) throw ApiException.forbidden("技能目录不在管理范围内。");
    noLinks(root);
    return root;
  }

  public synchronized Map<String, Object> edit(String id, String text, String expected) {
    try {
      var row = get(id);
      Path root = root(row);
      var files = tree(root);
      if (!hash(files).equals(expected)) throw ApiException.conflict("技能内容已变化，请刷新后保存。");
      if (text.getBytes(StandardCharsets.UTF_8).length > MAX_FILE)
        throw ApiException.bad("技能正文过大。");
      files.put("SKILL.md", text.getBytes(StandardCharsets.UTF_8));
      var meta = metadata(files.get("SKILL.md"));
      if (!meta.get("name").equals(row.get("name"))) throw ApiException.bad("编辑时不能修改技能名称，请新建技能。");
      Path temporary = Files.createTempFile(root, ".skill-", ".tmp");
      try {
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        Files.move(
            temporary,
            root.resolve("SKILL.md"),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
      repo.update(id, meta.get("name"), meta.get("description"), meta.get("version"), hash(files));
      return get(id);
    } catch (IOException e) {
      throw ApiException.bad("技能保存失败。");
    }
  }

  public Map<String, Object> detail(String id) {
    try {
      var row = new LinkedHashMap<>(get(id));
      var files = tree(root(row));
      row.put(
          "content",
          new String(files.getOrDefault("SKILL.md", new byte[0]), StandardCharsets.UTF_8));
      row.put("files", files.keySet());
      row.put("diskHash", hash(files));
      row.put("changed", !hash(files).equals(row.get("content_hash")));
      return row;
    } catch (IOException e) {
      throw ApiException.bad("技能包不可读取。");
    }
  }

  public Map<String, Object> refresh(String id) {
    var row = get(id);
    var d = detail(id);
    metadata(String.valueOf(d.get("content")).getBytes(StandardCharsets.UTF_8));
    return edit(id, String.valueOf(d.get("content")), String.valueOf(d.get("diskHash")));
  }

  private Map<String, byte[]> verified(String project, String id) {
    var row = allowed(project, id);
    try {
      var files = tree(root(row));
      if (!hash(files).equals(row.get("content_hash")))
        throw ApiException.conflict("技能文件已变更，请在技能设置中审阅并重新同步。");
      return files;
    } catch (IOException e) {
      throw ApiException.bad("技能文件不可读取。");
    }
  }

  public Map<String, Object> load(String project, String id) {
    var row = allowed(project, id);
    var files = verified(project, id);
    return Map.of(
        "id",
        id,
        "name",
        row.get("name"),
        "version",
        row.get("version"),
        "hash",
        row.get("content_hash"),
        "content",
        new String(files.get("SKILL.md"), StandardCharsets.UTF_8),
        "resources",
        files.keySet());
  }

  public Object resource(String project, String id, String name, int offset) {
    var files = verified(project, id);
    byte[] b = files.get(safeRelative(name));
    if (b == null) throw ApiException.missing("技能资源不存在。");
    String text = new String(b, StandardCharsets.UTF_8);
    if (offset < 0 || offset > text.length()) throw ApiException.bad("读取范围不合法。");
    int end = Math.min(text.length(), offset + 8000);
    return Map.of(
        "path",
        name,
        "content",
        text.substring(offset, end),
        "nextOffset",
        end,
        "total",
        text.length());
  }

  public Map<String, byte[]> scriptFiles(String project, String id, String path, String expected) {
    var row = allowed(project, id);
    if (!Objects.equals(expected, row.get("content_hash")))
      throw ApiException.conflict("技能版本已变更，重新加载后执行。");
    var files = verified(project, id);
    if (!safeRelative(path).startsWith("scripts/") || !files.containsKey(path))
      throw ApiException.bad("只能执行 scripts 下的文件。");
    return files;
  }

  public void set(String id, boolean enabled, boolean auto) {
    repo.state(id, enabled, auto);
  }

  public synchronized void delete(String id) {
    var row = get(id);
    try {
      removeTree(root(row));
      repo.delete(id);
    } catch (IOException e) {
      throw ApiException.bad("卸载技能失败。");
    }
  }

  public synchronized List<Map<String, Object>> scan(String project) {
    if (project == null) throw ApiException.bad("先选择项目。");
    try {
      Path base = base(project);
      try (var directories = Files.list(base)) {
        for (Path root : directories.limit(101).toList()) {
          noLinks(root);
          if (!Files.isDirectory(root) || !Files.isRegularFile(root.resolve("SKILL.md"))) continue;
          if (repo.list(project).stream()
              .anyMatch(
                  r -> root.toAbsolutePath().normalize().toString().equals(r.get("root_path"))))
            continue;
          var files = tree(root);
          var meta = metadata(files.get("SKILL.md"));
          if (repo.list(project).stream()
              .anyMatch(
                  r ->
                      project.equals(r.get("project_id"))
                          && meta.get("name").equals(r.get("name"))))
            throw ApiException.conflict("项目目录有同名技能：" + meta.get("name"));
          repo.insert(
              Database.id(),
              project,
              meta.get("name"),
              meta.get("description"),
              meta.get("version"),
              root.toAbsolutePath().normalize().toString(),
              hash(files),
              "project");
        }
      }
      return list(project);
    } catch (IOException e) {
      throw ApiException.bad("扫描项目技能失败。");
    }
  }
}
