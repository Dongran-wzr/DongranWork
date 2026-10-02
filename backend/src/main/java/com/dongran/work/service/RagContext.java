package com.dongran.work.service;

import java.util.*;
import java.util.regex.Pattern;

/** Context selection and citation validation shared by automatic and tool-driven retrieval. */
final class RagContext {
  static List<Map<String, Object>> select(String query, List<Map<String, Object>> rows) {
    var terms =
        KnowledgeRetrievalService.tokens(query).stream()
            .filter(t -> t.length() > 1)
            .filter(t -> !Set.of("给我", "一份", "怎么", "如何", "知识", "识库", "模板").contains(t))
            .distinct()
            .toList();
    Map<String, Long> matches = new HashMap<>();
    for (var row : rows) {
      String title = String.valueOf(row.get("name")).toLowerCase(Locale.ROOT);
      matches.put(String.valueOf(row.get("id")), terms.stream().filter(title::contains).count());
    }
    long best = matches.values().stream().mapToLong(Long::longValue).max().orElse(0);
    // Only focus on document titles when there is strong lexical evidence; semantic queries retain
    // recall.
    return rows.stream()
        .filter(
            row -> best < 2 || matches.get(String.valueOf(row.get("id"))) >= Math.max(2, best - 1))
        .sorted(
            Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("id")))
                .thenComparingInt(r -> ((Number) r.get("ordinal")).intValue()))
        .limit(12)
        .toList();
  }

  static List<Map<String, Object>> cited(String answer, List<Map<String, Object>> sources) {
    Set<String> cited = new LinkedHashSet<>();
    var matcher =
        Pattern.compile("knowledge://([a-fA-F0-9-]{36})/([a-fA-F0-9-]{36})").matcher(answer);
    while (matcher.find()) cited.add(matcher.group(1) + "/" + matcher.group(2));
    return sources.stream()
        .filter(row -> cited.contains(row.get("id") + "/" + row.get("chunkId")))
        .distinct()
        .toList();
  }

  static String validate(String answer, List<Map<String, Object>> sources) {
    var allowed = new HashSet<String>();
    for (var row : sources) allowed.add(row.get("id") + "/" + row.get("chunkId"));
    var matcher =
        Pattern.compile("\\[([^\\]]+)\\]\\(knowledge://([^)/]+/[^)]+)\\)").matcher(answer);
    return matcher.replaceAll(
        match ->
            java.util.regex.Matcher.quoteReplacement(
                allowed.contains(match.group(2)) ? match.group() : match.group(1) + "（来源未验证）"));
  }
}
