package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class TerminalStylePoolTest {
  @Test void defaultStyleIsZeroAndInterningIsStable() {
    var pool = new TerminalStylePool();
    var style = TerminalStyle.EMPTY.withBold().withForeground(TerminalColor.red());
    assertThat(pool.intern(TerminalStyle.EMPTY)).isZero();
    assertThat(pool.intern(style)).isEqualTo(pool.intern(style));
    assertThat(pool.get(pool.intern(style))).isEqualTo(style);
  }

  @Test void cachedFullSgrResetsAndSuppressesFaintAndDefaultSentinels() {
    var pool = new TerminalStylePool();
    int faint = pool.intern(TerminalStyle.EMPTY.withDim());
    int styled = pool.intern(TerminalStyle.EMPTY.withBold().withDim().withItalic()
        .withForeground(TerminalColor.green())
        .withBackground(TerminalColor.terminalDefault()));
    assertThat(pool.sgr(0)).isEqualTo("\u001b[0m");
    assertThat(pool.sgr(faint)).isEqualTo("\u001b[0m");
    assertThat(pool.sgr(styled)).isEqualTo("\u001b[0;1;3;32m");
    assertThat(pool.sgr(60_000)).isEqualTo("\u001b[0m");
  }

  @Test void unknownStateUsesFullResetWhileKnownStateUsesMinimalTransition() {
    var pool = new TerminalStylePool();
    int boldRed = pool.intern(TerminalStyle.EMPTY.withBold()
        .withForeground(TerminalColor.red()));
    int italicBlue = pool.intern(TerminalStyle.EMPTY.withItalic()
        .withForeground(TerminalColor.blue()));
    var output = new StringBuilder();
    pool.appendTransition(TerminalStylePool.UNKNOWN_STYLE, boldRed, output);
    assertThat(output).hasToString("\u001b[0;1;31m");
    output.setLength(0);
    pool.appendTransition(boldRed, italicBlue, output);
    assertThat(output).hasToString("\u001b[22;3;34m");
    output.setLength(0);
    pool.appendTransition(italicBlue, 0, output);
    assertThat(output).hasToString("\u001b[23;39m");
  }

  @Test void dimOnlyTransitionsAreWireNoOpsButRemainDistinctStyles() {
    var pool = new TerminalStylePool();
    int dim = pool.intern(TerminalStyle.EMPTY.withDim());
    assertThat(dim).isNotZero();
    var output = new StringBuilder();
    pool.appendTransition(0, dim, output);
    assertThat(output).isEmpty();
  }

  @Test void clearInvalidatesPoolIdentityAndDropsPriorIds() {
    var pool = new TerminalStylePool();
    int prior = pool.intern(TerminalStyle.EMPTY.withBold());
    long identity = pool.poolId();
    pool.clear();
    assertThat(pool.poolId()).isNotEqualTo(identity);
    assertThat(pool.size()).isOne();
    assertThat(pool.sgr(prior)).isEqualTo("\u001b[0m");
    assertThat(pool.overflowed()).isFalse();
  }

  @Test void colorLevelIsAppliedAtRendererEmissionTime() {
    var pool = new TerminalStylePool(2);
    int style = pool.intern(TerminalStyle.EMPTY
        .withForeground(TerminalColor.rgb(255, 0, 0)));
    assertThat(pool.sgr(style)).isEqualTo("\u001b[0;38;5;196m");
  }
}
