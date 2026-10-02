package com.dongran.work.controller;

import com.dongran.work.dto.FileWriteRequest;
import com.dongran.work.dto.ProjectOpenRequest;
import com.dongran.work.exception.ApiException;
import com.dongran.work.service.ProjectService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ProjectController {
  private final ProjectService projects;

  public ProjectController(ProjectService projects) {
    this.projects = projects;
  }

  @GetMapping("/projects")
  public Object projects() {
    return projects.list();
  }

  @PostMapping("/projects")
  public Object open(@Valid @RequestBody ProjectOpenRequest body) {
    return projects.open(body.path());
  }

  @GetMapping("/directories")
  public Object directories(@RequestParam(required = false) String path) {
    return projects.browse(path);
  }

  @GetMapping("/projects/{id}/files")
  public Object files(@PathVariable String id, @RequestParam(defaultValue = "") String path) {
    return projects.files(id, path);
  }

  @GetMapping("/projects/{id}/file")
  public Object file(@PathVariable String id, @RequestParam String path) {
    return projects.read(id, path);
  }

  @PutMapping("/projects/{id}/file")
  public Object write(@PathVariable String id, @Valid @RequestBody FileWriteRequest body) {
    if (!Boolean.TRUE.equals(body.confirmed())) throw ApiException.forbidden("此操作需要用户明确确认。");
    return projects.write(id, body.path(), body.content(), body.expectedSha256());
  }
}
