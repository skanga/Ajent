package com.github.skanga.ajent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.terminal.render.CanvasSerializer;
import com.github.skanga.ajent.terminal.render.InlineFrameRenderer;
import com.github.skanga.ajent.terminal.render.TerminalCanvas;
import com.github.skanga.ajent.terminal.render.TerminalStylePool;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Direct translation of scrollback_prefix_harness.cpp's five public-API scenarios. */
final class ScrollbackPrefixHarnessTest {
  private static final int[][] SHAPES = {
      {80, 24}, {40, 10}, {120, 50}, {60, 8}
  };

  @Test void oversizedStartupPlacesEveryRowExactlyOnceAcrossNativeShapes() {
    for (int[] shape : SHAPES) {
      int contentRows = shape[1] + 16;
      Harness harness = new Harness(shape[0], shape[1]);

      InlineFrameRenderer.Synced synced = harness.seed(markedCanvas(
          shape[0], contentRows, "BOOT"));

      assertThat(synced.rows()).isEqualTo(contentRows);
      assertMarkedRowsExactlyOnce(harness.emulator.transcript(), "BOOT", contentRows);
    }
  }

  @Test void committedPrefixIsWitnessedClampedDivergenceSafeAndExact() {
    for (int[] shape : SHAPES) {
      int contentRows = shape[1] + 20;
      Harness harness = new Harness(shape[0], shape[1]);
      TerminalCanvas canvas = markedCanvas(shape[0], contentRows, "PIN");
      InlineFrameRenderer.Synced synced = harness.seed(canvas);
      int overflow = synced.rows() - shape[1];

      assertThat(overflow).isPositive();
      assertThat(synced.checkScrollback(canvas, shape[1])).isPresent();
      assertThat(synced.scrollbackMarker(synced.rows() + 100).rows())
          .isLessThanOrEqualTo(synced.rows());
      assertThat(synced.checkScrollback(
          markedCanvas(shape[0], contentRows, "XXX"), shape[1])).isEmpty();

      int before = synced.rows();
      InlineFrameRenderer.ScrollbackMarker marker = synced.scrollbackMarker(overflow);
      assertThat(synced.commit(marker).rows()).isEqualTo(before - overflow);
    }
  }

  @Test void bareReemitExposesTheHazardWhileHardResetPreventsTheStrand() {
    for (int[] shape : SHAPES) {
      int contentRows = shape[1] + 16;
      TerminalCanvas hazardous = markedCanvas(shape[0], contentRows, "REEM");
      Harness bare = new Harness(shape[0], shape[1]);
      bare.seed(hazardous);
      bare.seed(hazardous);
      assertThat(hasDuplicateMarkedRow(bare.emulator.transcript(), "REEM-row-"))
          .as("bare re-emit hazard at %sx%s", shape[0], shape[1]).isTrue();

      TerminalCanvas safeCanvas = markedCanvas(shape[0], contentRows, "SAFE");
      Harness safe = new Harness(shape[0], shape[1]);
      safe.seed(safeCanvas);
      InlineFrameRenderer.Frame recovered = new InlineFrameRenderer.HardReset().render(
          safeCanvas, CanvasSerializer.contentRows(safeCanvas), shape[1], safe.styles,
          safe.emulator::feed, false);
      assertThat(recovered).isInstanceOf(InlineFrameRenderer.Synced.class);
      assertMarkedRowsExactlyOnce(safe.emulator.transcript(), "SAFE", contentRows);
    }
  }

  @Test void markerGenerationBindsCommitAndRejectsStaleReuse() {
    for (int[] shape : SHAPES) {
      int contentRows = shape[1] + 16;
      Harness harness = new Harness(shape[0], shape[1]);
      InlineFrameRenderer.Synced synced = harness.seed(markedCanvas(
          shape[0], contentRows, "GEN"));
      int overflow = synced.rows() - shape[1];
      long beforeGeneration = synced.generation();
      InlineFrameRenderer.ScrollbackMarker stale = synced.scrollbackMarker(overflow);
      InlineFrameRenderer.ScrollbackMarker fresh = synced.scrollbackMarker(overflow);

      assertThat(beforeGeneration).isNotZero();
      assertThat(stale.generation()).isEqualTo(beforeGeneration);
      int beforeRows = synced.rows();
      InlineFrameRenderer.Synced committed = synced.commit(fresh);
      assertThat(committed.rows()).isEqualTo(beforeRows - overflow);
      assertThat(committed.generation()).isNotEqualTo(beforeGeneration);

      int rowsBeforeStale = committed.rows();
      assertThat(committed.commit(stale).rows()).isEqualTo(rowsBeforeStale);
    }
  }

  @Test void scrollbackProofDistinguishesWitnessedRejectedAndVacuousCases() {
    for (int[] shape : SHAPES) {
      int contentRows = shape[1] + 16;
      Harness harness = new Harness(shape[0], shape[1]);
      TerminalCanvas canvas = markedCanvas(shape[0], contentRows, "PRF");
      InlineFrameRenderer.Synced synced = harness.seed(canvas);

      InlineFrameRenderer.ScrollbackProof witnessed =
          synced.checkScrollback(canvas, shape[1]).orElseThrow();
      assertThat(witnessed.valid()).isTrue();
      assertThat(witnessed.overflowRows()).isEqualTo(synced.rows() - shape[1]);
      assertThat(witnessed.bound()).isTrue();
      assertThat(synced.checkScrollback(
          markedCanvas(shape[0], contentRows, "ZZZ"), shape[1])).isEmpty();

      InlineFrameRenderer.ScrollbackProof vacuous =
          synced.checkScrollback(canvas, synced.rows() + 5).orElseThrow();
      assertThat(vacuous.overflowRows()).isZero();
      assertThat(vacuous.bound()).isFalse();
    }
  }

  private static TerminalCanvas markedCanvas(int width, int rows, String prefix) {
    var canvas = new TerminalCanvas(width, rows + 4);
    for (int row = 0; row < rows; row++) {
      canvas.writeText(0, row, prefix + "-row-" + row, 0);
    }
    return canvas;
  }

  private static void assertMarkedRowsExactlyOnce(
      List<String> transcript, String prefix, int rows) {
    List<String> marked = transcript.stream()
        .filter(row -> row.contains(prefix + "-row-"))
        .toList();
    assertThat(marked).containsExactlyElementsOf(IntStream.range(0, rows)
        .mapToObj(row -> prefix + "-row-" + row).toList());
    assertThat(new HashSet<>(marked)).hasSize(rows);
  }

  private static boolean hasDuplicateMarkedRow(List<String> transcript, String marker) {
    var seen = new HashSet<String>();
    return transcript.stream().filter(row -> row.contains(marker)).anyMatch(row -> !seen.add(row));
  }

  private static final class Harness {
    private final int terminalRows;
    private final TerminalStylePool styles = new TerminalStylePool();
    private final MidrunWireTest.AnsiEmulator emulator;

    private Harness(int columns, int terminalRows) {
      this.terminalRows = terminalRows;
      emulator = new MidrunWireTest.AnsiEmulator(columns, terminalRows);
    }

    private InlineFrameRenderer.Synced seed(TerminalCanvas canvas) {
      InlineFrameRenderer.Frame result = new InlineFrameRenderer.Empty().seed().render(
          canvas, CanvasSerializer.contentRows(canvas), terminalRows, styles,
          emulator::feed, false);
      assertThat(result).isInstanceOf(InlineFrameRenderer.Synced.class);
      return (InlineFrameRenderer.Synced) result;
    }
  }
}
