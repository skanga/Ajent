package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Direct translation of Maya's scrollback_ledger.hpp witness-chain contract. */
final class ScrollbackLedgerTest {
  @Test void estimatesArePolicyOnlyAndPaintRestampsTheAuthoritativeHeight() {
    var ledger = new ScrollbackLedger<String>();
    ledger.seal("first", 0, false);
    ledger.seal("gap", 3, true);

    assertThat(ledger.elements()).containsExactly("first", "gap");
    assertThat(ledger.size()).isEqualTo(2);
    assertThat(ledger.blockRows(0)).isOne();
    assertThat(ledger.blockRows(1)).isEqualTo(3);
    assertThat(ledger.blockRows(9)).isZero();
    assertThat(ledger.rowTotal()).isEqualTo(4);
    assertThat(ledger.recordedAt(0)).isFalse();
    assertThat(ledger.separatorAt(1)).isTrue();

    ledger.recordPaint(0, 7);
    ledger.recordPaint(1, 2);
    ledger.recordPaint(0, 5);
    ledger.recordPaint(1, -1);

    assertThat(ledger.blockRows(0)).isEqualTo(5);
    assertThat(ledger.rowTotal()).isEqualTo(7);
    assertThat(ledger.recordedAt(0)).isTrue();
  }

  @Test void frontDropExtendsAcrossASeparatorAndMintsOnlyPaintMeasuredDebt() {
    var ledger = new ScrollbackLedger<String>();
    ledger.seal("first", 20, false);
    ledger.seal("gap", 20, true);
    ledger.seal("tail", 20, false);
    ledger.recordPaint(0, 4);
    ledger.recordPaint(1, 1);

    assertThat(ledger.dropFront(1)).isEqualTo(2);
    assertThat(ledger.elements()).containsExactly("tail");
    assertThat(ledger.hasDebt()).isTrue();
    assertThat(ledger.harvest().rows()).isEqualTo(5);
    assertThat(ledger.hasDebt()).isFalse();
    assertThat(ledger.harvest().empty()).isTrue();
  }

  @Test void provabilityClampBacksOffInsteadOfExposingAnUnrecordedSeparator() {
    var ledger = new ScrollbackLedger<String>();
    ledger.seal("first", 2, false);
    ledger.seal("gap", 1, true);
    ledger.seal("tail", 2, false);
    ledger.recordPaint(0, 2);

    assertThat(ledger.dropFront(2)).isZero();
    assertThat(ledger.elements()).containsExactly("first", "gap", "tail");
    assertThat(ledger.hasDebt()).isFalse();
  }

  @Test void leadingSeparatorsCanBeRemovedDuringFreshRehydrate() {
    var ledger = new ScrollbackLedger<String>();
    ledger.seal("gap-1", 2, true);
    ledger.seal("gap-2", 3, true);
    ledger.seal("content", 4, false);
    ledger.recordPaint(0, 1);

    assertThat(ledger.dropLeadingSeparators()).isEqualTo(2);
    assertThat(ledger.elements()).containsExactly("content");
    assertThat(ledger.harvest().rows()).isOne();
  }

  @Test void replacementResetsPaintProofAndClearPreservesTheObservedWidth() {
    var ledger = new ScrollbackLedger<String>();
    ledger.seal("old", 3, false);
    ledger.recordPaintWidth(78);
    ledger.recordPaint(0, 9);
    ledger.replace(0, "new", 0);

    assertThat(ledger.elements()).containsExactly("new");
    assertThat(ledger.blockRows(0)).isOne();
    assertThat(ledger.recordedAt(0)).isFalse();
    ledger.clear();
    assertThat(ledger).satisfies(value -> {
      assertThat(value.isEmpty()).isTrue();
      assertThat(value.paintWidth()).isEqualTo(78);
      assertThat(value.hasDebt()).isFalse();
    });
  }

  @Test void debtSaturatesAtTheTypedIntegerBoundary() {
    var ledger = new ScrollbackLedger<String>();
    ledger.seal("a", 1, false);
    ledger.seal("b", 1, false);
    ledger.recordPaint(0, Integer.MAX_VALUE);
    ledger.recordPaint(1, Integer.MAX_VALUE);

    assertThat(ledger.dropFront(2)).isEqualTo(2);
    assertThat(ledger.harvest().rows()).isEqualTo(Integer.MAX_VALUE);
  }

  @Test void typedDebtAdvancesOnlyASyncedRendererAndIsConsumedExactlyOnce() {
    var canvas = new TerminalCanvas(20, 10);
    for (int row = 0; row < 10; row++) canvas.writeText(0, row, "row-" + row, 0);
    var styles = new TerminalStylePool();
    var frame = new InlineFrameRenderer.Empty().seed().render(canvas,
        CanvasSerializer.contentRows(canvas), 10, styles, ignored -> {}, false);
    var ledger = new ScrollbackLedger<String>();
    ledger.seal("old", 5, false);
    ledger.recordPaint(0, 5);
    ledger.dropFront(1);
    var debt = ledger.harvest();

    var advanced = InlineFrameRenderer.commitScrollback(frame, debt);

    assertThat(advanced).isInstanceOfSatisfying(InlineFrameRenderer.Synced.class,
        synced -> assertThat(synced.rows()).isEqualTo(5));
    assertThatThrownBy(() -> InlineFrameRenderer.commitScrollback(advanced, debt))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already consumed");
  }

  @Test void fullyPaintedOverBudgetTrimCommitsExactlyTheRowsDroppedFromTheTop() {
    var ledger = new ScrollbackLedger<String>();
    for (int index = 0; index < 12; index++) {
      ledger.seal("block-" + index, 10, index % 2 == 1);
      ledger.recordPaint(index, 10);
    }

    FrozenScrollbackTrimPolicy.TrimResult trim = FrozenScrollbackTrimPolicy.trim(ledger, 10);

    assertThat(trim.droppedBlocks()).isPositive();
    assertThat(trim.droppedRows()).isPositive().isEqualTo(trim.committedRows());
    assertThat(ledger.separatorAt(0)).isFalse();
  }
}
