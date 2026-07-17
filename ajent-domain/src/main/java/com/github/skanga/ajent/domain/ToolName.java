package com.github.skanga.ajent.domain;

import java.util.Objects;

public record ToolName(String value) {
  public ToolName { value = Objects.requireNonNull(value, "value"); }
}
