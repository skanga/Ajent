package com.github.skanga.ajent.domain;

import java.util.Objects;
import java.util.Optional;

/** Four-state request lifecycle with active context physically absent from idle. */
public sealed interface SessionPhase {
  enum Kind { IDLE, STREAMING, AWAITING_PERMISSION, EXECUTING_TOOL }

  record Idle() implements SessionPhase {}
  record Streaming(ActiveTurn context) implements SessionPhase {
    public Streaming { Objects.requireNonNull(context, "context"); }
  }
  record AwaitingPermission(ActiveTurn context) implements SessionPhase {
    public AwaitingPermission { Objects.requireNonNull(context, "context"); }
  }
  record ExecutingTool(ActiveTurn context) implements SessionPhase {
    public ExecutingTool { Objects.requireNonNull(context, "context"); }
  }

  default Kind kind() {
    return switch (this) {
      case Idle ignored -> Kind.IDLE;
      case Streaming ignored -> Kind.STREAMING;
      case AwaitingPermission ignored -> Kind.AWAITING_PERMISSION;
      case ExecutingTool ignored -> Kind.EXECUTING_TOOL;
    };
  }

  default String label() {
    return switch (this) {
      case Idle ignored -> "idle";
      case Streaming ignored -> "streaming";
      case AwaitingPermission ignored -> "permission";
      case ExecutingTool ignored -> "working";
    };
  }

  default Optional<ActiveTurn> active() {
    return switch (this) {
      case Idle ignored -> Optional.empty();
      case Streaming value -> Optional.of(value.context());
      case AwaitingPermission value -> Optional.of(value.context());
      case ExecutingTool value -> Optional.of(value.context());
    };
  }

  static boolean isLegalTransition(Kind from, Kind to) {
    if (to == Kind.IDLE) return true;
    return from == Kind.IDLE && to == Kind.STREAMING
        || from == Kind.STREAMING && to == Kind.AWAITING_PERMISSION
        || from == Kind.AWAITING_PERMISSION && to == Kind.EXECUTING_TOOL
        || from == Kind.EXECUTING_TOOL && to == Kind.STREAMING;
  }

  static Streaming start(Idle source, ActiveTurn active) {
    Objects.requireNonNull(source, "source");
    return new Streaming(active);
  }

  static AwaitingPermission landPermission(Streaming source) {
    return new AwaitingPermission(source.context());
  }

  static ExecutingTool executeTool(AwaitingPermission source) {
    return new ExecutingTool(source.context());
  }

  static Streaming resumeStream(ExecutingTool source) {
    return new Streaming(source.context());
  }

  static Idle finish(Streaming source) {
    Objects.requireNonNull(source, "source");
    return new Idle();
  }

  static Idle reject(AwaitingPermission source) {
    Objects.requireNonNull(source, "source");
    return new Idle();
  }

  static Idle doneTool(ExecutingTool source) {
    Objects.requireNonNull(source, "source");
    return new Idle();
  }

  static Idle abort(SessionPhase source) {
    if (source instanceof Idle) throw new IllegalArgumentException("idle has no active turn");
    return new Idle();
  }
}
