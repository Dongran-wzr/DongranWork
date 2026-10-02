package com.dongran.work.service;

import com.dongran.work.exception.ApiException;
import com.dongran.work.infrastructure.Database;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ExtensionService {
  private final PreferenceService preferences;
  private final AgentService agents;

  public ExtensionService(PreferenceService preferences, AgentService agents) {
    this.preferences = preferences;
    this.agents = agents;
  }

  public Object extensions() {
    return Map.of(
        "installed",
        preferences.collection("installedExtensions"),
        "catalog",
        List.of(
            Map.of("id", "product-docs", "name", "需求文档"),
            Map.of("id", "code-review", "name", "代码审查"),
            Map.of("id", "browser-check", "name", "浏览器验证")));
  }

  public Object run(String id, Map<String, Object> body) {
    if (preferences.collection("installedExtensions").stream()
        .noneMatch(e -> id.equals(e.get("id")) && Database.bool(e, "enabled", false)))
      throw ApiException.conflict("扩展未启用。");
    String prompt =
        Map.of(
                "product-docs",
                "分析当前项目并生成需求文档，写入 docs/requirements.md，明确范围与验收标准。",
                "code-review",
                "审查当前项目 Git 变更，报告真实的缺陷、风险和缺失测试。",
                "browser-check",
                "检查当前项目的浏览器测试配置，在获得命令执行授权后运行现有浏览器测试，报告真实结果；没有测试配置时明确说明。")
            .get(id);
    if (prompt == null) throw ApiException.missing("扩展不存在。");
    var input = new LinkedHashMap<>(body);
    input.put("prompt", prompt);
    return agents.create(input);
  }
}
