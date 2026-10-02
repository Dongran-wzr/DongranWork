package com.dongran.work.controller;

import com.dongran.work.exception.ApiException;
import com.dongran.work.infrastructure.Database;
import com.dongran.work.service.GitService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class GitController {
  private final GitService git;

  public GitController(GitService git) {
    this.git = git;
  }

  @GetMapping("/projects/{id}/git")
  public Object git(@PathVariable String id) {
    return git.status(id);
  }

  @GetMapping("/projects/{id}/git/diff")
  public Object diff(@PathVariable String id) {
    return git.diff(id);
  }

  @PostMapping("/projects/{id}/git/init")
  public Object gitInit(@PathVariable String id, @RequestBody Map<String, Object> body) {
    confirmed(body);
    return git.init(id);
  }

  @PostMapping("/projects/{id}/git/commit")
  public Object commit(@PathVariable String id, @RequestBody Map<String, Object> body) {
    confirmed(body);
    return git.commit(id, body);
  }

  @PostMapping("/projects/{id}/git/fetch")
  public Object fetch(@PathVariable String id, @RequestBody Map<String, Object> body) {
    confirmed(body);
    return git.fetch(id);
  }

  @GetMapping("/projects/{id}/worktrees")
  public Object worktrees(@PathVariable String id) {
    return git.worktrees(id);
  }

  @PostMapping("/projects/{id}/worktrees")
  public Object addWorktree(@PathVariable String id, @RequestBody Map<String, Object> body) {
    confirmed(body);
    return git.addWorktree(id, body);
  }

  @DeleteMapping("/projects/{id}/worktrees")
  public Object removeWorktree(@PathVariable String id, @RequestBody Map<String, Object> body) {
    confirmed(body);
    git.removeWorktree(id, Database.required(body, "path", 2000));
    return Map.of("deleted", true);
  }

  private void confirmed(Map<String, Object> body) {
    if (!Boolean.TRUE.equals(body.get("confirmed"))) throw ApiException.forbidden("此操作需要用户明确确认。");
  }
}
