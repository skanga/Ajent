package com.github.skanga.ajent.domain;

import java.util.Objects;

public record ToolCallId(String value) {
  public ToolCallId { value = Objects.requireNonNull(value, "value"); }
}
