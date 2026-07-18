package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

final class UnicodeWidthTest {
  @Test void asciiLatinAndControlsMatchUnicode16GoldenValues() {
    for (int codePoint : new int[] {'A', 'z', '0', ' ', '.', 0x00e9, 0x03b1}) {
      assertThat(UnicodeWidth.of(codePoint)).isEqualTo(1);
    }
    assertThat(UnicodeWidth.of(0)).isZero();
    assertThat(UnicodeWidth.of(9)).isZero();
    assertThat(UnicodeWidth.of(0x1f)).isZero();
  }

  @Test void eastAsianWideRangesStayWideInBothModes() {
    for (int codePoint : new int[] {0x4e2d, 0x6587, 0x4e00, 0x9fff, 0x1100,
        0xac00, 0x3000, 0xff21}) {
      assertThat(UnicodeWidth.of(codePoint)).isEqualTo(2);
      assertThat(UnicodeWidth.of(codePoint, UnicodeWidth.Mode.LEGACY)).isEqualTo(2);
    }
  }

  @Test void emojiPresentationAndHolesMatchThePinnedTable() {
    for (int codePoint : new int[] {0x26a1, 0x2705, 0x274c, 0x2728, 0x231a,
        0x1f600, 0x1f680, 0x1f9e0}) assertThat(UnicodeWidth.of(codePoint)).isEqualTo(2);
    for (int codePoint : new int[] {0x2600, 0x2603, 0x26a0, 0x26a2}) {
      assertThat(UnicodeWidth.of(codePoint)).isEqualTo(1);
    }
    assertThat(UnicodeWidth.of(0x1f1e6, UnicodeWidth.Mode.MODERN)).isEqualTo(2);
    assertThat(UnicodeWidth.of(0x1f1e6, UnicodeWidth.Mode.LEGACY)).isEqualTo(1);
  }

  @Test void rangeBoundariesHaveNoOffByOneErrors() {
    assertThat(UnicodeWidth.of(0x10ff)).isEqualTo(1);
    assertThat(UnicodeWidth.of(0x1100)).isEqualTo(2);
    assertThat(UnicodeWidth.of(0x115f)).isEqualTo(2);
    assertThat(UnicodeWidth.of(0x1160)).isEqualTo(1);
    for (int codePoint : new int[] {0x4dbf, 0x4dc0, 0x4dff, 0x4e00, 0x9fff}) {
      assertThat(UnicodeWidth.of(codePoint)).isEqualTo(2);
    }
  }

  @Test void sumsCodePointsRatherThanUtf16UnitsAndRejectsInvalidValues() {
    assertThat(UnicodeWidth.stringWidth("A\u4e2d\ud83d\ude42", UnicodeWidth.Mode.MODERN))
        .isEqualTo(5);
    assertThatIllegalArgumentException().isThrownBy(() -> UnicodeWidth.of(0x110000));
  }
}
