package com.dongran.work.controller;

import com.dongran.work.dto.KnowledgeRetrievalRequest;
import com.dongran.work.service.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeRetrievalController {
  private final KnowledgeModelSettings settings;
  private final KnowledgeRetrievalService retrieval;
  private final KnowledgeModelClient models;

  public KnowledgeRetrievalController(
      KnowledgeModelSettings settings,
      KnowledgeRetrievalService retrieval,
      KnowledgeModelClient models) {
    this.settings = settings;
    this.models = models;
    this.retrieval = retrieval;
  }

  @GetMapping("/retrieval/settings")
  public Object settings() {
    return settings.view();
  }

  @PutMapping("/retrieval/settings")
  public Object settings(@Valid @RequestBody KnowledgeRetrievalRequest body) {
    return settings.save(body);
  }

  @PostMapping("/retrieval/test/{kind}")
  public Object test(
      @PathVariable String kind, @Valid @RequestBody KnowledgeRetrievalRequest.Endpoint body) {
    return settings.test(kind, body, models);
  }

  @PostMapping("/{id}/index")
  public Object index(@PathVariable String id) {
    return retrieval.buildIndex(id);
  }

  @GetMapping("/{id}/index")
  public Object indexStatus(@PathVariable String id) {
    return retrieval.indexStatus(id);
  }

  @GetMapping("/search")
  public Object search(
      @RequestParam String query, @RequestParam(required = false) String projectId) {
    return retrieval.search(projectId, query, settings.get().topK());
  }
}
