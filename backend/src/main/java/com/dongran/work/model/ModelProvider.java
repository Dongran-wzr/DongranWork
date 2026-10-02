package com.dongran.work.model;

public record ModelProvider(
    String id,
    String name,
    String kind,
    String baseUrl,
    String modelName,
    String notes,
    double temperature,
    int contextWindow,
    String credentialId,
    boolean active,
    long revision,
    String createdAt,
    String updatedAt,
    String testedAt,
    Long latencyMs,
    String testError) {}
