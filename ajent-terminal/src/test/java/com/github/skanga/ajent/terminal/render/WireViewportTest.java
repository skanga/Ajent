package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

final class WireViewportTest {
  @Test void rowArithmeticSaturatesAtViewportBounds() {
    var viewport = WireViewport.freshFrame(24);
    assertThat(viewport.bottomRow().up(1000).y()).isZero();
    assertThat(viewport.topRow().down(1000).y()).isEqualTo(23);
    assertThat(viewport.bottomRow().up(1000).viewport()).isEqualTo(viewport.id());
  }

  @Test void rowFactoryClampsAbsoluteRequests() {
    var viewport = WireViewport.freshFrame(10);
    assertThat(new int[] {
        viewport.row(-5).y(), viewport.row(0).y(), viewport.row(5).y(),
        viewport.row(9).y(), viewport.row(10).y(), viewport.row(99).y()
    }).containsExactly(0, 0, 5, 9, 9, 9);
  }

  @Test void arithmeticIsInverseInsideViewportAndDistancesAreSigned() {
    var viewport = WireViewport.freshFrame(24);
    var start = viewport.row(12);
    assertThat(start.up(5).down(5).y()).isEqualTo(12);
    assertThat(viewport.row(5).signedDistanceTo(viewport.row(10))).isEqualTo(5);
    assertThat(viewport.row(10).signedDistanceTo(viewport.row(5))).isEqualTo(-5);
    assertThat(viewport.row(5).signedDistanceTo(viewport.row(5))).isZero();
  }

  @Test void separateViewportsHaveDistinctProvenance() {
    var first = WireViewport.freshFrame(24);
    var second = WireViewport.freshFrame(24);
    assertThat(first.id()).isNotEqualTo(second.id());
    assertThat(first.topRow()).isNotEqualTo(second.topRow());
    assertThatIllegalArgumentException()
        .isThrownBy(() -> first.topRow().signedDistanceTo(second.topRow()));
  }

  @Test void emitsExactMinimalRelativeCursorMoves() {
    var viewport = WireViewport.freshFrame(24);
    assertThat(move(viewport.row(5), viewport.row(5))).isEmpty();
    assertThat(move(viewport.row(10), viewport.row(7))).isEqualTo("\u001b[3A");
    assertThat(move(viewport.row(2), viewport.row(6))).isEqualTo("\u001b[4B");
    assertThat(move(viewport.row(5), viewport.row(4))).isEqualTo("\u001b[1A");
    assertThat(move(viewport.row(5), viewport.row(5).up(100))).isEqualTo("\u001b[5A");
  }

  @Test void moveToColumnZeroAlwaysEmitsCarriageReturn() {
    var viewport = WireViewport.freshFrame(24);
    var output = new StringBuilder();
    WireViewport.emitMoveToColumnZero(output, viewport.row(10), viewport.row(8));
    assertThat(output).hasToString("\u001b[2A\r");
    output.setLength(0);
    WireViewport.emitMoveToColumnZero(output, viewport.row(3), viewport.row(3));
    assertThat(output).hasToString("\r");
  }

  @Test void nonPositiveHeightCollapsesToOneRow() {
    var viewport = WireViewport.freshFrame(0);
    assertThat(viewport.height()).isOne();
    assertThat(viewport.topRow().y()).isZero();
    assertThat(viewport.bottomRow().y()).isZero();
  }

  @Test void extremeCountsCannotOverflowOutsideViewport() {
    var viewport = WireViewport.redraw(24, Integer.MAX_VALUE);
    assertThat(viewport.row(12).up(Integer.MAX_VALUE)).isEqualTo(viewport.topRow());
    assertThat(viewport.row(12).down(Integer.MAX_VALUE)).isEqualTo(viewport.bottomRow());
  }

  private static String move(WireViewport.Row from, WireViewport.Row to) {
    var output = new StringBuilder();
    WireViewport.emitMove(output, from, to);
    return output.toString();
  }
}
