package com.github.skanga.ajent.domain;

import java.util.Objects;

public sealed interface ToolStatus {
  default boolean isTerminal() {
    return this instanceof Done || this instanceof Failed || this instanceof Rejected;
  }

  default boolean isError() {
    return !isTerminal() || this instanceof Failed || this instanceof Rejected;
  }

  default String output() {
    return switch (this) {
      case Done done -> done.output();
      case Failed failed -> failed.output();
      default -> "";
    };
  }

  long startedNanos();

  long finishedNanos();

  record Pending(long startedNanos) implements ToolStatus {
    public Pending() { this(0); }
    public Pending { requireMonotonic(startedNanos); }
    @Override public long finishedNanos() { return 0; }
  }

  record Approved(long startedNanos) implements ToolStatus {
    public Approved() { this(0); }
    public Approved { requireMonotonic(startedNanos); }
    @Override public long finishedNanos() { return 0; }
  }

  record Running(long startedNanos, String progressText) implements ToolStatus {
    public Running(String progressText) { this(0, progressText); }
    public Running {
      requireMonotonic(startedNanos);
      progressText = Objects.requireNonNull(progressText, "progressText");
    }
    @Override public long finishedNanos() { return 0; }
  }

  record Done(long startedNanos, long finishedNanos, String output) implements ToolStatus {
    public Done(String output) { this(0, 0, output); }
    public Done {
      requireMonotonic(startedNanos);
      requireMonotonic(finishedNanos);
      output = Objects.requireNonNull(output, "output");
    }
  }

  record Failed(long startedNanos, long finishedNanos, String output) implements ToolStatus {
    public Failed(String output) { this(0, 0, output); }
    public Failed {
      requireMonotonic(startedNanos);
      requireMonotonic(finishedNanos);
      output = Objects.requireNonNull(output, "output");
    }
  }

  record Rejected(long finishedNanos) implements ToolStatus {
    public Rejected() { this(0); }
    public Rejected { requireMonotonic(finishedNanos); }
    @Override public long startedNanos() { return 0; }
  }

  private static void requireMonotonic(long nanos) {
    if (nanos < 0) throw new IllegalArgumentException("monotonic time cannot be negative");
  }
}
