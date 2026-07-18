package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;

final class InlineFrameRendererTest {
  @Test void emptySeedsFreshAndFirstDeliveryBecomesSynced() {
    var styles = new TerminalStylePool();
    var canvas = labeledCanvas(80, 3);
    var output = new StringBuilder();
    var empty = new InlineFrameRenderer.Empty();
    var fresh = empty.seed();
    var result = fresh.render(canvas, CanvasSerializer.contentRows(canvas), 24, styles,
        output::append, false);
    assertThat(result).isInstanceOf(InlineFrameRenderer.Synced.class);
    var synced = (InlineFrameRenderer.Synced) result;
    assertThat(synced.rows()).isEqualTo(3);
    assertThat(synced.width()).isEqualTo(80);
    assertThat(output).startsWith("\u001b[?25l\r\u001b[?7l");
    assertThatThrownBy(() -> empty.seed()).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> fresh.finalizeFrame()).isInstanceOf(IllegalStateException.class);
  }

  @Test void syncedRequiresBoundSingleUseWitnessesAndNoopStillHidesCursor() {
    var styles = new TerminalStylePool();
    var first = labeledCanvas(20, 3);
    var synced = firstRender(first, styles);
    var witness = synced.verify().orElseThrow();
    var second = labeledCanvas(20, 3);
    var proof = synced.checkScrollback(second, 24).orElseThrow();
    var output = new StringBuilder();
    var result = synced.render(second, CanvasSerializer.contentRows(second), 24, styles,
        output::append, witness, proof, false);
    assertThat(result).isInstanceOf(InlineFrameRenderer.Synced.class);
    assertThat(output).hasToString("\u001b[?25l");
    assertThatThrownBy(() -> synced.rows()).isInstanceOf(IllegalStateException.class);
  }

  @Test void staleDemotionSoftRepaintsAndReturnsToSynced() {
    var styles = new TerminalStylePool();
    var canvas = labeledCanvas(20, 3);
    var stale = firstRender(canvas, styles).demoteToStale();
    var output = new StringBuilder();
    var result = stale.render(canvas, CanvasSerializer.contentRows(canvas), 24, styles,
        output::append, false);
    assertThat(result).isInstanceOf(InlineFrameRenderer.Synced.class);
    assertThat(output).contains("\u001b[2A\r").endsWith("\u001b[J");
  }

  @Test void scrollbackMarkersClampShiftAndRejectSupersededGenerations() {
    var styles = new TerminalStylePool();
    var synced = firstRender(labeledCanvas(20, 6), styles);
    var marker = synced.scrollbackMarker(2);
    var staleDuplicate = synced.scrollbackMarker(1);
    assertThat(marker.rows()).isEqualTo(2);
    var remaining = synced.commit(marker);
    assertThat(remaining.rows()).isEqualTo(4);
    var unchanged = remaining.commit(staleDuplicate);
    assertThat(unchanged.rows()).isEqualTo(4);
    var all = unchanged.scrollbackMarker(99);
    assertThat(all.rows()).isEqualTo(4);
    assertThat(unchanged.commit(all).rows()).isZero();
  }

  @Test void finalizeRestoresClaimedCursorAndSealsTheState() {
    var styles = new TerminalStylePool();
    var synced = firstRender(labeledCanvas(20, 2), styles);
    var finalized = synced.finalizeFrame();
    assertThat(finalized.frame()).isInstanceOf(InlineFrameRenderer.Sealed.class);
    assertThat(finalized.restoreBytes()).isEqualTo("\u001b[?25h");
    assertThat(finalized.frame().finalizeFrame().restoreBytes()).isEmpty();
  }

  @Test void writeFailureRequiresHardResetAndWipeIsInsideSyncFrame() {
    var styles = new TerminalStylePool();
    var canvas = labeledCanvas(20, 2);
    var result = new InlineFrameRenderer.Empty().seed().render(canvas,
        CanvasSerializer.contentRows(canvas), 24, styles,
        ignored -> { throw new IOException("broken terminal"); }, false);
    assertThat(result).isInstanceOf(InlineFrameRenderer.HardReset.class);
    var output = new StringBuilder();
    var recovered = ((InlineFrameRenderer.HardReset) result).render(canvas,
        CanvasSerializer.contentRows(canvas), 24, styles, output::append, true);
    assertThat(recovered).isInstanceOf(InlineFrameRenderer.Synced.class);
    assertThat(output).startsWith("\u001b[?2026h\u001b[2J\u001b[3J\u001b[H\u001b[?25l")
        .endsWith("\u001b[?2026l");
  }

  private static InlineFrameRenderer.Synced firstRender(
      TerminalCanvas canvas, TerminalStylePool styles) {
    var result = new InlineFrameRenderer.Empty().seed().render(canvas,
        CanvasSerializer.contentRows(canvas), 24, styles, ignored -> {}, false);
    return (InlineFrameRenderer.Synced) result;
  }

  private static TerminalCanvas labeledCanvas(int width, int rows) {
    var canvas = new TerminalCanvas(width, rows + 4);
    for (int row = 0; row < rows; row++) canvas.writeText(0, row, "row_" + row, 0);
    return canvas;
  }
}
