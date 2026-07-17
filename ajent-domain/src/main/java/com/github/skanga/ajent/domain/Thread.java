package com.github.skanga.ajent.domain;

import java.util.List;
import java.util.Objects;

public record Thread(ThreadId id, String title, List<Message> messages) {
  public Thread {
    id = Objects.requireNonNull(id, "id");
    title = Objects.requireNonNull(title, "title");
    messages = List.copyOf(messages);
  }
}
