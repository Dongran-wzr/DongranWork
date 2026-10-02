package com.dongran.work.service;

import com.dongran.work.infrastructure.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class IntentRouter {
  public record Route(
      String goal,
      List<String> actions,
      String query,
      List<String> urls,
      boolean networkForbidden,
      boolean readOnly,
      String strategy,
      String completion) {}

  private final ModelClient model;
  private final Database db;

  public IntentRouter(ModelClient model, Database db) {
    this.model = model;
    this.db = db;
  }

  private static boolean has(String s, String... words) {
    return Arrays.stream(words).anyMatch(s::contains);
  }

  public Route route(String prompt, String previous, boolean project) throws Exception {
    boolean denied = has(prompt, "不要联网", "禁止联网", "不联网", "仅本地", "只用本地"),
        readOnly = has(prompt, "不要修改", "不修改", "只讨论", "只分析", "只读", "先设计", "先规划", "如何实现", "怎么实现");
    var urls = new ArrayList<String>();
    var matcher = Pattern.compile("https?://[^\\s<>\"，。；）]+").matcher(prompt);
    while (matcher.find() && urls.size() < 3) urls.add(matcher.group().replaceAll("[.,;)]$", ""));
    var actions = new LinkedHashSet<String>();
    boolean knowledge = has(prompt, "知识库", "项目资料", "已有资料", "内部文档"),
        web = has(prompt, "网上", "联网搜索", "网页搜索", "web_search", "最新", "官方文档");
    if (knowledge) actions.add("search_knowledge");
    if (!denied && !urls.isEmpty()) actions.add("web_fetch");
    if (!denied && web && urls.isEmpty()) actions.add("web_search");
    if (project
        && has(prompt, "这个项目", "当前项目", "项目的")
        && has(prompt, "流程", "结构", "如何", "怎么", "分析")) {
      actions.add("search_knowledge");
      actions.add("list_files");
    }
    String goal =
        has(
                    prompt, "实现", "修复", "修改代码", "运行测试", "执行命令", "写入", "生成文件", "创建文件", "保存到", "运行验证",
                    "运行技能", "执行技能", "运行脚本", "执行脚本")
                && !readOnly
            ? "execute"
            : "answer";
    String query = RetrievalQuery.clean(prompt);
    if (has(prompt, "刚才", "上面", "这个方案", "那个文档") && !previous.isBlank() && actions.isEmpty()) {
      if (has(previous, "知识库", "项目资料")) actions.add("search_knowledge");
    }
    if (actions.isEmpty()
        && !has(prompt, "你好", "你是谁", "hi", "谢谢")
        && (has(prompt, "刚才", "上面", "这个方案", "那个文档", "继续研究", "对比", "调研"))) {
      try {
        var result =
            model.complete(
                List.of(
                    Map.of(
                        "role",
                        "system",
                        "content",
                        "你是意图路由器，仅输出 JSON：{goal:answer或execute,actions:[search_knowledge,web_search,list_files 中的零或多个],query:检索词}。根据当前请求和上轮上下文判断，普通聊天 actions 为空；内部资料优先 search_knowledge，明确外部时效信息才 web_search。不要执行任务。"),
                    Map.of(
                        "role",
                        "user",
                        "content",
                        db.json(
                            Map.of(
                                "current",
                                prompt,
                                "previous",
                                previous.substring(0, Math.min(previous.length(), 6000)))))),
                List.of(),
                text -> {});
        String text = result.text().strip().replaceAll("^```(?:json)?\\s*|\\s*```$", "");
        var parsed = db.object(text);
        if (parsed.get("actions") instanceof List<?> list)
          for (Object action : list)
            if (Set.of("search_knowledge", "web_search", "list_files").contains(action)
                && ((!denied && web) || !action.equals("web_search"))
                && (!action.equals("list_files") || project)) actions.add(String.valueOf(action));
        if (!readOnly && "execute".equals(parsed.get("goal"))) goal = "execute";
        if (parsed.get("query") instanceof String q && !q.isBlank()) query = q;
        return new Route(
            goal,
            List.copyOf(actions),
            query.substring(0, Math.min(200, query.length())),
            urls,
            denied,
            readOnly,
            "model",
            "必要读取执行后再回答，引用真实来源；写入和运行必须有对应执行记录");
      } catch (com.dongran.work.exception.ApiException | IllegalStateException e) {
        /* Conservative local fallback. */
      }
    }
    return new Route(
        goal,
        List.copyOf(actions),
        query.substring(0, Math.min(200, query.length())),
        urls,
        denied,
        readOnly,
        "rules",
        "必要读取执行后再回答，引用真实来源；写入和运行必须有对应执行记录");
  }
}
