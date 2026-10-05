package com.dongran.work.controller;

import com.dongran.work.infrastructure.Database;
import com.dongran.work.service.SkillEvolutionService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SkillEvolutionController {
  private final SkillEvolutionService service;

  public SkillEvolutionController(SkillEvolutionService s) {
    service = s;
  }

  @GetMapping("/skill-evolution/candidates")
  public Object list(@RequestParam(required = false) String projectId) {
    return service.list(projectId);
  }

  @GetMapping("/skill-evolution/candidates/{id}")
  public Object detail(@PathVariable String id) {
    return service.detail(id);
  }

  @PostMapping("/skill-evolution/candidates/{id}/evaluate")
  public Object evaluate(@PathVariable String id) {
    return service.evaluate(id);
  }

  @PostMapping("/skill-evolution/candidates/{id}/approve")
  public Object approve(@PathVariable String id) {
    return service.approve(id);
  }

  @PostMapping("/skill-evolution/candidates/{id}/reject")
  public Object reject(@PathVariable String id) {
    return service.reject(id);
  }

  @GetMapping("/tasks/{id}/evolution")
  public Object task(@PathVariable String id) {
    return service.task(id);
  }

  @GetMapping("/skills/{id}/versions")
  public Object versions(@PathVariable String id) {
    return service.versions(id);
  }

  @PostMapping("/skills/{id}/rollback")
  public Object rollback(@PathVariable String id, @RequestBody Map<String, Object> body) {
    return service.rollback(
        id, Database.required(body, "versionId", 100), Database.required(body, "expectedHash", 64));
  }
}
