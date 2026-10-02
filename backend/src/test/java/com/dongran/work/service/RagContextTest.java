package com.dongran.work.service;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class RagContextTest {
  @Test
  void cleansInstructionsWithoutLosingSubject() {
    assertThat(RetrievalQuery.clean("请调用 web_search 搜索 Java 21 虚拟线程官方文档，列出找到的页面标题和链接。不要只根据已有知识回答。"))
        .isEqualTo("Java 21 虚拟线程官方文档");
    assertThat(RetrievalQuery.clean("请检索知识库，给我一份毕业实习报告模板")).isEqualTo("毕业实习报告模板");
  }

  @Test
  void removesUnrelatedVectorNeighborsAndOrdersDocumentContext() {
    var head =
        Map.<String, Object>of(
            "id", "report", "chunkId", "head", "name", "毕业实习报告模板.doc", "ordinal", 0);
    var tail =
        Map.<String, Object>of(
            "id", "report", "chunkId", "tail", "name", "毕业实习报告模板.doc", "ordinal", 1);
    var noise = Map.<String, Object>of("id", "exam", "name", "高考英语真题.docx", "ordinal", 13);
    assertThat(RagContext.select("毕业实习报告模板", List.of(tail, noise, head)))
        .containsExactly(head, tail);
  }

  @Test
  void citationsMustExistInRetrievedSources() {
    String doc = "11111111-1111-1111-1111-111111111111",
        chunk = "22222222-2222-2222-2222-222222222222";
    var source = Map.<String, Object>of("id", doc, "chunkId", chunk);
    String answer = "答案。[资料](knowledge://" + doc + "/" + chunk + ")";
    assertThat(RagContext.cited(answer, List.of(source))).containsExactly(source);
    assertThat(RagContext.validate(answer, List.of()))
        .doesNotContain("knowledge://")
        .contains("来源未验证");
  }

  @Test
  void rejectsSearchPortalsAndGenericJavaPages() {
    var portal =
        Map.<String, Object>of(
            "url", "https://www.bing.com/", "title", "Java virtual threads", "snippet", "");
    var unrelated =
        Map.<String, Object>of(
            "url",
            "https://example.com/download/21",
            "title",
            "Download Java 21",
            "snippet",
            "JDK download");
    var relevant =
        Map.<String, Object>of(
            "url",
            "https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html",
            "title",
            "Virtual Threads",
            "snippet",
            "Java 21 virtual threads guide");
    assertThat(
            WebToolsService.relevant(
                "Java 21 virtual threads documentation", List.of(portal, unrelated, relevant)))
        .containsExactly(relevant);
  }
}
