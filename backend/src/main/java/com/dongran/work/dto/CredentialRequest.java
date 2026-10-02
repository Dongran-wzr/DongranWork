package com.dongran.work.dto;

import jakarta.validation.constraints.*;

public record CredentialRequest(@NotBlank @Size(max = 16000) String value, Boolean remember) {}
