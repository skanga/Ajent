package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ScrollbackWirePropertyTest {
  @Test void prefixWitnessAndMarkerArithmeticHoldAcrossNativeShapes() {
    for (int[] shape : new int[][] {{80, 24}, {40, 10}, {120, 50}, {60, 8}}) {
      int width = shape[0], terminalRows = shape[1], contentRows = terminalRows + 20;
      var styles = new TerminalStylePool();
      var canvas = markedCanvas(width, contentRows, List.of());
      var synced = fresh(canvas, styles, new AnsiTerminalEmulator(width, terminalRows), terminalRows);
      assertThat(synced.checkScrollback(canvas, terminalRows)).isPresent();
      var shifted = markedCanvas(width, contentRows, repeatedLabels(contentRows, "shifted"));
      assertThat(synced.checkScrollback(shifted, terminalRows)).isEmpty();
      var marker = synced.scrollbackMarker(synced.rows() + 100);
      assertThat(marker.rows()).isEqualTo(synced.rows());
      assertThat(synced.commit(marker).rows()).isZero();
    }
  }

  @Test void randomizedAppendAndLiveTailEditsKeepScrollbackPrefixAppendOnly() {
    int[][] shapes = {{80, 24}, {100, 20}, {120, 30}, {60, 16}};
    for (int shape = 0; shape < shapes.length; shape++) {
      for (int walk = 0; walk < 40; walk++) {
        long seed = 0xc0ffeeL + shape * 7_368_787L + walk * 2_654_435_761L;
        exerciseWalk(shapes[shape][0], shapes[shape][1], seed);
      }
    }
  }

  @Test void prefixShiftRecoveryCommitsExactlyTheMeasuredOverflowBeforeSoftRepaint() {
    int width = 80;
    int terminalRows = 10;
    var styles = new TerminalStylePool();
    var emulator = new AnsiTerminalEmulator(width, terminalRows);
    var tall = markedCanvas(width, 40, repeatedLabels(40, "TALL"));
    var synced = fresh(tall, styles, emulator, terminalRows);
    var shifted = markedCanvas(width, 15, repeatedLabels(15, "SHIFTED"));

    assertThat(synced.checkScrollback(shifted, terminalRows)).isEmpty();
    synced = synced.commitScrollbackOverflow(terminalRows);
    assertThat(synced.rows()).isEqualTo(terminalRows);
    var recovered = synced.demoteToStale().render(shifted,
        CanvasSerializer.contentRows(shifted), terminalRows, styles, emulator::feed, false);

    assertThat(recovered).isInstanceOf(InlineFrameRenderer.Synced.class);
    assertThat(emulator.countContaining("TALL-row-0")).isOne();
    assertThat(emulator.countContaining("SHIFTED-row-14")).isOne();
  }

  @Test void overflowCommitIsAnIdempotentNoOpWhenTheFrameFits() {
    var styles = new TerminalStylePool();
    var emulator = new AnsiTerminalEmulator(40, 10);
    var synced = fresh(markedCanvas(40, 8, List.of()), styles, emulator, 10);

    assertThat(synced.commitScrollbackOverflow(10).rows()).isEqualTo(8);
  }

  private static void exerciseWalk(int width, int terminalRows, long seed) {
    var styles = new TerminalStylePool();
    var emulator = new AnsiTerminalEmulator(width, terminalRows);
    var random = new SplitMix64(seed);
    var rows = new ArrayList<Row>();
    rows.add(new Row(0, 0));
    int nextId = 1;
    Set<Integer> expectedPresent = new HashSet<>();
    expectedPresent.add(0);
    var canvas = markedCanvas(width, rows.size(), labels(rows));
    var synced = fresh(canvas, styles, emulator, terminalRows);
    List<String> priorScrollback = List.of();
    int steps = 25 + random.below(35);
    for (int step = 0; step < steps; step++) {
      int operation = random.below(100);
      if (operation < 55) {
        int additions = 1 + random.below(3);
        for (int count = 0; count < additions; count++) {
          rows.add(new Row(nextId, 0));
          expectedPresent.add(nextId++);
        }
      } else if (operation < 75 && !rows.isEmpty()) {
        int liveTop = Math.max(0, rows.size() - terminalRows);
        int index = liveTop + random.below(rows.size() - liveTop);
        Row row = rows.get(index);
        rows.set(index, new Row(row.id(), row.version() + 1));
      } else if (operation < 88 && rows.size() > 1) {
        int overflow = Math.max(0, synced.rows() - terminalRows);
        if (overflow > 0) {
          synced = synced.commitScrollbackOverflow(terminalRows);
          rows.subList(0, Math.min(overflow, rows.size())).clear();
        }
        int removals = Math.min(rows.size() - 1, 1 + random.below(3));
        for (int count = 0; count < removals; count++) {
          expectedPresent.remove(rows.removeLast().id());
        }
      } else {
        int overflow = Math.max(0, synced.rows() - terminalRows);
        if (overflow > 0) {
          synced = synced.commitScrollbackOverflow(terminalRows);
          int trimmed = Math.min(overflow, rows.size());
          rows.subList(0, trimmed).clear();
        }
      }
      canvas = markedCanvas(width, rows.size(), labels(rows));
      var witness = synced.verify().orElseThrow();
      var proof = synced.checkScrollback(canvas, terminalRows).orElseThrow();
      var result = synced.render(canvas, CanvasSerializer.contentRows(canvas), terminalRows,
          styles, emulator::feed, witness, proof, false);
      synced = (InlineFrameRenderer.Synced) result;
      List<String> scrollback = emulator.scrollbackLines();
      assertThat(scrollback.subList(0, priorScrollback.size())).containsExactlyElementsOf(priorScrollback);
      priorScrollback = scrollback;
      for (int id : expectedPresent) {
        assertThat(emulator.countContaining(marker(id))).as("shape %sx%s step %s row %s seed %s",
            width, terminalRows, step, id, Long.toUnsignedString(seed)).isEqualTo(1);
      }
    }
  }

  private static List<String> labels(List<Row> rows) {
    var labels = new ArrayList<String>(rows.size());
    for (Row row : rows) {
      labels.add(marker(row.id()) + "v" + row.version());
    }
    return labels;
  }

  private static List<String> repeatedLabels(int rows, String prefix) {
    var labels = new ArrayList<String>(rows);
    for (int row = 0; row < rows; row++) labels.add(prefix + "-row-" + row);
    return labels;
  }

  private static String marker(int row) { return "ROW-" + row + " "; }

  private static TerminalCanvas markedCanvas(int width, int rows, List<String> labels) {
    var canvas = new TerminalCanvas(width, rows + 2);
    for (int row = 0; row < rows; row++) {
      String label = labels.isEmpty() ? "BASE-row-" + row : labels.get(row);
      canvas.writeText(0, row, label, 0);
    }
    return canvas;
  }

  private static InlineFrameRenderer.Synced fresh(TerminalCanvas canvas,
      TerminalStylePool styles, AnsiTerminalEmulator emulator, int terminalRows) {
    return (InlineFrameRenderer.Synced) new InlineFrameRenderer.Empty().seed().render(canvas,
        CanvasSerializer.contentRows(canvas), terminalRows, styles, emulator::feed, false);
  }

  private record Row(int id, int version) {}

  private static final class SplitMix64 {
    private long state;

    private SplitMix64(long seed) {
      state = seed;
    }

    private long next() {
      state += 0x9e3779b97f4a7c15L;
      long value = state;
      value = (value ^ value >>> 30) * 0xbf58476d1ce4e5b9L;
      value = (value ^ value >>> 27) * 0x94d049bb133111ebL;
      return value ^ value >>> 31;
    }

    private int below(int bound) {
      return bound <= 0 ? 0 : (int) Long.remainderUnsigned(next(), bound);
    }
  }
}
