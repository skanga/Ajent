package com.github.skanga.ajent.tools.runtime;

import java.util.Objects;

public sealed interface ToolResult {
  record Success(ToolOutput output) implements ToolResult {
    public Success { output = Objects.requireNonNull(output, "output"); }
  }
  record Failure(ToolError error) implements ToolResult {
    public Failure { error = Objects.requireNonNull(error, "error"); }
  }
}
