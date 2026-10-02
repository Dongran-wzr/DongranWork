package com.dongran.work.controller;

import com.dongran.work.infrastructure.SandboxExecutor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sandbox")
public class SandboxController {
  private final SandboxExecutor sandbox;

  public SandboxController(SandboxExecutor sandbox) {
    this.sandbox = sandbox;
  }

  @GetMapping("/status")
  public Object status(@RequestParam(defaultValue = "false") boolean refresh) {
    return sandbox.status(refresh);
  }
}
