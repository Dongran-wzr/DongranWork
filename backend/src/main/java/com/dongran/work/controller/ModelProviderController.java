package com.dongran.work.controller;

import com.dongran.work.dto.ModelProviderRequest;
import com.dongran.work.infrastructure.ModelClient;
import com.dongran.work.service.ModelProviderService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/model-providers")
public class ModelProviderController {
  private final ModelProviderService providers;
  private final ModelClient model;
  private final com.dongran.work.service.ModelCapabilityService capabilities;

  public ModelProviderController(
      ModelProviderService providers,
      ModelClient model,
      com.dongran.work.service.ModelCapabilityService capabilities) {
    this.providers = providers;
    this.capabilities = capabilities;
    this.model = model;
  }

  @GetMapping
  public Object list() {
    return providers.list();
  }

  @PostMapping
  public Object add(@Valid @RequestBody ModelProviderRequest body) {
    return providers.save(null, body);
  }

  @PutMapping("/{id}")
  public Object edit(@PathVariable String id, @Valid @RequestBody ModelProviderRequest body) {
    return providers.save(id, body);
  }

  @PostMapping("/{id}/activate")
  public Object activate(@PathVariable String id) {
    return providers.activate(id);
  }

  @PostMapping("/{id}/duplicate")
  public Object duplicate(@PathVariable String id) {
    return providers.duplicate(id);
  }

  @DeleteMapping("/{id}")
  public Object delete(@PathVariable String id) {
    providers.delete(id);
    return Map.of("deleted", true);
  }

  @GetMapping("/{id}/capabilities")
  public Object capabilities(@PathVariable String id) {
    return capabilities.view(id);
  }

  @PostMapping("/{id}/capabilities")
  public Object testCapabilities(@PathVariable String id) throws Exception {
    return capabilities.test(id);
  }

  @PostMapping("/{id}/test")
  public Object test(@PathVariable String id) throws Exception {
    var configuration = providers.configuration(id);
    long start = System.nanoTime();
    try {
      var result =
          model.completeWithConfiguration(
              configuration,
              List.of(Map.of("role", "user", "content", "请只回复 OK。")),
              List.of(),
              text -> {});
      long elapsed = (System.nanoTime() - start) / 1_000_000;
      providers.tested(id, configuration.revision(), elapsed, null);
      return Map.of("connected", true, "elapsedMs", elapsed, "reply", result.text());
    } catch (Exception e) {
      String message =
          e instanceof com.dongran.work.exception.ApiException
              ? e.getMessage()
              : "连接失败，请检查服务地址和网络。";
      providers.tested(
          id, configuration.revision(), (System.nanoTime() - start) / 1_000_000, message);
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw com.dongran.work.exception.ApiException.bad(message);
    }
  }
}
