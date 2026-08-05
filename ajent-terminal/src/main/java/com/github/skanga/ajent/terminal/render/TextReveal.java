package com.github.skanga.ajent.terminal.render;

import java.util.Objects;

/** Stateful UTF-16-safe text reveal driven by Ajent's rate cursor. */
public final class TextReveal {
  private static final double FRAME_GAP_CAP_SECONDS = 0.250;

  private final RateCursor cursor;
  private double finalizeSeconds;
  private String source = "";
  private long lastNanos;
  private long lastRevealNanos;
  private int lastRevealed;
  private boolean live;
  private boolean effects;

  public TextReveal() {
    this(90, 0.15, 0.16);
  }

  public TextReveal(double floorRate, double drainSeconds, double finalizeSeconds) {
    cursor = new RateCursor(floorRate, drainSeconds);
    this.finalizeSeconds = finalizeSeconds > 0 ? finalizeSeconds : 0.001;
  }

  /** Updates live reveal pacing without discarding the current cursor position. */
  public void setPacing(double floorRate, double drainSeconds) {
    cursor.setPacing(floorRate, drainSeconds);
  }

  /** Changes the deadline used by the next live-to-finalizing transition. */
  public void setFinalizeSeconds(double seconds) {
    if (!Double.isFinite(seconds) || seconds <= 0) {
      throw new IllegalArgumentException("finalize seconds must be positive and finite");
    }
    finalizeSeconds = seconds;
  }

  /** Starts a new text block, revealing existing settled text immediately. */
  public Frame begin(String text, boolean streaming, long nowNanos) {
    source = Objects.requireNonNull(text, "text");
    cursor.reset();
    lastNanos = nowNanos;
    lastRevealNanos = nowNanos;
    live = streaming;
    effects = streaming;
    int total = source.codePointCount(0, source.length());
    if (!streaming) cursor.setPosition(total);
    lastRevealed = streaming ? 0 : total;
    return frame(total, nowNanos);
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
      lastRevealNanos = nowNanos;
      live = streaming;
      effects = streaming;
      lastRevealed = streaming ? 0 : total;
      return frame(total, nowNanos);
    }
    source = text;
    if (live && !streaming) cursor.setDeadline(finalizeSeconds);
    else if (streaming) {
      cursor.clearDeadline();
      effects = true;
    }
    live = streaming;
    double elapsed = Math.max(0, nowNanos - lastNanos) / 1_000_000_000.0;
    cursor.tick(total, Math.min(FRAME_GAP_CAP_SECONDS, elapsed));
    lastNanos = nowNanos;
    return frame(total, nowNanos);
  }

  /** Reveals the complete source immediately and disables residual edge animation. */
  public Frame snapToEdge(long nowNanos) {
    int total = source.codePointCount(0, source.length());
    cursor.setPosition(total);
    cursor.clearDeadline();
    lastNanos = nowNanos;
    lastRevealNanos = nowNanos;
    lastRevealed = total;
    live = false;
    effects = false;
    return frame(total, nowNanos);
  }

  private Frame frame(int total, long nowNanos) {
    int revealed = Math.min(total, Math.max(0, (int) cursor.position()));
    if (revealed != lastRevealed) {
      lastRevealNanos = nowNanos;
      lastRevealed = revealed;
    }
    int end = source.offsetByCodePoints(0, revealed);
    long edgeAgeMillis = Math.max(0, nowNanos - lastRevealNanos) / 1_000_000;
    return new Frame(source.substring(0, end), revealed < total, revealed, total,
        source, nowNanos / 1_000_000, edgeAgeMillis, live, effects);
  }

  public record Frame(String text, boolean animating, int revealedCodePoints,
                      int totalCodePoints, String source, long totalMillis,
                      long edgeAgeMillis, boolean live, boolean effects) {
    private static final long SCRAMBLE_SETTLE_MILLIS = 220 + 6 * 26;
    private static final long CARET_PULSE_WINDOW_MILLIS = 4_000;

    public Frame(String text, boolean animating, int revealedCodePoints, int totalCodePoints) {
      this(text, animating, revealedCodePoints, totalCodePoints, text, 0, 1_000, false, false);
    }

    public Frame {
      text = Objects.requireNonNull(text, "text");
      source = Objects.requireNonNull(source, "source");
      if (revealedCodePoints < 0 || totalCodePoints < revealedCodePoints) {
        throw new IllegalArgumentException("invalid reveal bounds");
      }
    }

    public TextRevealEffect.Decoration visual(TerminalStyle base) {
      boolean active = effects && (live || animating
          || edgeAgeMillis < SCRAMBLE_SETTLE_MILLIS);
      TextRevealEffect.Parameters parameters = TextRevealEffect.Parameters.defaults(
          totalMillis, active ? edgeAgeMillis : 1_000,
          active ? revealedCodePoints : totalCodePoints, totalCodePoints);
      if (active && revealedCodePoints < totalCodePoints) {
        parameters = parameters.withClip(totalCodePoints - revealedCodePoints);
      }
      TextRevealEffect.Decoration decorated =
          TextRevealEffect.decorate(source, base, parameters.withScramble(active));
      return live && !animating
          ? TextRevealEffect.decorateEndCaret(decorated, totalMillis, 650) : decorated;
    }

    public boolean requiresAnimation() {
      return animating || live && edgeAgeMillis <= CARET_PULSE_WINDOW_MILLIS
          || effects && edgeAgeMillis < SCRAMBLE_SETTLE_MILLIS;
    }
  }
}
