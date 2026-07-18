package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

final class ColumnTextWrapperTest {
  @Test void wrapsByCellsRatherThanUtf16Units() {
    assertThat(ColumnTextWrapper.wrap("A中B🚀C", 3))
        .containsExactly("A中", "B🚀", "C");
  }

  @Test void preservesLogicalBlankLinesAndTrailingNewline() {
    assertThat(ColumnTextWrapper.wrap("a\n\nb\n", 8))
        .containsExactly("a", "", "b", "");
    assertThat(ColumnTextWrapper.wrap("", 8)).containsExactly("");
  }

  @Test void narrowViewportKeepsWideGlyphAtomicAndAlwaysProgresses() {
    assertThat(ColumnTextWrapper.wrap("中A", 1)).containsExactly("中", "A");
    assertThat(ColumnTextWrapper.wrap("\u0001A", 0)).containsExactly("\u0001A");
  }

  @Test void rejectsNullText() {
    assertThatNullPointerException().isThrownBy(() -> ColumnTextWrapper.wrap(null, 1));
  }
}
