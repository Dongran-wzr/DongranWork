package com.dongran.work.controller;

import com.dongran.work.infrastructure.Database;
import com.dongran.work.service.SkillService;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/skills")
public class SkillController {
  private final SkillService skills;

  public SkillController(SkillService skills) {
    this.skills = skills;
  }

  @GetMapping
  public Object list(@RequestParam(required = false) String projectId) {
    return skills.list(projectId);
  }

  @GetMapping("/{id}")
  public Object get(@PathVariable String id) {
    return skills.detail(id);
  }

  @PostMapping("/directory")
  public Object directory(@RequestBody Map<String, Object> b) {
    return skills.importDirectory(
        Database.text(b, "projectId", null), Database.required(b, "path", 2000));
  }

  @PostMapping(value = "/zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Object zip(
      @RequestParam(required = false) String projectId, @RequestPart("file") MultipartFile file)
      throws Exception {
    return skills.importZip(projectId, file.getInputStream());
  }

  @PostMapping("/{id}/state")
  public Object state(@PathVariable String id, @RequestBody Map<String, Object> b) {
    skills.set(id, Database.bool(b, "enabled", true), Database.bool(b, "autoMatch", true));
    return skills.get(id);
  }

  @DeleteMapping("/{id}")
  public Object delete(@PathVariable String id) {
    skills.delete(id);
    return Map.of("deleted", true);
  }

  @PostMapping
  public Object create(@RequestBody Map<String, Object> b) {
    return skills.create(
        Database.text(b, "projectId", null), Database.required(b, "content", 200000));
  }

  @PutMapping("/{id}")
  public Object edit(@PathVariable String id, @RequestBody Map<String, Object> b) {
    return skills.edit(
        id, Database.required(b, "content", 200000), Database.required(b, "expectedHash", 64));
  }

  @PostMapping("/{id}/refresh")
  public Object refresh(@PathVariable String id) {
    return skills.refresh(id);
  }

  @PostMapping("/scan")
  public Object scan(@RequestBody Map<String, Object> b) {
    return skills.scan(Database.required(b, "projectId", 100));
  }
}
