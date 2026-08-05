package com.github.skanga.ajent.core.workspace;

import java.util.Objects;

/** One workspace declaration exposed by Ajent's #symbol picker. */
public record WorkspaceSymbol(String name, String path, int lineNumber) {
  public WorkspaceSymbol {
    name = Objects.requireNonNull(name, "name");
    path = Objects.requireNonNull(path, "path");
    if (lineNumber < 1) throw new IllegalArgumentException("lineNumber must be positive");
  }
}
