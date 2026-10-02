package com.dongran.work.controller;

import com.dongran.work.dto.ApprovalRequest;
import com.dongran.work.dto.MessageRequest;
import com.dongran.work.dto.TaskCreateRequest;
import com.dongran.work.infrastructure.Database;
import com.dongran.work.service.AgentService;
import com.dongran.work.service.ApprovalService;
import com.dongran.work.service.TaskStreamService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class TaskController {
  private final AgentService agents;
  private final ApprovalService approvals;
  private final TaskStreamService streams;
  private final Database db;
  private final com.dongran.work.service.AgentTeamPlanner planner;

  public TaskController(
      AgentService agents,
      ApprovalService approvals,
      TaskStreamService streams,
      Database db,
      com.dongran.work.service.AgentTeamPlanner planner) {
    this.agents = agents;
    this.approvals = approvals;
    this.streams = streams;
    this.db = db;
    this.planner = planner;
  }

  @GetMapping("/tasks/{id}/team")
  public Object team(@PathVariable String id) {
    var task = agents.get(id);
    return planner.plan(String.valueOf(task.get("prompt")));
  }

  @GetMapping("/tasks")
  public Object tasks(@RequestParam(required = false) String projectId) {
    return agents.list(projectId);
  }

  @PostMapping("/tasks")
  public Object task(@Valid @RequestBody TaskCreateRequest body) {
    return agents.create(db.object(db.json(body)));
  }

  @GetMapping("/tasks/{id}")
  public Object task(@PathVariable String id) {
    return agents.get(id);
  }

  @PostMapping("/tasks/{id}/messages")
  public Object reply(@PathVariable String id, @Valid @RequestBody MessageRequest body) {
    return agents.reply(id, body.prompt());
  }

  @PostMapping("/tasks/{id}/cancel")
  public Object cancel(@PathVariable String id) {
    agents.cancel(id);
    return Map.of("cancelled", true);
  }

  @PostMapping("/approvals/{id}")
  public Object approval(@PathVariable String id, @Valid @RequestBody ApprovalRequest body) {
    approvals.resolve(id, body.approved());
    return Map.of("resolved", true);
  }

  @GetMapping(value = "/tasks/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @PathVariable String id,
      @RequestParam(defaultValue = "0") long after,
      @RequestHeader(value = "Last-Event-ID", required = false) String last) {
    return streams.stream(id, after, last);
  }
}
