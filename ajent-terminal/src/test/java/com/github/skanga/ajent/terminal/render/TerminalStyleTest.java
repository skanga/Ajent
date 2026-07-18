package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class TerminalStyleTest {
  @Test void publicStyleBuilderPortsEveryNativeAttributeCode() {
    assertThat(TerminalStyle.EMPTY.toSgr()).isEmpty();
    assertThat(TerminalStyle.EMPTY.withBold().toSgr()).isEqualTo("\u001b[1m");
    assertThat(TerminalStyle.EMPTY.withDim().toSgr()).isEqualTo("\u001b[2m");
    assertThat(TerminalStyle.EMPTY.withItalic().toSgr()).isEqualTo("\u001b[3m");
    assertThat(TerminalStyle.EMPTY.withUnderline().toSgr()).isEqualTo("\u001b[4m");
    assertThat(TerminalStyle.EMPTY.withInverse().toSgr()).isEqualTo("\u001b[7m");
    assertThat(TerminalStyle.EMPTY.withStrikethrough().toSgr()).isEqualTo("\u001b[9m");
  }

  @Test void colorsSerializeInNativeAnsiIndexedAndRgbForms() {
    assertThat(TerminalColor.green().foregroundSgr()).isEqualTo("32");
    assertThat(TerminalColor.blue().backgroundSgr()).isEqualTo("44");
    assertThat(TerminalColor.named(14).foregroundSgr()).isEqualTo("96");
    assertThat(TerminalColor.indexed(201).foregroundSgr()).isEqualTo("38;5;201");
    assertThat(TerminalColor.rgb(100, 150, 200).foregroundSgr())
        .isEqualTo("38;2;100;150;200");
    assertThat(TerminalColor.terminalDefault().backgroundSgr()).isEqualTo("49");
  }

  @Test void mergeIsAdditiveAndOverlayColorsWin() {
    var base = TerminalStyle.EMPTY.withBold().withForeground(TerminalColor.red());
    var overlay = TerminalStyle.EMPTY.withItalic().withForeground(TerminalColor.blue());
    var merged = base.merge(overlay);
    assertThat(merged.bold()).isTrue();
    assertThat(merged.italic()).isTrue();
    assertThat(merged.foreground()).isEqualTo(TerminalColor.blue());
    assertThat(TerminalStyle.EMPTY.isEmpty()).isTrue();
    assertThat(merged.isEmpty()).isFalse();
  }

  @Test void colorDegradationUsesThePinnedNearestPaletteAlgorithm() {
    assertThat(TerminalColor.rgb(255, 0, 0).degrade(2)).isEqualTo(TerminalColor.indexed(196));
    assertThat(TerminalColor.rgb(255, 0, 0).degrade(1)).isEqualTo(TerminalColor.named(9));
    assertThat(TerminalColor.indexed(196).degrade(1)).isEqualTo(TerminalColor.named(9));
    assertThat(TerminalColor.rgb(1, 2, 3).degrade(3))
        .isEqualTo(TerminalColor.rgb(1, 2, 3));
  }
}
