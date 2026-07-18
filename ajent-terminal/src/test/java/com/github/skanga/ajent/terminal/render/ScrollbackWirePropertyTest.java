package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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
    for (int[] shape : new int[][] {{40, 8}, {60, 16}, {80, 24}, {100, 20}}) {
      exerciseWalk(shape[0], shape[1], 0xc0ffeeL + shape[0] * 31L + shape[1]);
    }
  }

  private static void exerciseWalk(int width, int terminalRows, long seed) {
    var random = new Random(seed);
    var styles = new TerminalStylePool();
    var emulator = new AnsiTerminalEmulator(width, terminalRows);
    var versions = new ArrayList<Integer>();
    versions.add(0);
    var canvas = markedCanvas(width, versions.size(), labels(versions));
    var synced = fresh(canvas, styles, emulator, terminalRows);
    List<String> priorScrollback = List.of();
    for (int step = 0; step < 80; step++) {
      if (random.nextInt(4) == 0 && !versions.isEmpty()) {
        int liveTop = Math.max(0, versions.size() - terminalRows);
        int row = liveTop + random.nextInt(versions.size() - liveTop);
        versions.set(row, versions.get(row) + 1);
      } else {
        int additions = 1 + random.nextInt(3);
        for (int count = 0; count < additions; count++) versions.add(0);
      }
      canvas = markedCanvas(width, versions.size(), labels(versions));
      var witness = synced.verify().orElseThrow();
      var proof = synced.checkScrollback(canvas, terminalRows).orElseThrow();
      var result = synced.render(canvas, CanvasSerializer.contentRows(canvas), terminalRows,
          styles, emulator::feed, witness, proof, false);
      synced = (InlineFrameRenderer.Synced) result;
      List<String> scrollback = emulator.scrollbackLines();
      assertThat(scrollback.subList(0, priorScrollback.size())).containsExactlyElementsOf(priorScrollback);
      priorScrollback = scrollback;
      for (int row = 0; row < versions.size(); row++) {
        assertThat(emulator.countContaining(marker(row))).as("shape %sx%s step %s row %s",
            width, terminalRows, step, row).isEqualTo(1);
      }
    }
  }

  private static List<String> labels(List<Integer> versions) {
    var labels = new ArrayList<String>(versions.size());
    for (int row = 0; row < versions.size(); row++) {
      labels.add(marker(row) + "v" + versions.get(row));
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
}
