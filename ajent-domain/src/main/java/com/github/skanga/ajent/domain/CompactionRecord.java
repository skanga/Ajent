package com.github.skanga.ajent.domain;

import java.time.Instant;
import java.util.Objects;

public record CompactionRecord(int upToIndex, String summary, Instant createdAt) {
  public CompactionRecord {
    if (upToIndex < 0) throw new IllegalArgumentException("upToIndex cannot be negative");
    summary = Objects.requireNonNull(summary, "summary");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }
}
