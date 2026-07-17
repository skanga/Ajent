package com.github.skanga.ajent.domain;

import java.util.Map;
import java.util.Objects;

public record ToolUse(
    ToolCallId id, ToolName name, Map<String, Object> arguments, ToolStatus status) {
  public ToolUse {
    id = Objects.requireNonNull(id, "id");
    name = Objects.requireNonNull(name, "name");
    arguments = Map.copyOf(arguments);
    status = Objects.requireNonNull(status, "status");
  }
}
