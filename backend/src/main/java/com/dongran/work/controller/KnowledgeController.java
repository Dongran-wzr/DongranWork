package com.dongran.work.controller;

import com.dongran.work.dto.KnowledgeWriteRequest;
import com.dongran.work.service.KnowledgeService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class KnowledgeController {
  private final KnowledgeService knowledge;

  public KnowledgeController(KnowledgeService knowledge) {
    this.knowledge = knowledge;
  }

  @GetMapping("/knowledge")
  public Object knowledge(
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) String query) {
    return query == null ? knowledge.list(projectId) : knowledge.search(projectId, query);
  }

  @GetMapping("/knowledge/{id}")
  public Object knowledgeDocument(@PathVariable String id) {
    return knowledge.get(id);
  }

  @GetMapping("/knowledge/{id}/preview")
  public Object preview(@PathVariable String id) {
    return knowledge.preview(id);
  }

  @GetMapping("/knowledge/{id}/fragments/{chunkId}")
  public Object fragment(@PathVariable String id, @PathVariable String chunkId) {
    return knowledge.fragment(id, chunkId);
  }

  @PostMapping("/knowledge/{id}/reextract")
  public Object reextract(@PathVariable String id) {
    return knowledge.reextract(id);
  }

  @GetMapping("/knowledge/{id}/source")
  public org.springframework.http.ResponseEntity<byte[]> source(@PathVariable String id) {
    var row = knowledge.get(id);
    byte[] bytes = knowledge.source(id);
    return org.springframework.http.ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(String.valueOf(row.get("sourceMime"))))
        .header(
            "Content-Disposition",
            org.springframework.http.ContentDisposition.attachment()
                .filename(
                    String.valueOf(row.get("sourceName")), java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(bytes);
  }

  @PostMapping("/knowledge")
  public Object addKnowledge(@Valid @RequestBody KnowledgeWriteRequest body) {
    return knowledge.save(null, body.projectId(), body.name(), body.content());
  }

  @PutMapping("/knowledge/{id}")
  public Object editKnowledge(
      @PathVariable String id, @Valid @RequestBody KnowledgeWriteRequest body) {
    var old = knowledge.get(id);
    return knowledge.save(id, (String) old.get("projectId"), body.name(), body.content());
  }

  @PostMapping(value = "/knowledge/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Object upload(
      @RequestParam(required = false) String projectId, @RequestParam MultipartFile file)
      throws IOException {
    return knowledge.upload(projectId, file);
  }

  @DeleteMapping("/knowledge/{id}")
  public Object deleteKnowledge(@PathVariable String id) {
    knowledge.delete(id);
    return Map.of("deleted", true);
  }
}
