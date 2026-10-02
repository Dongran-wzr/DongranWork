package com.dongran.work.dto;

import jakarta.validation.constraints.*;

public record ModelProviderRequest(
    @NotBlank @Size(max = 80) String name,
    @NotBlank @Pattern(regexp = "cloud|local|custom") String kind,
    @NotBlank @Size(max = 2048) String baseUrl,
    @NotBlank @Size(max = 200) String modelName,
    @Size(max = 500) String notes,
    @NotNull @DecimalMin("0") @DecimalMax("2") Double temperature,
    @NotNull @Min(4096) @Max(131072) Integer contextWindow,
    @Size(max = 16000) String apiKey,
    Boolean clearKey,
    Boolean rememberKey,
    Long revision) {}
