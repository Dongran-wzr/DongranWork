package com.dongran.work.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ScheduleWriteRequest(
    @NotBlank @Size(max = 80) String name,
    @NotBlank @Size(max = 4000) String prompt,
    String scope,
    String projectId,
    String projectName,
    String frequency,
    String time,
    List<Integer> weekdays,
    String date,
    @NotNull Boolean enabled,
    String createdAt,
    String updatedAt) {}
