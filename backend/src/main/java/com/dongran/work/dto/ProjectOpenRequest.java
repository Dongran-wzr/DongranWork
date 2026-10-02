package com.dongran.work.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectOpenRequest(@NotBlank @Size(max = 2000) String path) {}
