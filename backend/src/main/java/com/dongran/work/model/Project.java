package com.dongran.work.model;

/** A registered local project; paths are canonicalized when it is opened. */
public record Project(String id, String name, String path, String createdAt, String openedAt) {}
