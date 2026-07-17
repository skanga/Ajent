package com.github.skanga.ajent.tools.runtime;

import java.util.Objects;
import java.util.Optional;

public record ToolOutput(String text, Optional<FileChange> change) {
  public ToolOutput {
    text = Objects.requireNonNull(text, "text");
    change = Objects.requireNonNull(change, "change");
  }

  public ToolOutput(String text) { this(text, Optional.empty()); }
}
