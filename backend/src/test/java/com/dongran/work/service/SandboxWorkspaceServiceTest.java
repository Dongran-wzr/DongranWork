package com.dongran.work.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dongran.work.exception.ApiException;
import com.dongran.work.model.Project;
import com.dongran.work.repository.ProjectRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class SandboxWorkspaceServiceTest {
  @TempDir Path temporary;
  private Path project;
  private Path outside;
  private SandboxWorkspaceService service;
  private final List<Path> links = new ArrayList<>();

  @BeforeEach
  void setup() throws IOException {
    project = Files.createDirectory(temporary.resolve("project")).toRealPath();
    outside = Files.createDirectory(temporary.resolve("outside")).toRealPath();
    var preferences = mock(PreferenceService.class);
    when(preferences.string(eq("excludedPaths"), anyString()))
        .thenReturn(".env\n.git\nnode_modules\nprivate/notes");
    var repository = mock(ProjectRepository.class);
    when(repository.findById("project"))
        .thenReturn(Optional.of(new Project("project", "Project", project.toString(), "", "")));
    var projects = new ProjectService(preferences, repository);
    service = new SandboxWorkspaceService(temporary.resolve("app-data"), projects);
  }

  @AfterEach
  void removeOnlyCreatedLinks() throws IOException {
    // Unlink junctions explicitly before TempDir cleanup; never walk their external targets.
    for (Path link : links) Files.deleteIfExists(link);
  }

  @Test
  void snapshotExcludesRepositoryMetadataCredentialsAndConfiguredPaths() throws Exception {
    write(project, "src/Main.java", "class Main {}");
    write(project, ".env.example", "API_KEY=example");
    for (String path :
        List.of(
            ".git/config",
            ".env",
            ".env.production",
            "config/private.PEM",
            "config/signing.key",
            ".ssh/id_rsa",
            "id_ed25519.pub",
            ".aws/credentials",
            ".npmrc",
            ".runtime/private.txt",
            ".codex/auth.json",
            "node_modules/dependency/index.js",
            "private/notes/team.txt")) {
      write(project, path, "secret:" + path);
    }

    var snapshot = prepare();

    assertThat(snapshot.original().keySet())
        .containsExactlyInAnyOrder("src/Main.java", ".env.example");
    assertThat(regularFiles(snapshot.workspace())).isEqualTo(snapshot.original().keySet());
    assertThat(Files.readString(snapshot.workspace().resolve("src/Main.java")))
        .isEqualTo("class Main {}");
    assertThat(Files.readString(project.resolve(".env"))).isEqualTo("secret:.env");
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void windowsSnapshotAlsoExcludesCaseVariantsOfSensitivePaths() throws Exception {
    write(project, "src/main.txt", "safe");
    write(project, ".ENV", "secret");
    write(project, ".GiT/config", "secret");
    write(project, ".Env.production", "secret");
    write(project, "PRIVATE/NOTES/team.txt", "secret");

    var snapshot = prepare();

    assertThat(snapshot.original().keySet()).containsExactly("src/main.txt");
    assertThat(regularFiles(snapshot.workspace())).containsExactly("src/main.txt");
  }

  @Test
  void successfulSyncUpdatesCreatesAndDeletesOnlyChangedFiles() throws Exception {
    write(project, "src/update.txt", "before");
    write(project, "src/delete.txt", "remove");
    write(project, "keep.txt", "keep");
    var snapshot = prepare();
    write(snapshot.workspace(), "src/update.txt", "after");
    write(snapshot.workspace(), "new/nested.txt", "new");
    Files.delete(snapshot.workspace().resolve("src/delete.txt"));

    assertThat(Files.readString(project.resolve("src/update.txt"))).isEqualTo("before");
    assertThat(Files.exists(project.resolve("new/nested.txt"))).isFalse();
    var result = service.sync(snapshot);

    assertThat(result.status()).isEqualTo("applied");
    assertThat(result.files()).isEqualTo(3);
    assertThat(Files.readString(project.resolve("src/update.txt"))).isEqualTo("after");
    assertThat(Files.readString(project.resolve("new/nested.txt"))).isEqualTo("new");
    assertThat(Files.exists(project.resolve("src/delete.txt"))).isFalse();
    assertThat(Files.readString(project.resolve("keep.txt"))).isEqualTo("keep");
    assertThat(Files.exists(snapshot.workspace())).isTrue();
  }

  @Test
  void unchangedSnapshotDoesNotRewriteSourceFile() throws Exception {
    write(project, "main.txt", "same");
    var modified = Files.getLastModifiedTime(project.resolve("main.txt"));
    var snapshot = prepare();

    var result = service.sync(snapshot);

    assertThat(result.status()).isEqualTo("unchanged");
    assertThat(result.files()).isZero();
    assertThat(Files.getLastModifiedTime(project.resolve("main.txt"))).isEqualTo(modified);
  }

  @Test
  void modifiedOriginalPreventsEveryUpdateAndDeleteDuringPreflight() throws Exception {
    write(project, "a-update.txt", "original a");
    write(project, "z-conflict.txt", "original z");
    write(project, "delete.txt", "original delete");
    var snapshot = prepare();
    write(snapshot.workspace(), "a-update.txt", "agent a");
    write(snapshot.workspace(), "z-conflict.txt", "agent z");
    write(snapshot.workspace(), "new.txt", "agent new");
    Files.delete(snapshot.workspace().resolve("delete.txt"));
    write(project, "z-conflict.txt", "user edit");

    var failure = assertThrows(ApiException.class, () -> service.sync(snapshot));

    assertThat(failure.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(Files.readString(project.resolve("a-update.txt"))).isEqualTo("original a");
    assertThat(Files.readString(project.resolve("z-conflict.txt"))).isEqualTo("user edit");
    assertThat(Files.readString(project.resolve("delete.txt"))).isEqualTo("original delete");
    assertThat(Files.exists(project.resolve("new.txt"))).isFalse();
    assertThat(Files.readString(snapshot.workspace().resolve("z-conflict.txt")))
        .isEqualTo("agent z");
  }

  @Test
  void newlyCreatedOriginalIsNeverOverwrittenBySnapshotAddition() throws Exception {
    write(project, "existing.txt", "before");
    var snapshot = prepare();
    write(snapshot.workspace(), "existing.txt", "agent");
    write(snapshot.workspace(), "new.txt", "agent addition");
    write(project, "new.txt", "user addition");

    var failure = assertThrows(ApiException.class, () -> service.sync(snapshot));

    assertThat(failure.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(Files.readString(project.resolve("existing.txt"))).isEqualTo("before");
    assertThat(Files.readString(project.resolve("new.txt"))).isEqualTo("user addition");
  }

  @Test
  void sourceDirectoryLinkIsSkippedWithoutCopyingExternalContents() throws Exception {
    write(project, "main.txt", "safe");
    write(outside, "private.txt", "external secret");
    directoryLink(project.resolve("external"), outside);

    var snapshot = prepare();

    assertThat(snapshot.original().keySet()).containsExactly("main.txt");
    assertThat(Files.exists(snapshot.workspace().resolve("external"), LinkOption.NOFOLLOW_LINKS))
        .isFalse();
    assertThat(Files.readString(outside.resolve("private.txt"))).isEqualTo("external secret");
  }

  @Test
  void newResultDirectoryLinkRejectsEntireSyncWithoutExternalChanges() throws Exception {
    write(project, "main.txt", "before");
    write(outside, "private.txt", "external secret");
    var snapshot = prepare();
    write(snapshot.workspace(), "main.txt", "agent update");
    directoryLink(snapshot.workspace().resolve("external"), outside);

    var failure = assertThrows(IOException.class, () -> service.sync(snapshot));

    assertThat(failure.getMessage()).contains("链接");
    assertThat(Files.readString(project.resolve("main.txt"))).isEqualTo("before");
    assertThat(Files.exists(project.resolve("external"), LinkOption.NOFOLLOW_LINKS)).isFalse();
    assertThat(Files.readString(outside.resolve("private.txt"))).isEqualTo("external secret");
    assertThat(regularFiles(outside)).containsExactly("private.txt");
  }

  @Test
  void originalDirectoryReplacedByLinkCannotRedirectWriteback() throws Exception {
    write(project, "main.txt", "before");
    write(project, "src/private.txt", "same");
    write(outside, "private.txt", "same");
    var snapshot = prepare();
    write(snapshot.workspace(), "main.txt", "agent update");
    write(snapshot.workspace(), "src/private.txt", "agent secret");
    Files.delete(project.resolve("src/private.txt"));
    Files.delete(project.resolve("src"));
    directoryLink(project.resolve("src"), outside);

    var failure = assertThrows(ApiException.class, () -> service.sync(snapshot));

    assertThat(failure.status()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(Files.readString(project.resolve("main.txt"))).isEqualTo("before");
    assertThat(Files.readString(outside.resolve("private.txt"))).isEqualTo("same");
  }

  private SandboxWorkspaceService.Snapshot prepare() throws IOException {
    return service.prepare(UUID.randomUUID().toString(), "project");
  }

  private static void write(Path directory, String relative, String content) throws IOException {
    Path file = directory.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private static Set<String> regularFiles(Path directory) throws IOException {
    try (var paths = Files.walk(directory)) {
      return paths
          .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .map(path -> directory.relativize(path).toString().replace('\\', '/'))
          .collect(java.util.stream.Collectors.toSet());
    }
  }

  private void directoryLink(Path link, Path target) throws Exception {
    if (System.getProperty("os.name").startsWith("Windows")) {
      // Junctions require no developer mode or symlink privilege on Windows.
      String command =
          "$ErrorActionPreference='Stop'; New-Item -ItemType Junction -Path "
              + quotePowerShell(link.toString())
              + " -Target "
              + quotePowerShell(target.toString())
              + " | Out-Null";
      Process process =
          new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
              .redirectErrorStream(true)
              .start();
      if (!process.waitFor(20, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException("Timed out creating test junction");
      }
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(process.exitValue()).withFailMessage(output).isZero();
    } else {
      Files.createSymbolicLink(link, target);
    }
    links.add(link);
    assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isTrue();
  }

  private static String quotePowerShell(String value) {
    return "'" + value.replace("'", "''") + "'";
  }
}
