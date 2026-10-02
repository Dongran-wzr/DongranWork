package com.dongran.work.dto;

import jakarta.validation.constraints.*;

public record KnowledgeWriteRequest(
    @Size(max = 100) String projectId,
    @NotBlank @Size(max = 200) String name,
    @NotBlank @Size(max = 1_000_000) String content) {}
