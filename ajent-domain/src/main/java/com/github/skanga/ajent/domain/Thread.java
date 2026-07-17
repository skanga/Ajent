package com.github.skanga.ajent.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record Thread(
    ThreadId id, String title, List<Message> messages,
    Instant createdAt, Instant updatedAt, List<CompactionRecord> compactions) {
  public Thread(ThreadId id, String title, List<Message> messages) {
    this(id, title, messages, Instant.now(), Instant.now(), List.of());
  }

  public Thread {
    id = Objects.requireNonNull(id, "id");
    title = Objects.requireNonNull(title, "title");
    messages = List.copyOf(messages);
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    compactions = List.copyOf(compactions);
  }
}
