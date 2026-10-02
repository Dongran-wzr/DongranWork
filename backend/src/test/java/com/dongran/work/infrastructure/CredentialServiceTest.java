package com.dongran.work.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialServiceTest {
  @TempDir Path data;

  @Test
  void sessionCredentialsCanBeDeletedWithoutPlatformStorage() {
    var service = new CredentialService(data);
    assertThat(service.save("provider-test", "temporary-test-key", false).get("storage"))
        .isEqualTo("session");
    assertThat(service.get("provider-test")).isEqualTo("temporary-test-key");
    assertThat(new CredentialService(data).get("provider-test")).isEmpty();
    service.delete("provider-test");
    assertThat(service.get("provider-test")).isEmpty();
  }

  @Test
  void switchingToSessionOnlyDoesNotRestoreAnOldSavedKey() {
    assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    var service = new CredentialService(data);
    var stored = service.save("provider-test", "old-test-key", true);
    assumeTrue(Boolean.TRUE.equals(stored.get("persistent")));
    assertThat(new CredentialService(data).get("provider-test")).isEqualTo("old-test-key");
    service.save("provider-test", "new-session-test-key", false);
    assertThat(service.get("provider-test")).isEqualTo("new-session-test-key");
    assertThat(new CredentialService(data).get("provider-test")).isEmpty();
    service.delete("provider-test");
  }
}
