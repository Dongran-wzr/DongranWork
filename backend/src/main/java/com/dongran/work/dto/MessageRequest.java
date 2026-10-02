package com.dongran.work.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MessageRequest(
    @NotBlank @Size(max = 16000) String prompt,
    @Size(max = 3) java.util.List<String> skillIds,
    Boolean autoSkills) {}
