package com.dongran.work.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FileWriteRequest(
    @NotBlank @Size(max = 1000) String path,
    @NotNull @Size(max = 1_000_000) String content,
    String expectedSha256,
    Boolean confirmed) {}
