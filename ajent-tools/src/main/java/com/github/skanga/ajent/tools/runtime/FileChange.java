package com.github.skanga.ajent.tools.runtime;

import java.util.Objects;

public record FileChange(String path, int added, int removed, String before, String after) {
  public FileChange {
    path = Objects.requireNonNull(path, "path");
    before = Objects.requireNonNull(before, "before");
    after = Objects.requireNonNull(after, "after");
  }
}
