package com.dongran.work.controller;

import com.dongran.work.dto.CredentialRequest;
import com.dongran.work.infrastructure.CredentialService;
import com.dongran.work.infrastructure.ModelClient;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ModelController {
  private final ModelClient model;
  private final CredentialService credentials;

  public ModelController(ModelClient model, CredentialService credentials) {
    this.model = model;
    this.credentials = credentials;
  }

  @GetMapping("/models/status")
  public Object models() {
    return model.status();
  }

  @PutMapping("/credentials/{id}")
  public Object credential(@PathVariable String id, @Valid @RequestBody CredentialRequest body) {
    return credentials.save(id, body.value(), !Boolean.FALSE.equals(body.remember()));
  }

  @DeleteMapping("/credentials/{id}")
  public Object deleteCredential(@PathVariable String id) {
    credentials.delete(id);
    return Map.of("deleted", true);
  }

  @PostMapping("/models/test")
  public Object testModel() throws Exception {
    long start = System.nanoTime();
    var result =
        model.complete(
            List.of(Map.of("role", "user", "content", "请只回复 OK。")), List.of(), delta -> {});
    return Map.of(
        "connected",
        true,
        "reply",
        result.text(),
        "elapsedMs",
        (System.nanoTime() - start) / 1_000_000);
  }
}
