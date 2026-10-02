package com.dongran.work;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dongran.work.infrastructure.CredentialService;
import com.dongran.work.service.ModelProviderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class DeveloperToolsIntegrationTest {
  static final Path DATA = temporary();
  static final String TOKEN = "developer-tools-integration-token";
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired ModelProviderService providers;
  @Autowired CredentialService credentials;

  static Path temporary() {
    try {
      return Files.createTempDirectory("dongran-git-test-");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("dongran.data-dir", DATA::toString);
    r.add("dongran.token", () -> TOKEN);
  }

  MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b) {
    return b.header("Host", "127.0.0.1:3210")
        .header("Authorization", "Bearer " + TOKEN)
        .header("X-Dongran-Client", "desktop")
        .with(
            r -> {
              r.setLocalPort(3210);
              return r;
            });
  }

  MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder b, Object value)
      throws Exception {
    return auth(b).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(value));
  }

  JsonNode result(MockHttpServletRequestBuilder b) throws Exception {
    return json.readTree(
        mvc.perform(b)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray());
  }

  String project(Path root) throws Exception {
    return result(body(post("/api/projects"), Map.of("path", root.toString()))).path("id").asText();
  }

  String git(Path root, String... args) throws Exception {
    var command =
        new ArrayList<>(List.of("git", "-c", "safe.directory=" + root, "-C", root.toString()));
    command.addAll(List.of(args));
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String out =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as(out).isZero();
    return out;
  }

  Path repository(boolean initial) throws Exception {
    Path root = temporary();
    git(root, "init", "-b", "main");
    git(root, "config", "core.autocrlf", "false");
    git(root, "config", "user.name", "Integration Test");
    git(root, "config", "user.email", "integration@example.invalid");
    if (initial) {
      Files.writeString(root.resolve("hello.txt"), "original\n");
      git(root, "add", "hello.txt");
      git(root, "commit", "-m", "initial");
    }
    return root;
  }

  JsonNode operation(String id, String op, Map<String, Object> extra) throws Exception {
    var body = new LinkedHashMap<String, Object>(extra);
    body.put("operation", op);
    body.put("confirmed", true);
    return result(body(post("/api/projects/" + id + "/git/operations"), body));
  }

  MockHttpServletRequestBuilder operationRequest(String id, String op, Map<String, Object> extra)
      throws Exception {
    var body = new LinkedHashMap<String, Object>(extra);
    body.put("operation", op);
    body.put("confirmed", true);
    return body(post("/api/projects/" + id + "/git/operations"), body);
  }

  JsonNode diff(String id, String file, boolean staged) throws Exception {
    return result(
        auth(
            get("/api/projects/" + id + "/git/file-diff")
                .param("path", file)
                .param("staged", String.valueOf(staged))));
  }

  @Test
  void commitsOnlyIndexAndPreservesNewerWorkingChanges() throws Exception {
    Path root = repository(true);
    String id = project(root);
    Files.writeString(root.resolve("hello.txt"), "staged version\n");
    operation(id, "stage", Map.of("paths", List.of("hello.txt")));
    Files.writeString(root.resolve("hello.txt"), "newer working version\n");
    assertThat(diff(id, "hello.txt", true).path("diff").asText())
        .contains("+staged version")
        .doesNotContain("newer working version");
    assertThat(diff(id, "hello.txt", false).path("diff").asText())
        .contains("+newer working version");
    operation(id, "commit", Map.of("message", "commit index only"));
    assertThat(git(root, "show", "HEAD:hello.txt")).isEqualTo("staged version\n");
    assertThat(Files.readString(root.resolve("hello.txt"))).isEqualTo("newer working version\n");
    var snapshot = result(auth(get("/api/projects/" + id + "/git/workspace")));
    assertThat(snapshot.path("commits").size()).isEqualTo(2);
    assertThat(snapshot.path("files").get(0).path("staged").asBoolean()).isFalse();
  }

  @Test
  void discardRequiresCurrentFingerprintAndNeverDeletesUntracked() throws Exception {
    Path root = repository(true);
    String id = project(root);
    Files.writeString(root.resolve("hello.txt"), "first change\n");
    String fingerprint = diff(id, "hello.txt", false).path("fingerprint").asText();
    Files.writeString(root.resolve("hello.txt"), "second change\n");
    mvc.perform(
            operationRequest(
                id, "discard", Map.of("paths", List.of("hello.txt"), "fingerprint", fingerprint)))
        .andExpect(status().isConflict());
    assertThat(Files.readString(root.resolve("hello.txt"))).isEqualTo("second change\n");
    fingerprint = diff(id, "hello.txt", false).path("fingerprint").asText();
    operation(id, "discard", Map.of("paths", List.of("hello.txt"), "fingerprint", fingerprint));
    assertThat(Files.readString(root.resolve("hello.txt"))).isEqualTo("original\n");
    Files.writeString(root.resolve("new.txt"), "keep me");
    fingerprint = diff(id, "new.txt", false).path("fingerprint").asText();
    mvc.perform(
            operationRequest(
                id, "discard", Map.of("paths", List.of("new.txt"), "fingerprint", fingerprint)))
        .andExpect(status().isForbidden());
    assertThat(Files.exists(root.resolve("new.txt"))).isTrue();
  }

  @Test
  void unstagesRenameAndUnbornFilesWithoutDeletingContent() throws Exception {
    Path root = repository(true);
    String id = project(root);
    git(root, "mv", "hello.txt", "renamed.txt");
    var files = result(auth(get("/api/projects/" + id + "/git/workspace"))).path("files");
    assertThat(files.get(0).path("originalPath").asText()).isEqualTo("hello.txt");
    assertThat(diff(id, "renamed.txt", true).path("diff").asText())
        .contains("rename from hello.txt");
    operation(id, "unstage", Map.of("paths", List.of("renamed.txt")));
    assertThat(git(root, "diff", "--cached")).isBlank();
    assertThat(Files.readString(root.resolve("renamed.txt"))).isEqualTo("original\n");
    Path unborn = repository(false);
    String unbornId = project(unborn);
    Files.writeString(unborn.resolve("first.txt"), "first\n");
    operation(unbornId, "stage", Map.of("paths", List.of("first.txt")));
    operation(unbornId, "unstage", Map.of("paths", List.of("first.txt")));
    assertThat(git(unborn, "ls-files")).isBlank();
    assertThat(Files.exists(unborn.resolve("first.txt"))).isTrue();
  }

  @Test
  void branchesRejectDirtyWorkspaceAndUnsafeReferences() throws Exception {
    Path root = repository(true);
    String id = project(root);
    operation(id, "create-branch", Map.of("branch", "feature/agent", "base", "HEAD"));
    assertThat(git(root, "branch", "--show-current").strip()).isEqualTo("feature/agent");
    Files.writeString(root.resolve("hello.txt"), "keep changes\n");
    mvc.perform(operationRequest(id, "checkout", Map.of("branch", "main")))
        .andExpect(status().isConflict());
    assertThat(git(root, "branch", "--show-current").strip()).isEqualTo("feature/agent");
    mvc.perform(operationRequest(id, "stage", Map.of("paths", List.of("../outside.txt"))))
        .andExpect(status().isForbidden());
    git(root, "restore", "hello.txt");
    mvc.perform(operationRequest(id, "create-branch", Map.of("branch", "--orphan")))
        .andExpect(status().isBadRequest());
    operation(id, "checkout", Map.of("branch", "main"));
    Path child = Files.createDirectory(root.resolve("child"));
    String childId = project(child);
    mvc.perform(auth(get("/api/projects/" + childId + "/git/workspace")))
        .andExpect(status().isConflict());
    mvc.perform(operationRequest(childId, "stage", Map.of("paths", List.of("new.txt"))))
        .andExpect(status().isConflict());
  }

  Map<String, Object> profile(String name, String secret) {
    var b = new LinkedHashMap<String, Object>();
    b.put("name", name);
    b.put("kind", "local");
    b.put("baseUrl", "http://127.0.0.1:11434/v1");
    b.put("modelName", "fixture");
    b.put("temperature", 0.5);
    b.put("contextWindow", 32768);
    b.put("apiKey", secret);
    b.put("rememberKey", false);
    return b;
  }

  @Test
  void providerLifecycleKeepsKeysSeparateAndUsesOptimisticRevision() throws Exception {
    var first = result(body(post("/api/model-providers"), profile("first", "secret-first")));
    var second = result(body(post("/api/model-providers"), profile("second", "secret-second")));
    String a = first.path("id").asText(), b = second.path("id").asText();
    result(auth(post("/api/model-providers/" + a + "/activate")));
    assertThat(providers.configuration(null).providerId()).isEqualTo(a);
    assertThat(credentials.get(providers.get(a).credentialId())).isEqualTo("secret-first");
    assertThat(credentials.get(providers.get(b).credentialId())).isEqualTo("secret-second");
    assertThat(result(auth(get("/api/model-providers"))).toString())
        .doesNotContain("secret-first", "secret-second", "credentialId", "apiKey");
    var duplicate = result(auth(post("/api/model-providers/" + a + "/duplicate")));
    assertThat(duplicate.path("credentialConfigured").asBoolean()).isFalse();
    assertThat(duplicate.path("active").asBoolean()).isFalse();
    var update = profile("renamed", "");
    update.put("revision", first.path("revision").asLong());
    var saved = result(body(put("/api/model-providers/" + a), update));
    assertThat(saved.path("revision").asLong()).isEqualTo(2);
    assertThat(saved.path("credentialConfigured").asBoolean()).isTrue();
    mvc.perform(body(put("/api/model-providers/" + a), update)).andExpect(status().isConflict());
    mvc.perform(auth(delete("/api/model-providers/" + a))).andExpect(status().isConflict());
    result(auth(post("/api/model-providers/" + b + "/activate")));
    result(auth(delete("/api/model-providers/" + duplicate.path("id").asText())));
    assertThat(providers.configuration(null).providerId()).isEqualTo(b);
    assertThat(
            new String(
                Files.readAllBytes(DATA.resolve("dongran.sqlite")),
                java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain("secret-first", "secret-second");
  }

  @Test
  void invalidModelProfilesFailValidation() throws Exception {
    var input = profile("invalid", "");
    input.put("contextWindow", 5000);
    mvc.perform(body(post("/api/model-providers"), input)).andExpect(status().isBadRequest());
    input.put("contextWindow", 32768);
    input.put("baseUrl", "http://remote.example/v1");
    mvc.perform(body(post("/api/model-providers"), input)).andExpect(status().isBadRequest());
    input.put("baseUrl", "https://provider.example/v1");
    input.put("apiKey", "header\ninjection");
    mvc.perform(body(post("/api/model-providers"), input)).andExpect(status().isBadRequest());
  }
}
