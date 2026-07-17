package com.github.skanga.ajent.runtime;

import java.util.Objects;

public sealed interface ToolCompletion {
  record Success(String output) implements ToolCompletion {
    public Success { output = Objects.requireNonNull(output, "output"); }
  }
  record Failure(String error) implements ToolCompletion {
    public Failure { error = Objects.requireNonNull(error, "error"); }
  }
}
