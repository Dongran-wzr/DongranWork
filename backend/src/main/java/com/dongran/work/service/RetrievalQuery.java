package com.dongran.work.service;

/** Removes tool/output instructions while preserving the user's search subject. */
final class RetrievalQuery {
  static String clean(String input) {
    String q =
        input
            .replaceAll("https?://\\S+", "")
            .replaceAll("(?i)web_search|web_fetch", "")
            .replaceAll("[，,。；;]\\s*(?:列出|列举|附上|不要只|不要仅|请用|用中文|回答时|并给出).*$", "")
            .replaceAll(
                "^(?:请|帮我|为我|调用|使用|根据|从|在|的|里|中|搜索|检索|查询|联网|网页|网上|知识库|已有资料|内部文档|[\\s，,:：])+", "")
            .replaceAll("^(?:给我|提供|生成)(?:一份|一个)?", "")
            .replaceAll("\\s+", " ")
            .strip();
    return q.isBlank() ? input.strip() : q.substring(0, Math.min(200, q.length()));
  }
}
