package com.dongran.work.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record GitOperationRequest(
    @NotBlank String operation,
    List<@NotBlank @Size(max = 2000) String> paths,
    @Size(max = 4000) String message,
    @Size(max = 200) String branch,
    @Size(max = 200) String base,
    String fingerprint,
    Boolean confirmed) {}
