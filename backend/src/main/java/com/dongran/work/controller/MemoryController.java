package com.dongran.work.controller;

import com.dongran.work.service.MemoryService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class MemoryController {
  private final MemoryService memories;

  public MemoryController(MemoryService memories) {
    this.memories = memories;
  }

  @GetMapping("/memories")
  public Object memories(
      @RequestParam(required = false) String projectId,
      @RequestParam(defaultValue = "false") boolean effective) {
    return memories.list(projectId, effective);
  }

  @PostMapping("/memories")
  public Object addMemory(@RequestBody Map<String, Object> body) {
    return memories.save(null, body);
  }

  @PutMapping("/memories/{id}")
  public Object editMemory(@PathVariable String id, @RequestBody Map<String, Object> body) {
    return memories.save(id, body);
  }

  @DeleteMapping("/memories/{id}")
  public Object deleteMemory(@PathVariable String id) {
    memories.delete(id);
    return Map.of("deleted", true);
  }
}
