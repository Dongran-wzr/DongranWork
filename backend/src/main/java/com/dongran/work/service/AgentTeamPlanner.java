package com.dongran.work.service;

import java.util.*;
import org.springframework.stereotype.Component;

/** Classifies intent into a small, auditable team plan before the lead agent delegates. */
@Component
public class AgentTeamPlanner {
  public record Specialist(String id, String label, String purpose, String reason) {}

  public record Node(String id, List<String> dependsOn) {}

  public record Plan(List<Specialist> specialists, List<Node> dag, String summary) {
    public List<String> ids() {
      return specialists.stream().map(Specialist::id).toList();
    }
  }

  private static final Map<String, String> LABELS =
      Map.of(
          "product",
          "产品分析",
          "architect",
          "架构设计",
          "developer",
          "开发实现",
          "reviewer",
          "代码审查",
          "tester",
          "测试验证",
          "security",
          "安全审查",
          "researcher",
          "资料研究",
          "docs",
          "文档整理",
          "ux",
          "交互设计",
          "devops",
          "工程运维");

  public Plan plan(String prompt) {
    String text = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
    var selected = new LinkedHashSet<String>();
    if (has(text, "需求", "产品", "用户故事", "验收", "prd", "功能设计")) selected.add("product");
    if (has(text, "架构", "方案", "设计", "模块", "接口", "数据库", "性能")) selected.add("architect");
    if (has(text, "代码", "实现", "开发", "修改", "修复", "重构", "接口")) selected.add("developer");
    if (has(text, "审查", "review", "检查代码", "质量")) selected.add("reviewer");
    if (has(text, "测试", "验证", "运行", "回归", "验收")) selected.add("tester");
    if (has(text, "安全", "权限", "漏洞", "密钥", "合规")) selected.add("security");
    if (has(text, "调研", "研究", "资料", "文档链接", "对比")) selected.add("researcher");
    if (has(text, "文档", "说明", "readme", "指南")) selected.add("docs");
    if (has(text, "界面", "ui", "ux", "交互", "视觉")) selected.add("ux");
    if (has(text, "部署", "发布", "构建", "ci", "运维", "docker")) selected.add("devops");
    if (selected.isEmpty()) selected.add("researcher");
    var specialists =
        selected.stream()
            .map(id -> new Specialist(id, LABELS.get(id), purpose(id), reason(id, text)))
            .toList();
    var ids =
        specialists.stream()
            .map(Specialist::id)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    var dag = new ArrayList<Node>();
    for (String id : ids) {
      var deps = new ArrayList<String>();
      if ((id.equals("developer") || id.equals("architect")) && ids.contains("product"))
        deps.add("product");
      if (id.equals("developer") && ids.contains("architect")) deps.add("architect");
      if (Set.of("reviewer", "tester", "security", "docs", "devops").contains(id)
          && ids.contains("developer")) deps.add("developer");
      dag.add(new Node(id, deps));
    }
    return new Plan(specialists, dag, "根据任务意图生成 DAG；无依赖 Agent 并发执行，主 Agent 负责汇总和最终验收。");
  }

  public boolean supported(String role) {
    return LABELS.containsKey(role);
  }

  public String label(String role) {
    return LABELS.getOrDefault(role, "专业 Agent");
  }

  public boolean allowed(String role, String tool) {
    if ("lead".equals(role) || "developer".equals(role) || "devops".equals(role)) return true;
    if (Set.of("product", "architect", "researcher", "ux", "docs").contains(role))
      return Set.of(
              "list_files",
              "read_file",
              "git_status",
              "git_diff",
              "search_knowledge",
              "web_fetch",
              "web_search",
              "remember",
              "delegate")
          .contains(tool);
    if ("reviewer".equals(role) || "security".equals(role))
      return Set.of(
              "list_files",
              "read_file",
              "git_status",
              "git_diff",
              "run_command",
              "search_knowledge",
              "web_fetch",
              "web_search",
              "remember")
          .contains(tool);
    if ("tester".equals(role))
      return Set.of(
              "list_files",
              "read_file",
              "run_command",
              "git_status",
              "git_diff",
              "search_knowledge",
              "web_fetch",
              "web_search")
          .contains(tool);
    return false;
  }

  private static boolean has(String text, String... words) {
    return Arrays.stream(words).anyMatch(text::contains);
  }

  private static String purpose(String id) {
    return switch (id) {
      case "product" -> "澄清目标、用户流程和验收标准";
      case "architect" -> "拆解系统边界、接口、数据和技术方案";
      case "developer" -> "在项目中实现或修改代码";
      case "reviewer" -> "检查变更质量、回归风险和可维护性";
      case "tester" -> "设计并运行验证，反馈可复现结果";
      case "security" -> "检查权限、输入、密钥和安全边界";
      case "researcher" -> "整理项目资料和外部事实依据";
      case "docs" -> "整理需求、变更说明和使用文档";
      case "ux" -> "分析界面结构、交互和可用性";
      default -> "处理构建、发布和运行环境";
    };
  }

  private static String reason(String id, String text) {
    return switch (id) {
      case "tester" -> "任务包含运行或验收意图";
      case "developer" -> "任务包含实现或修改意图";
      case "product" -> "任务包含需求和目标澄清意图";
      default -> "任务关键词与该专业能力匹配";
    };
  }
}
