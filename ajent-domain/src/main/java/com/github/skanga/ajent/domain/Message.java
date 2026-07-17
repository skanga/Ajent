package com.github.skanga.ajent.domain;

import java.util.List;
import java.util.Objects;

public record Message(Role role, String text, List<ImageContent> images, List<ToolUse> toolCalls) {
  public Message {
    role = Objects.requireNonNull(role, "role");
    text = Objects.requireNonNull(text, "text");
    images = List.copyOf(images);
    toolCalls = List.copyOf(toolCalls);
  }
}
