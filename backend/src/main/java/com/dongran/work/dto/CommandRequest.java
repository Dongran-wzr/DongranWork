package com.dongran.work.dto;

import jakarta.validation.constraints.*;

public record CommandRequest(
    @NotBlank @Size(max = 100) String projectId,
    @NotBlank @Size(max = 4000) String command,
    @Min(1) @Max(3600) Integer timeout,
    Boolean confirmed) {}
