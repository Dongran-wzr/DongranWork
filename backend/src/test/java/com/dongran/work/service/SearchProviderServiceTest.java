package com.dongran.work.service;

import static org.assertj.core.api.Assertions.*;

import com.dongran.work.infrastructure.Database;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class SearchProviderServiceTest {
  @Test
  void mapsProviderResultsAndRejectsUnsafeLinks() {
    var rows =
        List.of(
            Map.of("url", "https://example.com/docs", "title", "Docs", "content", "Evidence"),
            Map.of("url", "javascript:alert(1)", "title", "Bad"));
    assertThat(SearchProviderService.parse("tavily", Map.of("results", rows)))
        .hasSize(1)
        .first()
        .extracting(r -> r.get("snippet"))
        .isEqualTo("Evidence");
    assertThat(
            SearchProviderService.parse(
                "brave",
                Map.of(
                    "web",
                    Map.of(
                        "results",
                        List.of(
                            Map.of(
                                "url",
                                "https://example.com",
                                "title",
                                "Docs",
                                "description",
                                "Snippet"))))))
        .first()
        .extracting(r -> r.get("snippet"))
        .isEqualTo("Snippet");
  }

  @Test
  void keysStayInHeadersAndUseFixedHttpsEndpoints() {
    var db = new Database(null, new ObjectMapper());
    var tavily = SearchProviderService.request("tavily", "synthetic-key", "public test", db);
    assertThat(tavily.uri().toString()).isEqualTo("https://api.tavily.com/search");
    assertThat(tavily.headers().firstValue("Authorization")).contains("Bearer synthetic-key");
    var brave = SearchProviderService.request("brave", "synthetic-key", "Java 21", db);
    assertThat(brave.uri().toString()).contains("q=Java+21").doesNotContain("synthetic-key");
    assertThat(brave.headers().firstValue("X-Subscription-Token")).contains("synthetic-key");
  }
}
