package com.dongran.work.controller;

import com.dongran.work.service.ConnectionService;
import com.dongran.work.service.ExtensionService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class IntegrationController {
  private final ConnectionService connections;
  private final ExtensionService extensions;

  public IntegrationController(ConnectionService connections, ExtensionService extensions) {
    this.connections = connections;
    this.extensions = extensions;
  }

  @PostMapping("/connections/{id}/test")
  public Object testConnection(@PathVariable String id) throws Exception {
    return connections.test(id);
  }

  @GetMapping("/connections/{id}/tools")
  public Object connectionTools(@PathVariable String id) throws Exception {
    return connections.tools(id);
  }

  @GetMapping("/extensions")
  public Object extensions() {
    return extensions.extensions();
  }

  @PostMapping("/extensions/{id}/run")
  public Object run(@PathVariable String id, @RequestBody Map<String, Object> body) {
    return extensions.run(id, body);
  }
}
