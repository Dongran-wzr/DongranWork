package com.dongran.work.controller;

import com.dongran.work.infrastructure.Database;
import com.dongran.work.repository.MemoryRepository;
import com.dongran.work.service.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ContextController {
  private final ContextEngine context;
  private final MemoryRepository memories;
  private final PreferenceService prefs;

  public ContextController(
      ContextEngine context, MemoryRepository memories, PreferenceService prefs) {
    this.context = context;
    this.memories = memories;
    this.prefs = prefs;
  }

  @GetMapping("/tasks/{id}/context")
  public Object context(@PathVariable String id) {
    return context.inspect(id);
  }

  @GetMapping("/tasks/{id}/evidence/{evidence}")
  public Object evidence(
      @PathVariable String id,
      @PathVariable String evidence,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "6000") int length) {
    return context.readEvidence(id, "lead", evidence, offset, length);
  }

  @GetMapping("/context/settings")
  public Object settings() {
    return context.settings();
  }

  @GetMapping("/memory-records")
  public Object memories() {
    return memories.rows();
  }

  @PostMapping("/memory-records/{id}/status")
  public Object status(@PathVariable String id, @RequestBody Map<String, Object> body) {
    return memories.change(
        id,
        Database.required(body, "status", 20),
        Database.number(body, "version", 0, 1, Integer.MAX_VALUE));
  }

  @PostMapping("/memory-records/{id}/pin")
  public Object pin(@PathVariable String id, @RequestBody Map<String, Object> body) {
    memories.pin(id, Database.bool(body, "pinned", false));
    return memories.get(id);
  }

  @GetMapping("/memory-records/{id}/history")
  public Object history(@PathVariable String id) {
    return memories.history(id);
  }
}
