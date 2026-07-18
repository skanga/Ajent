package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

final class OversizedScrollbackOracleTest {
  @Test void startupOversizedFrameAppearsExactlyOnceInOrder() {
    var styles = new TerminalStylePool();
    var emulator = new AnsiTerminalEmulator(80, 24);
    var frame = markedCanvas(80, 40, "TURN");
    renderFresh(frame, styles, emulator);
    var markers = emulator.transcript().stream().filter(line -> line.contains("TURN-row-")).toList();
    assertThat(markers).hasSize(40);
    for (int row = 0; row < 40; row++) assertThat(markers.get(row)).contains("TURN-row-" + row);
    assertThat(new HashSet<>(markers)).hasSize(40);
  }

  @Test void bareFreshReemitPinsTheNativeDuplicateHazard() {
    var styles = new TerminalStylePool();
    var emulator = new AnsiTerminalEmulator(80, 24);
    var frame = markedCanvas(80, 40, "TURN");
    renderFresh(frame, styles, emulator);
    renderFresh(frame, styles, emulator);
    var markers = emulator.transcript().stream().filter(line -> line.contains("TURN-row-")).toList();
    assertThat(new HashSet<>(markers).size()).isLessThan(markers.size());
  }

  @Test void hardResetWipeMakesOversizedRepaintSafe() {
    var styles = new TerminalStylePool();
    var emulator = new AnsiTerminalEmulator(80, 24);
    var frame = markedCanvas(80, 40, "TURN");
    var synced = renderFresh(frame, styles, emulator);
    var reset = synced.demoteToHardReset();
    var recovered = reset.render(frame, CanvasSerializer.contentRows(frame), 24, styles,
        emulator::feed, false);
    assertThat(recovered).isInstanceOf(InlineFrameRenderer.Synced.class);
    assertThat(emulator.transcript().stream().filter(line -> line.contains("TURN-row-")))
        .hasSize(40);
  }

  @Test void overflowedShrinkWithChangedPrefixEscalatesToHardReset() {
    var styles = new TerminalStylePool();
    var emulator = new AnsiTerminalEmulator(104, 75);
    var tall = markedCanvas(104, 220, "TALL");
    var synced = renderFresh(tall, styles, emulator, 75);
    var shorter = markedCanvas(104, 130, "SHORT");
    assertThat(synced.checkScrollback(shorter, 75)).isEmpty();
    var recovered = synced.demoteToHardReset().render(shorter,
        CanvasSerializer.contentRows(shorter), 75, styles, emulator::feed, false);
    assertThat(recovered).isInstanceOf(InlineFrameRenderer.Synced.class);
    assertThat(emulator.countContaining("TALL-row-")).isZero();
    assertThat(emulator.transcript().stream().filter(line -> line.contains("SHORT-row-")))
        .hasSize(130);
  }

  @Test void oldUnconditionalCommitSoftRecoveryStrandsTallRows() {
    var styles = new TerminalStylePool();
    var emulator = new AnsiTerminalEmulator(104, 75);
    var tall = markedCanvas(104, 220, "TALL");
    var synced = renderFresh(tall, styles, emulator, 75);
    int overflow = synced.rows() - 75;
    var marker = synced.scrollbackMarker(overflow);
    var stale = synced.commit(marker).demoteToStale();
    var shorter = markedCanvas(104, 130, "SHORT");
    stale.render(shorter, CanvasSerializer.contentRows(shorter), 75, styles,
        emulator::feed, false);
    assertThat(emulator.countContaining("TALL-row-")).isPositive();
  }

  private static InlineFrameRenderer.Synced renderFresh(TerminalCanvas canvas,
      TerminalStylePool styles, AnsiTerminalEmulator emulator) {
    return renderFresh(canvas, styles, emulator, 24);
  }

  private static InlineFrameRenderer.Synced renderFresh(TerminalCanvas canvas,
      TerminalStylePool styles, AnsiTerminalEmulator emulator, int terminalRows) {
    return (InlineFrameRenderer.Synced) new InlineFrameRenderer.Empty().seed().render(canvas,
        CanvasSerializer.contentRows(canvas), terminalRows, styles, emulator::feed, false);
  }

  private static TerminalCanvas markedCanvas(int width, int rows, String prefix) {
    var canvas = new TerminalCanvas(width, rows + 4);
    for (int row = 0; row < rows; row++) {
      canvas.writeText(0, row, prefix + "-row-" + row, 0);
    }
    return canvas;
  }
}
