package com.dongran.work.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record KnowledgeRetrievalRequest(
    @Min(0) long revision,
    @NotBlank @Pattern(regexp = "bm25|hybrid|vector_bm25") String mode,
    @Min(5) @Max(50) int candidates,
    @Min(1) @Max(20) int topK,
    @NotNull @Valid Endpoint embedding,
    @NotNull @Valid Endpoint rerank) {
  public record Endpoint(
      boolean enabled,
      String consentTarget,
      @Size(max = 2048) String baseUrl,
      @Size(max = 200) String model,
      @Size(max = 100) String providerId,
      @Size(max = 16000) String apiKey,
      Boolean rememberKey,
      boolean clearKey) {}
}
