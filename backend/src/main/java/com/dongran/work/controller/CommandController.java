package com.dongran.work.controller;

import com.dongran.work.dto.CommandRequest;
import com.dongran.work.exception.ApiException;
import com.dongran.work.service.CommandService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CommandController {
  private final CommandService commands;

  public CommandController(CommandService commands) {
    this.commands = commands;
  }

  @PostMapping("/commands")
  public Object command(@Valid @RequestBody CommandRequest body) {
    if (!Boolean.TRUE.equals(body.confirmed())) throw ApiException.forbidden("此操作需要用户明确确认。");
    String id =
        commands.start(
            body.projectId(), null, body.command(), body.timeout() == null ? 120 : body.timeout());
    return commands.get(id);
  }

  @GetMapping("/commands/{id}")
  public Object command(@PathVariable String id) {
    return commands.get(id);
  }

  @PostMapping("/commands/{id}/cancel")
  public Object cancelCommand(@PathVariable String id) {
    commands.cancel(id);
    return commands.get(id);
  }
}
