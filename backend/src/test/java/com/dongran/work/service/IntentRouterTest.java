package com.dongran.work.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dongran.work.infrastructure.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class IntentRouterTest {
  final ModelClient model = mock(ModelClient.class);
  final IntentRouter router = new IntentRouter(model, new Database(null, new ObjectMapper()));

  @Test
  void explicitReadsDoNotDependOnModelToolCalls() throws Exception {
    var kb = router.route("知识库里的毕业实习报告模板怎么写？", "", false);
    assertThat(kb.actions()).containsExactly("search_knowledge");
    assertThat(kb.goal()).isEqualTo("answer");
    var web = router.route("总结 https://example.com ，附上来源", "", false);
    assertThat(web.urls()).containsExactly("https://example.com");
    assertThat(web.actions()).containsExactly("web_fetch");
    verifyNoInteractions(model);
  }

  @Test
  void networkAndReadOnlyLimitsWin() throws Exception {
    var route = router.route("不要联网，只分析这个项目的结构和最新文档 https://example.com", "", true);
    assertThat(route.networkForbidden()).isTrue();
    assertThat(route.readOnly()).isTrue();
    assertThat(route.actions())
        .contains("search_knowledge", "list_files")
        .doesNotContain("web_fetch", "web_search");
    assertThat(router.route("如何实现登录功能？", "", true).goal()).isEqualTo("answer");
  }

  @Test
  void contextClassifierAcceptsOnlyAllowedActions() throws Exception {
    when(model.complete(any(), any(), any()))
        .thenReturn(
            new ModelClient.Result(
                "{\"goal\":\"answer\",\"actions\":[\"search_knowledge\",\"web_search\",\"run_command\"],\"query\":\"验收标准\"}",
                List.of(),
                Map.of()));
    var route = router.route("对比一下验收标准", "用户讨论内部资料", true);
    assertThat(route.strategy()).isEqualTo("model");
    assertThat(route.actions()).containsExactly("search_knowledge");
    assertThat(route.query()).isEqualTo("验收标准");
  }

  @Test
  void invalidClassifierFallsBackWithoutExecutingUnknownActions() throws Exception {
    when(model.complete(any(), any(), any()))
        .thenReturn(new ModelClient.Result("不是 JSON", List.of(), Map.of()));
    assertThat(router.route("对比一下", "", false).actions()).isEmpty();
  }
}
