package com.github.skanga.ajent.tools.runtime;

import java.util.Objects;

/** One structured unified-diff hunk using Ajent's one-based coordinates. */
public record DiffHunk(
    int oldStart, int oldLength, int newStart, int newLength, String patch, Status status) {
  public enum Status { PENDING, ACCEPTED, REJECTED }

  public DiffHunk(int oldStart, int oldLength, int newStart, int newLength, String patch) {
    this(oldStart, oldLength, newStart, newLength, patch, Status.PENDING);
  }

  public DiffHunk {
    if (oldStart < 1 || oldLength < 0 || newStart < 1 || newLength < 0) {
      throw new IllegalArgumentException("invalid diff hunk coordinates");
    }
    patch = Objects.requireNonNull(patch, "patch");
    status = Objects.requireNonNull(status, "status");
  }

  public DiffHunk withStatus(Status value) {
    return new DiffHunk(oldStart, oldLength, newStart, newLength, patch, value);
  }
}
