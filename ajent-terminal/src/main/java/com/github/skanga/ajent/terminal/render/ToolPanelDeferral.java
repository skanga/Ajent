package com.github.skanga.ajent.terminal.render;

import java.time.Duration;
import java.util.Objects;

/** Holds a tool panel behind the preceding text reveal, with a bounded recovery snap. */
public final class ToolPanelDeferral {
  public static final Duration MAXIMUM = Duration.ofMillis(1_500);

  private Object boundary;
  private long startedNanos;
  private boolean released;

  public enum Decision {
    SHOW,
    HOLD,
    SNAP_AND_SHOW
  }

  /** Determines whether the current boundary may expose its tool panel. */
  public Decision next(Object boundaryKey, boolean hasTools, boolean revealInProgress,
      long nowNanos) {
    if (!hasTools) {
      reset();
      return Decision.SHOW;
    }
    Objects.requireNonNull(boundaryKey, "boundaryKey");
    if (!boundaryKey.equals(boundary)) {
      boundary = boundaryKey;
      startedNanos = nowNanos;
      released = false;
    }
    if (released) return Decision.SHOW;
    if (!revealInProgress) {
      released = true;
      return Decision.SHOW;
    }
    if (Math.max(0, nowNanos - startedNanos) >= MAXIMUM.toNanos()) {
      released = true;
      return Decision.SNAP_AND_SHOW;
    }
    return Decision.HOLD;
  }

  public void reset() {
    boundary = null;
    startedNanos = 0;
    released = false;
  }
}
