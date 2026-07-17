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

  record Pending() implements ToolStatus {}
  record Approved() implements ToolStatus {}
  record Running(String progressText) implements ToolStatus {
    public Running { progressText = Objects.requireNonNull(progressText, "progressText"); }
  }
  record Done(String output) implements ToolStatus {
    public Done { output = Objects.requireNonNull(output, "output"); }
  }
  record Failed(String output) implements ToolStatus {
    public Failed { output = Objects.requireNonNull(output, "output"); }
  }
  record Rejected() implements ToolStatus {}
}
