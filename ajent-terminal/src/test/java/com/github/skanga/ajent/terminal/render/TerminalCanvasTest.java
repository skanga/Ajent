package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class TerminalCanvasTest {
  @Test void dimensionsSetGetAndOutOfBoundsMatchNativeCanvas() {
    var canvas = new TerminalCanvas(10, 5);
    assertThat(canvas.width()).isEqualTo(10);
    assertThat(canvas.height()).isEqualTo(5);
    assertThat(canvas.cellCount()).isEqualTo(50);
    canvas.set(3, 2, 'X', 42);
    assertThat(canvas.get(3, 2)).isEqualTo(new PackedCell('X', 42, 0, 0));
    canvas.set(-1, 0, 'Q', 0);
    canvas.set(10, 0, 'Q', 0);
    assertThat(canvas.get(-1, 0)).isEqualTo(PackedCell.BLANK);
    assertThat(canvas.get(0, 0)).isEqualTo(PackedCell.BLANK);
  }

  @Test void writesUnicodeTextAndWidePairsUsingCodePointWidths() {
    var canvas = new TerminalCanvas(20, 2);
    canvas.writeText(2, 1, "hi\u4e2d!", 7);
    assertThat(canvas.get(2, 1).character()).isEqualTo('h');
    assertThat(canvas.get(3, 1).character()).isEqualTo('i');
    assertThat(canvas.get(4, 1)).isEqualTo(new PackedCell(0x4e2d, 7, 0, 1));
    assertThat(canvas.get(5, 1)).isEqualTo(new PackedCell(' ', 7, 0, 2));
    assertThat(canvas.get(6, 1).character()).isEqualTo('!');
    assertThat(canvas.lastContentColumn(1)).isEqualTo(6);
  }

  @Test void refusesWideOrphansAtCanvasAndClipEdges() {
    var canvas = new TerminalCanvas(5, 2);
    canvas.set(4, 0, 'X', 0, 1);
    assertThat(canvas.get(4, 0)).isEqualTo(PackedCell.BLANK);
    canvas.pushClip(new TerminalCanvas.Rect(1, 0, 2, 2));
    canvas.set(2, 0, 'X', 0, 1);
    canvas.set(1, 1, ' ', 0, 2);
    canvas.popClip();
    assertThat(canvas.get(2, 0)).isEqualTo(PackedCell.BLANK);
    assertThat(canvas.get(1, 1)).isEqualTo(PackedCell.BLANK);
  }

  @Test void fillAndNestedClipsUseRectangleIntersection() {
    var canvas = new TerminalCanvas(10, 6);
    canvas.pushClip(new TerminalCanvas.Rect(1, 1, 5, 4));
    try (var ignored = canvas.clipScope(new TerminalCanvas.Rect(2, 2, 2, 2))) {
      canvas.fill(new TerminalCanvas.Rect(0, 0, 10, 6), '*', 0);
    }
    assertThat(canvas.get(2, 2).character()).isEqualTo('*');
    assertThat(canvas.get(3, 3).character()).isEqualTo('*');
    assertThat(canvas.get(1, 1)).isEqualTo(PackedCell.BLANK);
    assertThat(canvas.clipDepth()).isOne();
    canvas.popClip();
    canvas.set(0, 0, 'B', 0);
    assertThat(canvas.get(0, 0).character()).isEqualTo('B');
  }

  @Test void clippedBlitsBlankWideOrphansAtBothEdges() {
    long[] source = {
        new PackedCell('A', 0, 0, 0).pack(),
        new PackedCell('X', 0, 0, 1).pack(),
        new PackedCell(' ', 0, 0, 2).pack(),
        new PackedCell('B', 0, 0, 0).pack()
    };
    var right = new TerminalCanvas(10, 2);
    right.pushClip(new TerminalCanvas.Rect(0, 0, 2, 2));
    right.blitPackedRow(0, 0, source, true);
    assertThat(right.get(0, 0).character()).isEqualTo('A');
    assertThat(right.get(1, 0)).isEqualTo(PackedCell.BLANK);

    var left = new TerminalCanvas(10, 2);
    left.pushClip(new TerminalCanvas.Rect(2, 0, 2, 2));
    left.blitPackedRow(0, 0, source, true);
    assertThat(left.get(2, 0)).isEqualTo(PackedCell.BLANK);
    assertThat(left.get(3, 0).character()).isEqualTo('B');
  }

  @Test void contentExtentsAndClearRowFollowVisibleCells() {
    var canvas = new TerminalCanvas(10, 4);
    canvas.writeText(0, 0, "row0", 0);
    canvas.writeText(0, 2, "row2", 0);
    assertThat(canvas.maxContentRow()).isEqualTo(2);
    assertThat(canvas.lastContentColumn(0)).isEqualTo(3);
    canvas.clearRow(2);
    assertThat(canvas.lastContentColumn(2)).isEqualTo(-1);
    assertThat(canvas.maxContentRow()).isZero();
    canvas.clearRow(0);
    assertThat(canvas.maxContentRow()).isEqualTo(-1);
  }

  @Test void clearsResizeDamageAndLifecycleRemainCoherent() {
    var canvas = new TerminalCanvas(5, 3);
    assertThat(canvas.stage()).isEqualTo(TerminalCanvas.Stage.DRAINED);
    canvas.set(0, 0, 'Q', 0);
    assertThat(canvas.stage()).isEqualTo(TerminalCanvas.Stage.PAINTED);
    long epoch = canvas.rowEpoch(0);
    canvas.set(0, 0, 'Q', 0);
    assertThat(canvas.rowEpoch(0)).isEqualTo(epoch);
    canvas.clear();
    assertThat(canvas.get(0, 0)).isEqualTo(PackedCell.BLANK);
    assertThat(canvas.damage()).isEqualTo(new TerminalCanvas.Rect(0, 0, 5, 3));
    assertThat(canvas.stage()).isEqualTo(TerminalCanvas.Stage.DRAINED);
    canvas.resetDamage();
    assertThat(canvas.damage().isEmpty()).isTrue();
    canvas.markAllDamaged();
    assertThat(canvas.damage()).isEqualTo(new TerminalCanvas.Rect(0, 0, 5, 3));
    canvas.resize(20, 10);
    assertThat(canvas.cellCount()).isEqualTo(200);
    assertThat(canvas.clipDepth()).isZero();
  }

  @Test void partialClearPreservesSurvivingExtent() {
    var canvas = new TerminalCanvas(8, 5);
    canvas.writeText(0, 0, "top", 0);
    canvas.writeText(0, 3, "tail", 0);
    canvas.clearRows(2);
    assertThat(canvas.get(0, 0)).isEqualTo(PackedCell.BLANK);
    assertThat(canvas.get(0, 3).character()).isEqualTo('t');
    assertThat(canvas.maxContentRow()).isEqualTo(3);
    assertThat(canvas.damage()).isEqualTo(new TerminalCanvas.Rect(0, 0, 8, 2));
  }

  @Test void clearBelowPreservesFrozenPrefixAndCapsTheClearedBand() {
    var canvas = new TerminalCanvas(8, 6);
    canvas.writeText(0, 0, "frozen", 0);
    canvas.writeText(0, 2, "live", 0);
    canvas.clearBelow(1, 4);
    assertThat(canvas.get(0, 0).character()).isEqualTo('f');
    assertThat(canvas.get(0, 2)).isEqualTo(PackedCell.BLANK);
    assertThat(canvas.maxContentRow()).isZero();
    assertThat(canvas.damage()).isEqualTo(new TerminalCanvas.Rect(0, 1, 8, 3));
    assertThat(canvas.stage()).isEqualTo(TerminalCanvas.Stage.DRAINED);
  }

  @Test void cachedBlitSkipsOnlyWhenTheDestinationBytesAlreadyMatch() {
    var canvas = new TerminalCanvas(6, 2);
    long[] source = {
        new PackedCell('A', 0, 0, 0).pack(), new PackedCell('B', 0, 0, 0).pack()
    };
    assertThat(canvas.blitPackedRowCached(1, 0, source, true, 1)).isFalse();
    long epoch = canvas.rowEpoch(0);
    canvas.clearRows(0); // Reset frame bookkeeping without touching the destination.
    assertThat(canvas.blitPackedRowCached(1, 0, source, true, 1)).isTrue();
    assertThat(canvas.rowEpoch(0)).isEqualTo(epoch);
    assertThat(canvas.lastContentColumn(0)).isEqualTo(2);
  }
}
