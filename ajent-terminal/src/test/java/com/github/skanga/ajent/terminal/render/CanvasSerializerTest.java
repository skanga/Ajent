package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class CanvasSerializerTest {
  @Test void derivesContentRowsFromTheBoundCanvas() {
    var canvas = new TerminalCanvas(5, 5);
    assertThat(CanvasSerializer.contentHeight(canvas)).isZero();
    canvas.set(0, 2, 'X', 0);
    var rows = CanvasSerializer.contentRows(canvas);
    assertThat(rows.value()).isEqualTo(3);
    assertThat(rows.belongsTo(canvas)).isTrue();
    assertThat(rows.belongsTo(new TerminalCanvas(5, 5))).isFalse();
  }

  @Test void serializesTrimmedRowsWithExactModesEraseAndResetBytes() {
    var styles = new TerminalStylePool();
    var canvas = new TerminalCanvas(5, 3);
    canvas.writeText(0, 0, "hi", 0);
    canvas.writeText(0, 1, "x", styles.intern(TerminalStyle.EMPTY.withBold()));
    assertThat(CanvasSerializer.serialize(canvas, styles, 2, 0)).isEqualTo(
        "\u001b[?7l\u001b[0mhi\u001b[K\r\n\u001b[1mx\u001b[0m\u001b[K\u001b[?7h\u001b[0m");
  }

  @Test void fullWidthRowsSkipEraseBecauseDecawmPinsTheCursorAtTheEdge() {
    var styles = new TerminalStylePool();
    var canvas = new TerminalCanvas(3, 1);
    canvas.writeText(0, 0, "abc", 0);
    assertThat(CanvasSerializer.serialize(canvas, styles)).isEqualTo(
        "\u001b[?7l\u001b[0mabc\u001b[?7h\u001b[0m");
  }

  @Test void rowRangeAndEmptyGeometryFollowNativeBounds() {
    var styles = new TerminalStylePool();
    var canvas = new TerminalCanvas(4, 3);
    canvas.writeText(0, 1, "B", 0);
    assertThat(CanvasSerializer.serialize(canvas, styles, 2, 1)).isEqualTo(
        "\u001b[?7l\u001b[0mB\u001b[K\u001b[?7h\u001b[0m");
    assertThat(CanvasSerializer.serialize(canvas, styles, 2, 2)).isEmpty();
    assertThat(CanvasSerializer.serialize(new TerminalCanvas(0, 2), styles)).isEmpty();
  }

  @Test void wideTrailsAreSkippedWithoutLosingFollowingCells() {
    var styles = new TerminalStylePool();
    var canvas = new TerminalCanvas(5, 1);
    canvas.writeText(0, 0, "\u4e2d!", 0);
    assertThat(CanvasSerializer.serialize(canvas, styles)).isEqualTo(
        "\u001b[?7l\u001b[0m\u4e2d!\u001b[K\u001b[?7h\u001b[0m");
  }

  @Test void differenceScansAndWideEdgeSnappingMatchPackedRows() {
    long blank = PackedCell.BLANK.pack();
    long lead = new PackedCell(0x4e2d, 0, 0, 1).pack();
    long trail = new PackedCell(' ', 0, 0, 2).pack();
    long[] previous = {blank, lead, trail, blank};
    long[] current = {blank, blank, blank, blank};
    assertThat(CanvasSerializer.firstDifference(current, previous)).isEqualTo(1);
    assertThat(CanvasSerializer.lastDifference(current, previous)).isEqualTo(2);
    assertThat(CanvasSerializer.snapFirstDifferenceLeft(2, current, previous)).isEqualTo(1);
    assertThat(CanvasSerializer.snapLastDifferenceRight(1, current, previous)).isEqualTo(2);
    assertThat(CanvasSerializer.firstDifference(current, current)).isEqualTo(4);
    assertThat(CanvasSerializer.lastDifference(current, current)).isEqualTo(-1);
  }
}
