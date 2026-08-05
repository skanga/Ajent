package com.github.skanga.ajent.terminal.render;

import java.util.Objects;

/** Viewport-closed terminal row coordinates from Ajent's renderer witness chain. */
public final class WireViewport {
  private static final ThreadLocal<long[]> NEXT_ID = ThreadLocal.withInitial(() -> new long[] {1});

  /** Provenance tag preventing rows minted for separate frames from being mixed. */
  public record Id(long value) {}

  /** A row proven by construction to lie inside the viewport that minted it. */
  public static final class Row {
    private final int y;
    private final int top;
    private final int bottomExclusive;
    private final Id viewport;

    private Row(int y, int top, int bottomExclusive, Id viewport) {
      this.y = Math.clamp(y, top, Math.max(top, bottomExclusive - 1));
      this.top = top;
      this.bottomExclusive = bottomExclusive;
      this.viewport = viewport;
    }

    public int y() { return y; }

    public Id viewport() { return viewport; }

    public Row up(int count) {
      if (count <= 0) return this;
      return new Row(saturatingSubtract(y, count), top, bottomExclusive, viewport);
    }

    public Row down(int count) {
      if (count <= 0) return this;
      return new Row(saturatingAdd(y, count), top, bottomExclusive, viewport);
    }

    public int signedDistanceTo(Row other) {
      requireSameViewport(other);
      return other.y - y;
    }

    private void requireSameViewport(Row other) {
      Objects.requireNonNull(other, "other");
      if (!viewport.equals(other.viewport)) {
        throw new IllegalArgumentException("wire rows belong to different viewports");
      }
    }

    @Override public boolean equals(Object other) {
      return other instanceof Row row && y == row.y && viewport.equals(row.viewport);
    }

    @Override public int hashCode() {
      return 31 * Integer.hashCode(y) + viewport.hashCode();
    }

    @Override public String toString() {
      return "Row[y=" + y + ", viewport=" + viewport.value + "]";
    }
  }

  private final int top;
  private final int height;
  private final Id id;

  private WireViewport(int top, int height, Id id) {
    this.top = top;
    this.height = height;
    this.id = id;
  }

  public static WireViewport freshFrame(int terminalHeight) {
    return new WireViewport(0, Math.max(1, terminalHeight), mintId());
  }

  public static WireViewport redraw(int terminalHeight, int wireCursorRows) {
    int height = Math.max(1, terminalHeight);
    Math.clamp(wireCursorRows, 1, height); // Preserve the source's defensive validation.
    return new WireViewport(0, height, mintId());
  }

  public int top() { return top; }

  public int height() { return height; }

  public int bottomExclusive() { return top + height; }

  public Id id() { return id; }

  public Row topRow() { return row(top); }

  public Row bottomRow() { return row(bottomExclusive() - 1); }

  public Row row(int absoluteY) { return new Row(absoluteY, top, bottomExclusive(), id); }

  public boolean contains(Row row) {
    return row != null && id.equals(row.viewport);
  }

  public static void emitMove(StringBuilder output, Row from, Row to) {
    Objects.requireNonNull(output, "output");
    int delta = from.signedDistanceTo(to);
    if (delta < 0) output.append("\u001b[").append(-delta).append('A');
    else if (delta > 0) output.append("\u001b[").append(delta).append('B');
  }

  public static void emitMoveToColumnZero(StringBuilder output, Row from, Row to) {
    emitMove(output, from, to);
    output.append('\r');
  }

  private static Id mintId() {
    long[] next = NEXT_ID.get();
    return new Id(next[0]++);
  }

  private static int saturatingAdd(int left, int right) {
    long value = (long) left + right;
    return Math.clamp(value, Integer.MIN_VALUE, Integer.MAX_VALUE);
  }

  private static int saturatingSubtract(int left, int right) {
    long value = (long) left - right;
    return Math.clamp(value, Integer.MIN_VALUE, Integer.MAX_VALUE);
  }
}
