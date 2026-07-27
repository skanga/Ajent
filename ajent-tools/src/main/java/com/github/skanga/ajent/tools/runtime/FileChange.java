package com.github.skanga.ajent.tools.runtime;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Objects;

public record FileChange(
    String path, int added, int removed, String before, String after, List<DiffHunk> hunks,
    @JsonIgnore boolean existedBefore) {
  public FileChange(String path, int added, int removed, String before, String after) {
    this(path, added, removed, before, after, UnifiedDiff.hunks(before, after), !before.isEmpty());
  }

  public FileChange(
      String path, int added, int removed, String before, String after, List<DiffHunk> hunks) {
    this(path, added, removed, before, after, hunks, !before.isEmpty());
  }

  public FileChange {
    path = Objects.requireNonNull(path, "path");
    before = Objects.requireNonNull(before, "before");
    after = Objects.requireNonNull(after, "after");
    hunks = List.copyOf(hunks);
  }

  public FileChange withHunks(List<DiffHunk> value) {
    return new FileChange(path, added, removed, before, after, value, existedBefore);
  }
}
