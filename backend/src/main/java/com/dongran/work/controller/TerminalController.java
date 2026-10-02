package com.dongran.work.controller;

import com.dongran.work.service.TerminalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/terminals")
public class TerminalController {
  private final TerminalService service;

  public TerminalController(TerminalService service) {
    this.service = service;
  }

  public record Create(
      @NotBlank String projectId,
      @NotBlank String profile,
      @Min(2) @Max(500) int cols,
      @Min(1) @Max(300) int rows,
      boolean confirmed) {}

  public record Input(@NotNull @Size(max = 65536) String data) {}

  public record Resize(@Min(2) @Max(500) int cols, @Min(1) @Max(300) int rows) {}

  public record Rename(@NotBlank @Size(max = 50) String name) {}

  @GetMapping
  public Object list() {
    return service.list();
  }

  @GetMapping("/profiles")
  public Object profiles() {
    return service.profiles();
  }

  @PostMapping
  public Object create(@Valid @RequestBody Create body) {
    return service.create(
        body.projectId(), body.profile(), body.cols(), body.rows(), body.confirmed());
  }

  @PostMapping("/poll")
  public Object poll(@RequestBody Map<String, Long> cursors) {
    return service.poll(cursors);
  }

  @PostMapping("/{id}/input")
  public Object input(@PathVariable String id, @Valid @RequestBody Input body) {
    service.input(id, body.data());
    return Map.of("ok", true);
  }

  @PostMapping("/{id}/resize")
  public Object resize(@PathVariable String id, @Valid @RequestBody Resize body) {
    service.resize(id, body.cols(), body.rows());
    return Map.of("ok", true);
  }

  @PatchMapping("/{id}")
  public Object rename(@PathVariable String id, @Valid @RequestBody Rename body) {
    service.rename(id, body.name());
    return Map.of("ok", true);
  }

  @DeleteMapping("/{id}")
  public Object close(@PathVariable String id) {
    service.close(id);
    return Map.of("ok", true);
  }

  @DeleteMapping
  public Object closeAll() {
    service.closeAll();
    return Map.of("ok", true);
  }
}
