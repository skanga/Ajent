package com.github.skanga.ajent.runtime;

import java.util.Objects;
import java.util.Optional;
import com.github.skanga.ajent.tools.runtime.FileChange;

public sealed interface ToolCompletion {
  record Success(String output, Optional<FileChange> change) implements ToolCompletion {
    public Success {
      output = Objects.requireNonNull(output, "output");
      change = Objects.requireNonNull(change, "change");
    }
    public Success(String output) { this(output, Optional.empty()); }
  }
  record Failure(String error) implements ToolCompletion {
    public Failure { error = Objects.requireNonNull(error, "error"); }
  }
}
