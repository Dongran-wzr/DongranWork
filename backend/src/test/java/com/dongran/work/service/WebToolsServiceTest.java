package com.dongran.work.service;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebToolsServiceTest {
  @Test
  void blocksLocalAndNonWebAddresses() {
    for (String url :
        new String[] {
          "file:///etc/passwd",
          "http://127.0.0.1/",
          "http://[::1]/",
          "http://169.254.169.254/",
          "http://10.0.0.1/",
          "http://100.64.0.1/",
          "http://[fc00::1]/",
          "https://example.com:8080/",
          "http://user:password@example.com/"
        })
      assertThatThrownBy(() -> WebToolsService.validate(url)).as(url).isInstanceOf(Exception.class);
  }

  @Test
  void extractsVisibleTextWithoutExecutingScripts() {
    var page =
        WebToolsService.html(
            "<html><head><title>Fixture &amp; title</title><style>hidden css</style></head><body><h1>Visible title</h1><p>正文 &amp; text</p><script>secretScript()</script></body></html>",
            "https://example.com");
    assertThat(page.get("title")).isEqualTo("Fixture & title");
    assertThat(page.get("content").toString())
        .contains("Visible title", "正文 & text")
        .doesNotContain("secretScript", "hidden css");
  }

  @Test
  void searchParsesResultsAndRejectsExternalEntities() throws Exception {
    var rows =
        WebToolsService.parseSearch(
            "<rss><channel><item><title>Example</title><link>https://example.com</link><description>Sample</description></item></channel></rss>"
                .getBytes(StandardCharsets.UTF_8));
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().get("url")).isEqualTo("https://example.com");
    assertThatThrownBy(
            () ->
                WebToolsService.parseSearch(
                    "<!DOCTYPE rss [<!ENTITY external SYSTEM 'file:///etc/passwd'>]><rss>&external;</rss>"
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(Exception.class);
  }
}
