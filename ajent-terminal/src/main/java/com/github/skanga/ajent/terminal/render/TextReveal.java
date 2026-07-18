package com.github.skanga.ajent.terminal.render;

import java.util.Objects;

/** Stateful UTF-16-safe text reveal driven by AgenTTY's rate cursor. */
public final class TextReveal {
  private static final double FRAME_GAP_CAP_SECONDS = 0.250;

  private final RateCursor cursor;
  private final double finalizeSeconds;
  private String source = "";
  private long lastNanos;
  private boolean live;

  public TextReveal() {
    this(90, 0.15, 0.16);
  }

  public TextReveal(double floorRate, double drainSeconds, double finalizeSeconds) {
    cursor = new RateCursor(floorRate, drainSeconds);
    this.finalizeSeconds = finalizeSeconds > 0 ? finalizeSeconds : 0.001;
  }

  /** Starts a new text block, revealing existing settled text immediately. */
  public Frame begin(String text, boolean streaming, long nowNanos) {
    source = Objects.requireNonNull(text, "text");
    cursor.reset();
    lastNanos = nowNanos;
    live = streaming;
    int total = source.codePointCount(0, source.length());
    if (!streaming) cursor.setPosition(total);
    return frame(total);
  }

  /** Reconciles a streaming snapshot and advances the cursor by wall-clock time. */
  public Frame update(String text, boolean streaming, long nowNanos) {
    Objects.requireNonNull(text, "text");
    int previousTotal = source.codePointCount(0, source.length());
    int total = text.codePointCount(0, text.length());
    if (total < previousTotal || !text.startsWith(source)) {
      source = text;
      cursor.reset();
      if (!streaming) cursor.setPosition(total);
      lastNanos = nowNanos;
      live = streaming;
      return frame(total);
    }
    source = text;
    if (live && !streaming) cursor.setDeadline(finalizeSeconds);
    else if (streaming) cursor.clearDeadline();
    live = streaming;
    double elapsed = Math.max(0, nowNanos - lastNanos) / 1_000_000_000.0;
    cursor.tick(total, Math.min(FRAME_GAP_CAP_SECONDS, elapsed));
    lastNanos = nowNanos;
    return frame(total);
  }

  private Frame frame(int total) {
    int revealed = Math.min(total, Math.max(0, (int) cursor.position()));
    int end = source.offsetByCodePoints(0, revealed);
    return new Frame(source.substring(0, end), revealed < total, revealed, total);
  }

  public record Frame(String text, boolean animating, int revealedCodePoints,
                      int totalCodePoints) {
    public Frame {
      text = Objects.requireNonNull(text, "text");
      if (revealedCodePoints < 0 || totalCodePoints < revealedCodePoints) {
        throw new IllegalArgumentException("invalid reveal bounds");
      }
    }
  }
}
