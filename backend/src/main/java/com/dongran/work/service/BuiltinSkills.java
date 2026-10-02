package com.dongran.work.service;

import com.dongran.work.infrastructure.Database;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class BuiltinSkills implements ApplicationRunner {
  private final SkillService skills;
  private final PreferenceService prefs;
  private final Database db;

  public BuiltinSkills(SkillService skills, PreferenceService prefs, Database db) {
    this.skills = skills;
    this.prefs = prefs;
    this.db = db;
  }

  public void run(ApplicationArguments args) {
    var marker =
        db.jdbc.queryForList("SELECT value FROM preferences WHERE key='builtinSkillsMigrated'");
    if (!marker.isEmpty()) return;
    String[][] definitions = {
      {
        "product-docs",
        "需求文档 PRD 产品需求 用户流程 验收标准",
        "先明确用户目标、范围、角色、约束与未知事项。阅读已有项目文档；必要时向用户澄清。输出背景、目标、用户流程、功能需求、非功能需求、验收标准和待确认事项。不得虚构已经确认的需求。写入文件仍需遵守当前权限。"
      },
      {
        "code-review",
        "代码审查 code review 检查提交 代码质量 缺陷",
        "先确认审查范围，调用 git_diff 查看真实改动，再用 read_file 读取相关上下文。优先找可复现的正确性、安全性和回归问题。必要检查通过沙箱执行；不能执行时明确说明。每个问题给出文件位置、触发条件、影响和修复建议，无证据不编造问题。最后说明测试覆盖及未验证项。"
      },
      {
        "browser-check",
        "浏览器验证 页面测试 UI 验收 前端交互",
        "根据用户流程制定验证步骤，检查可用工具和浏览器环境。仅在确有浏览器工具或项目测试脚本时执行；脚本进入沙箱。没有浏览器工具时说明缺少能力，不能声称看过页面。报告步骤、实际结果、缺陷和未覆盖项。"
      }
    };
    for (var d : definitions) {
      if (skills.list(null).stream().anyMatch(r -> d[0].equals(r.get("name")))) continue;
      var row =
          skills.create(
              null,
              "---\nname: "
                  + d[0]
                  + "\ndescription: "
                  + d[1]
                  + "\nversion: 1.0.0\n---\n\n"
                  + d[2]
                  + "\n");
      boolean enabled =
          prefs.collection("installedExtensions").stream()
              .anyMatch(r -> d[0].equals(r.get("id")) && Boolean.TRUE.equals(r.get("enabled")));
      skills.set((String) row.get("id"), enabled, true);
      db.jdbc.update("UPDATE skills SET source='builtin' WHERE id=?", row.get("id"));
    }
    db.jdbc.update("INSERT INTO preferences(key,value) VALUES('builtinSkillsMigrated','true')");
  }
}
