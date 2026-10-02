package com.dongran.work.controller;

import com.dongran.work.dto.GitOperationRequest;
import com.dongran.work.service.GitWorkspaceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{id}/git")
public class GitWorkspaceController {
  private final GitWorkspaceService git;

  public GitWorkspaceController(GitWorkspaceService git) {
    this.git = git;
  }

  @GetMapping("/workspace")
  public Object workspace(@PathVariable String id) {
    return git.snapshot(id);
  }

  @GetMapping("/file-diff")
  public Object diff(
      @PathVariable String id,
      @RequestParam String path,
      @RequestParam(defaultValue = "false") boolean staged) {
    return git.diff(id, path, staged);
  }

  @PostMapping("/operations")
  public Object operate(@PathVariable String id, @Valid @RequestBody GitOperationRequest body) {
    return git.operate(id, body);
  }
}
