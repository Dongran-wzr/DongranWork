package com.dongran.work.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TaskCreateRequest(
    @NotBlank @Size(max = 16000) String prompt,
    String projectId,
    String mode,
    String scheduleId,
    @Size(max = 3) java.util.List<String> skillIds,
    Boolean autoSkills) {}
