package com.github.skanga.ajent.tools.runtime;

import java.util.Objects;

public record ToolError(ToolErrorKind kind, String detail) {
  public ToolError {
    kind = Objects.requireNonNull(kind, "kind");
    detail = Objects.requireNonNull(detail, "detail");
  }

  public String render() { return "[" + kind + "] " + detail; }
}
