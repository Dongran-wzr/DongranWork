package com.dongran.work.service;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AgentTeamPlannerTest {
  private final AgentTeamPlanner planner = new AgentTeamPlanner();
  @Test void recognizesMixedProductEngineeringSecurityIntent() {
    var plan=planner.plan("梳理登录需求，修改接口并补充测试和权限安全检查");
    assertThat(plan.ids()).contains("product","architect","developer","tester","security");
    assertThat(planner.allowed("product","write_file")).isFalse();
    assertThat(planner.allowed("developer","write_file")).isTrue();
    assertThat(planner.allowed("tester","run_command")).isTrue();
  }
  @Test void fallsBackToResearchAndKeepsDelegationOneLevelDeep() {
    assertThat(planner.plan("帮我了解这个项目").ids()).containsExactly("researcher");
    assertThat(planner.supported("ux")).isTrue();
    assertThat(planner.supported("random")).isFalse();
  }
  @Test void planningHasNoCyclesAndValidationDependsOnDevelopment() {
    var plan=new AgentTeamPlanner().plan("产品需求、架构设计、开发实现、测试、安全审查、文档与部署");
    var done=new HashSet<String>();
    for(int round=0;round<plan.dag().size();round++) {
      var ready=plan.dag().stream().filter(n->done.containsAll(n.dependsOn())).map(AgentTeamPlanner.Node::id).toList();
      done.addAll(ready);
    }
    assertThat(done).containsAll(plan.ids());
    assertThat(plan.dag().stream().filter(n->n.id().equals("tester")).findFirst().orElseThrow().dependsOn()).contains("developer");
  }
}
