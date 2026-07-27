package com.github.skanga.ajent.terminal.render;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

/** Typed state machine guarding Ajent's inline renderer shadow-of-wire. */
public final class InlineFrameRenderer {
  public sealed interface Frame permits Empty, Fresh, Synced, Stale, HardReset, Sealed {}

  @FunctionalInterface
  public interface FrameWriter { void write(String bytes) throws IOException; }

  public record Finalized(Sealed frame, String restoreBytes) {}

  public static final class Empty implements Frame {
    private boolean consumed;

    public Fresh seed() {
      consume();
      return new Fresh(State.empty());
    }

    public Finalized finalizeFrame() {
      consume();
      return new Finalized(new Sealed(), "");
    }

    private void consume() {
      if (consumed) throw new IllegalStateException("inline frame state already consumed");
      consumed = true;
    }
  }

  public static final class Fresh implements Frame {
    private State state;

    private Fresh(State state) { this.state = state; }

    public Frame render(TerminalCanvas canvas, CanvasSerializer.ContentRows rows,
        int terminalRows, TerminalStylePool styles, FrameWriter writer, boolean synchronizedOutput) {
      return render(canvas, rows, terminalRows, styles, writer, synchronizedOutput, "");
    }

    private Frame render(TerminalCanvas canvas, CanvasSerializer.ContentRows rows,
        int terminalRows, TerminalStylePool styles, FrameWriter writer, boolean synchronizedOutput,
        String resetPrefix) {
      State owned = consume();
      return commit(compose(canvas, rows, terminalRows, styles, owned, synchronizedOutput,
          resetPrefix), writer);
    }

    public Finalized finalizeFrame() { return finalizeState(consume()); }

    private State consume() {
      if (state == null) throw new IllegalStateException("inline frame state already consumed");
      State owned = state;
      state = null;
      return owned;
    }
  }

  public static final class Synced implements Frame {
    private State state;

    private Synced(State state) { this.state = state; }

    public int rows() { return live().previousRows; }
    public int width() { return live().previousWidth; }
    public long generation() { return live().generation; }

    public Optional<ShadowWitness> verify() {
      State current = live();
      return current.shadowHash == hash(current.previousCells, current.previousWidth,
          current.previousRows)
          ? Optional.of(new ShadowWitness(this, current.generation, current.shadowHash))
          : Optional.empty();
    }

    public Optional<ScrollbackProof> checkScrollback(TerminalCanvas canvas, int terminalRows) {
      State current = live();
      if (current.previousRows <= terminalRows) {
        return Optional.of(new ScrollbackProof(null, current.generation, 0));
      }
      int overflow = current.previousRows - terminalRows;
      if (canvas.width() != current.previousWidth || overflow > CanvasSerializer.contentHeight(canvas)) {
        return Optional.empty();
      }
      long[] cells = canvas.packedCells();
      int count = overflow * current.previousWidth;
      return Arrays.equals(current.previousCells, 0, count, cells, 0, count)
          ? Optional.of(new ScrollbackProof(this, current.generation, overflow))
          : Optional.empty();
    }

    public Frame render(TerminalCanvas canvas, CanvasSerializer.ContentRows rows,
        int terminalRows, TerminalStylePool styles, FrameWriter writer, ShadowWitness witness,
        ScrollbackProof proof, boolean synchronizedOutput) {
      State owned = live();
      witness.consume(this, owned);
      proof.consume(this, owned, terminalRows);
      state = null;
      return InlineFrameRenderer.commit(
          compose(canvas, rows, terminalRows, styles, owned, synchronizedOutput, ""), writer);
    }

    public Stale demoteToStale() {
      State owned = consume();
      return new Stale(owned.forSoftRecovery());
    }

    public HardReset demoteToHardReset() {
      consume();
      return new HardReset();
    }

    public ScrollbackMarker scrollbackMarker(int rows) {
      State current = live();
      return new ScrollbackMarker(Math.clamp(rows, 0, current.previousRows), current.generation);
    }

    public Synced commit(ScrollbackMarker marker) {
      State owned = consume();
      if (marker.rows == 0) return new Synced(owned);
      if (marker.generation != owned.generation) return new Synced(owned);
      if (marker.rows >= owned.previousRows) {
        return new Synced(State.reset(owned.generation + 1));
      }
      int shift = marker.rows * owned.previousWidth;
      long[] remaining = Arrays.copyOfRange(owned.previousCells, shift, owned.previousCells.length);
      return new Synced(owned.withShadow(remaining, owned.previousWidth,
          owned.previousRows - marker.rows, owned.wireCursorRows, owned.generation + 1));
    }

    /** Commits only rows already known to be above the physical viewport. */
    public Synced commitScrollbackOverflow(int terminalRows) {
      if (terminalRows <= 0) throw new IllegalArgumentException("terminal rows must be positive");
      int overflow = Math.max(0, rows() - terminalRows);
      return commit(scrollbackMarker(overflow));
    }

    public Finalized finalizeFrame() { return finalizeState(consume()); }

    private State live() {
      if (state == null) throw new IllegalStateException("inline frame state already consumed");
      return state;
    }

    private State consume() {
      State owned = live();
      state = null;
      return owned;
    }
  }

  public static final class Stale implements Frame {
    private State state;

    private Stale(State state) { this.state = state; }

    public Frame render(TerminalCanvas canvas, CanvasSerializer.ContentRows rows,
        int terminalRows, TerminalStylePool styles, FrameWriter writer, boolean synchronizedOutput) {
      State owned = consumeState(this, state);
      state = null;
      return commit(compose(canvas, rows, terminalRows, styles, owned, synchronizedOutput, ""), writer);
    }

    public Finalized finalizeFrame() {
      State owned = consumeState(this, state);
      state = null;
      return finalizeState(owned);
    }
  }

  public static final class HardReset implements Frame {
    private boolean consumed;

    public Frame render(TerminalCanvas canvas, CanvasSerializer.ContentRows rows,
        int terminalRows, TerminalStylePool styles, FrameWriter writer, boolean synchronizedOutput) {
      if (consumed) throw new IllegalStateException("inline frame state already consumed");
      consumed = true;
      return new Fresh(State.empty()).render(canvas, rows, terminalRows, styles, writer,
          synchronizedOutput, "\u001b[2J\u001b[3J\u001b[H");
    }

    public Finalized finalizeFrame() {
      if (consumed) throw new IllegalStateException("inline frame state already consumed");
      consumed = true;
      return new Finalized(new Sealed(), "");
    }
  }

  public static final class Sealed implements Frame {
    private Sealed() {}
    public Finalized finalizeFrame() { return new Finalized(this, ""); }
  }

  public static final class ShadowWitness {
    private Synced owner;
    private final long generation;
    private final long hash;

    private ShadowWitness(Synced owner, long generation, long hash) {
      this.owner = owner;
      this.generation = generation;
      this.hash = hash;
    }

    private void consume(Synced expected, State state) {
      if (owner != expected || generation != state.generation || hash != state.shadowHash) {
        throw new IllegalArgumentException("shadow witness does not belong to this frame");
      }
      owner = null;
    }
  }

  public static final class ScrollbackProof {
    private Synced owner;
    private final long generation;
    private final int overflow;
    private boolean consumed;

    private ScrollbackProof(Synced owner, long generation, int overflow) {
      this.owner = owner;
      this.generation = generation;
      this.overflow = overflow;
    }

    public boolean valid() { return !consumed; }
    public int overflowRows() { return overflow; }
    public boolean bound() { return owner != null; }

    private void consume(Synced expected, State state, int terminalRows) {
      int actualOverflow = Math.max(0, state.previousRows - terminalRows);
      boolean correctOwner = overflow == 0 ? owner == null : owner == expected;
      if (consumed || !correctOwner
          || generation != state.generation || overflow != actualOverflow) {
        throw new IllegalArgumentException("scrollback proof does not belong to this frame");
      }
      consumed = true;
      owner = null;
    }
  }

  public static final class ScrollbackMarker {
    private final int rows;
    private final long generation;

    private ScrollbackMarker(int rows, long generation) {
      this.rows = rows;
      this.generation = generation;
    }

    public int rows() { return rows; }
    public long generation() { return generation; }
  }

  private record State(long[] previousCells, int previousWidth, int previousRows,
      int wireCursorRows, int ghostRowsAbove, boolean cursorHidden, boolean decawmOff,
      long shadowHash, long generation) {
    private static State empty() { return new State(new long[0], 0, 0, 0, 0, false, false, -1, 0); }

    private static State reset(long generation) {
      return new State(new long[0], 0, 0, 0, 0, false, false, -1, generation);
    }

    private State forSoftRecovery() {
      return new State(new long[0], previousWidth, 0, 0, wireCursorRows, cursorHidden,
          decawmOff, -1, generation + 1);
    }

    private State withShadow(long[] cells, int width, int rows, int cursorRows, long generation) {
      return new State(cells, width, rows, cursorRows, 0, true, decawmOff,
          hash(cells, width, rows), generation);
    }
  }

  private record Composed(String bytes, State successor, State predecessor) {}

  private InlineFrameRenderer() {}

  /** Consumes paint-minted debt verbatim; debt evaporates when no coherent shadow exists. */
  public static Frame commitScrollback(Frame frame, ScrollbackLedger.ScrollbackDebt debt) {
    java.util.Objects.requireNonNull(frame, "frame");
    java.util.Objects.requireNonNull(debt, "debt");
    int rows = debt.consumeRows();
    if (rows <= 0 || !(frame instanceof Synced synced)) return frame;
    return synced.commit(synced.scrollbackMarker(rows));
  }

  private static Composed compose(TerminalCanvas canvas, CanvasSerializer.ContentRows rows,
      int terminalRows, TerminalStylePool styles, State state, boolean synchronizedOutput,
      String resetPrefix) {
    java.util.Objects.requireNonNull(canvas, "canvas");
    java.util.Objects.requireNonNull(rows, "rows");
    java.util.Objects.requireNonNull(styles, "styles");
    if (!rows.belongsTo(canvas)) throw new IllegalArgumentException("content rows came from another canvas");
    int contentRows = rows.value();
    if (canvas.width() <= 0 || contentRows <= 0 || terminalRows <= 0) {
      return new Composed("", state, state);
    }
    if (state.previousWidth != 0 && state.previousWidth != canvas.width()) {
      state = State.reset(state.generation + 1);
    }
    long[] current = Arrays.copyOf(canvas.packedCells(), contentRows * canvas.width());
    int previousRows = state.previousRows;
    int previousOnScreen = Math.min(previousRows, terminalRows);
    int commonRows = Math.min(contentRows, previousRows);
    int firstChanged = commonRows;
    if (state.previousWidth == canvas.width() && state.previousCells.length >= commonRows * canvas.width()) {
      for (int row = Math.max(0, previousRows - previousOnScreen); row < commonRows; row++) {
        if (!rowEquals(current, state.previousCells, row, canvas.width())) {
          firstChanged = row;
          break;
        }
      }
    }
    if (contentRows < previousRows && firstChanged == commonRows) {
      // Pure shrink cleanup repaints the new last row before erasing below it.
      firstChanged = Math.max(0, contentRows - 1);
    }
    boolean same = firstChanged == commonRows && contentRows == previousRows;
    if (same && resetPrefix.isEmpty()) {
      State successor = new State(state.previousCells, state.previousWidth, state.previousRows,
          state.wireCursorRows, state.ghostRowsAbove, true, state.decawmOff,
          state.shadowHash, state.generation);
      return new Composed("\u001b[?25l", successor, state);
    }

    var output = new StringBuilder();
    if (synchronizedOutput) output.append("\u001b[?2026h");
    output.append(resetPrefix).append("\u001b[?25l");
    if (state.previousRows == 0 && state.previousWidth > 0) {
      int emitRows = Math.min(contentRows, terminalRows);
      int startRow = Math.max(0, contentRows - terminalRows);
      int cursorRow = Math.min(Math.max(1, state.ghostRowsAbove), terminalRows) - 1;
      int moveUp = Math.min(cursorRow, Math.max(0, emitRows - 1));
      if (moveUp > 0) output.append("\u001b[").append(moveUp).append('A');
      output.append('\r').append(CanvasSerializer.serialize(canvas, styles, contentRows, startRow))
          .append("\u001b[J");
    } else if (state.previousRows == 0) {
      output.append('\r').append(CanvasSerializer.serialize(canvas, styles, contentRows, 0));
    } else {
      int delta = firstChanged - (previousRows - 1);
      if (delta < 0) {
        int moveUp = Math.min(-delta, previousOnScreen - 1);
        appendCursorMove(output, moveUp, 'A');
        output.append('\r');
      } else if (delta == 0) {
        output.append('\r');
      } else {
        output.append("\r\n");
        appendCursorMove(output, delta - 1, 'B');
      }
      if (!state.decawmOff) {
        output.append("\u001b[?7l");
        state = new State(state.previousCells, state.previousWidth, state.previousRows,
            state.wireCursorRows, state.ghostRowsAbove, true, true,
            state.shadowHash, state.generation);
      }
      int currentStyle = TerminalStylePool.UNKNOWN_STYLE;
      boolean compatRepaint = System.getenv("AJENT_COMPAT_REPAINT") != null
          || "zed".equals(System.getenv("TERM_PROGRAM"));
      long blank = PackedCell.BLANK.pack();
      for (int row = firstChanged; row < contentRows; row++) {
        if (row > firstChanged) output.append("\r\n");
        long[] currentRow = Arrays.copyOfRange(current, row * canvas.width(),
            (row + 1) * canvas.width());
        long[] previousRow = new long[canvas.width()];
        Arrays.fill(previousRow, blank);
        boolean newRow = row >= previousRows;
        if (!newRow) {
          System.arraycopy(state.previousCells, row * canvas.width(), previousRow, 0,
              canvas.width());
        }
        int newVisibleTop = Math.max(0, contentRows - terminalRows);
        int previousVisibleTop = Math.max(0, previousRows - terminalRows);
        boolean willScrollOff = contentRows >= terminalRows
            && row >= previousVisibleTop && row <= newVisibleTop;
        int rowFirstDifference = CanvasSerializer.firstDifference(currentRow, previousRow);
        boolean wholeRow = willScrollOff || (compatRepaint && rowFirstDifference < canvas.width());
        int firstDifference = wholeRow ? 0
            : CanvasSerializer.snapFirstDifferenceLeft(rowFirstDifference, currentRow, previousRow);
        if (firstDifference >= canvas.width()) continue;
        int lastDifference = wholeRow ? canvas.width() - 1
            : CanvasSerializer.snapLastDifferenceRight(
                CanvasSerializer.lastDifference(currentRow, previousRow), currentRow, previousRow);
        int lastVisible = canvas.lastContentColumn(row);
        int endEmit = Math.max(firstDifference, Math.min(lastDifference + 1, lastVisible + 1));
        boolean needErase = newRow || lastDifference > lastVisible;
        boolean needEmit = endEmit > firstDifference;
        if (needEmit || needErase) appendCursorMove(output, firstDifference, 'C');
        if (needEmit) {
          currentStyle = CanvasSerializer.emitCellRun(canvas, styles, row, firstDifference,
              endEmit, currentStyle, output);
        }
        if (needErase) {
          if (currentStyle != 0) {
            output.append(styles.sgr(0));
            currentStyle = 0;
          }
          if (!(needEmit && endEmit >= canvas.width())) output.append("\u001b[K");
        }
      }
      if (contentRows < previousRows) {
        output.append('\r');
        int lastRow = contentRows - 1;
        int lastVisible = canvas.lastContentColumn(lastRow);
        if (lastVisible >= 0) {
          currentStyle = CanvasSerializer.emitCellRun(canvas, styles, lastRow, 0,
              lastVisible + 1, currentStyle, output);
        }
        if (currentStyle != 0) output.append(styles.sgr(0));
        if (lastVisible < canvas.width() - 1) output.append("\u001b[J");
        else output.append("\r\n\u001b[J\u001b[A");
      }
      output.append("\u001b[0m");
    }
    if (synchronizedOutput) output.append("\u001b[?2026l");
    State successor = state.withShadow(current, canvas.width(), contentRows,
        Math.min(contentRows, terminalRows), state.generation + 1);
    return new Composed(output.toString(), successor, state);
  }

  private static Frame commit(Composed composed, FrameWriter writer) {
    try {
      writer.write(composed.bytes);
      return new Synced(composed.successor);
    } catch (IOException failure) {
      return new HardReset();
    }
  }

  private static Finalized finalizeState(State state) {
    var restore = new StringBuilder();
    if (state.cursorHidden) restore.append("\u001b[?25h");
    if (state.decawmOff) restore.append("\u001b[?7h");
    return new Finalized(new Sealed(), restore.toString());
  }

  private static State consumeState(Object owner, State state) {
    if (state == null) throw new IllegalStateException("inline frame state already consumed");
    return state;
  }

  private static long hash(long[] cells, int width, int rows) {
    if (width <= 0 || rows <= 0) return -1;
    long combined = 0;
    for (int row = 0; row < rows; row++) {
      long hash = 0xcbf29ce484222325L;
      hash ^= Integer.toUnsignedLong(row) + 0x9e3779b97f4a7c15L;
      hash *= 0x100000001b3L;
      for (int column = 0; column < width; column++) {
        hash ^= cells[row * width + column];
        hash *= 0x100000001b3L;
      }
      combined ^= hash;
    }
    return combined;
  }

  private static boolean rowEquals(long[] left, long[] right, int row, int width) {
    int offset = row * width;
    return Arrays.equals(left, offset, offset + width, right, offset, offset + width);
  }

  private static void appendCursorMove(StringBuilder output, int count, char direction) {
    if (count > 0) output.append("\u001b[").append(count).append(direction);
  }
}
