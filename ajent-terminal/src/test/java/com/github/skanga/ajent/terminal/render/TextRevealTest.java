package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class TextRevealTest {
  @Test void settledTextIsImmediateAndStreamingTextGlidesWithoutSplittingSurrogates() {
    var settled = new TextReveal();
    assertThat(settled.begin("ready 🚀", false, 0).text()).isEqualTo("ready 🚀");

    var reveal = new TextReveal(90, 0.15, 0.16);
    assertThat(reveal.begin("a🚀bc", true, 0).text()).isEmpty();
    TextReveal.Frame first = reveal.update("a🚀bc", true, 20_000_000);
    assertThat(first.text()).isEqualTo("a");
    assertThat(first.animating()).isTrue();
    TextReveal.Frame complete = reveal.update("a🚀bc", true, 1_000_000_000);
    assertThat(complete.text()).isEqualTo("a🚀bc");
    assertThat(complete.animating()).isFalse();
  }

  @Test void finalizationDrainsByDeadlineAndLongFrameIsCapped() {
    String burst = "x".repeat(1_000);
    var reveal = new TextReveal();
    reveal.begin(burst, true, 0);
    TextReveal.Frame capped = reveal.update(burst, true, 10_000_000_000L);
    assertThat(capped.revealedCodePoints()).isBetween(1, 999);

    TextReveal.Frame frame = reveal.update(burst, false, 10_016_000_000L);
    for (int index = 0; index < 10 && frame.animating(); index++) {
      frame = reveal.update(burst, false, 10_032_000_000L + index * 16_000_000L);
    }
    assertThat(frame.text()).isEqualTo(burst);
  }

  @Test void replacementAndShrinkResetSafely() {
    var reveal = new TextReveal();
    reveal.begin("abcdef", true, 0);
    reveal.update("abcdef", true, 20_000_000);
    assertThat(reveal.update("xy", false, 30_000_000).text()).isEqualTo("xy");
    assertThat(reveal.update("zz", true, 40_000_000).text()).isEmpty();
    assertThat(reveal.update("zz", true, 60_000_000).text()).isEqualTo("z");
  }

  @Test void frameRejectsImpossibleBoundsAndConstructorNormalizesDeadline() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> new TextReveal.Frame("", false, 2, 1))
        .isInstanceOf(IllegalArgumentException.class);
    var reveal = new TextReveal(90, 0.15, 0);
    reveal.begin("abc", true, 0);
    TextReveal.Frame completed = reveal.update("abc", false, 1_000_000);
    assertThat(completed.animating()).isFalse();
    assertThat(completed.text()).isEqualTo("abc");
  }

  @Test void visualFrameGhostsAheadOfCursorPulsesAtLiveEdgeAndSettlesCleanly() {
    var reveal = new TextReveal(90, 0.15, 0.16);
    TextReveal.Frame initial = reveal.begin("abc", true, 0);
    assertThat(initial.visual(TerminalStyle.EMPTY).text()).isEqualTo("a  ");
    assertThat(initial.requiresAnimation()).isTrue();

    TextReveal.Frame caught = reveal.update("abc", true, 1_000_000_000L);
    assertThat(caught.animating()).isFalse();
    assertThat(UnicodeWidth.stringWidth(caught.visual(TerminalStyle.EMPTY).text(),
        UnicodeWidth.Mode.MODERN)).isEqualTo(3);
    assertThat(caught.visual(TerminalStyle.EMPTY).text()).isNotEqualTo("abc");
    assertThat(caught.visual(TerminalStyle.EMPTY).glyphs().getLast().style().bold()).isTrue();
    assertThat(caught.requiresAnimation()).isTrue();

    TextReveal.Frame closing = reveal.update("abc", false, 1_016_000_000L);
    assertThat(closing.requiresAnimation()).isTrue();
    TextReveal.Frame settled = reveal.update("abc", false, 1_500_000_000L);
    assertThat(settled.visual(TerminalStyle.EMPTY).text()).isEqualTo("abc");
    assertThat(settled.visual(TerminalStyle.EMPTY).glyphs())
        .allSatisfy(glyph -> assertThat(glyph.style()).isEqualTo(TerminalStyle.EMPTY));
    assertThat(settled.requiresAnimation()).isFalse();

    var quietReveal = new TextReveal();
    quietReveal.begin("abc", true, 0);
    quietReveal.update("abc", true, 1_000_000_000L);
    assertThat(quietReveal.update("abc", true, 5_100_000_000L).requiresAnimation()).isFalse();

    var resumed = new TextReveal();
    resumed.begin("abc", false, 0);
    TextReveal.Frame resumedLive = resumed.update("abc", true, 1_000_000);
    assertThat(resumedLive.requiresAnimation()).isTrue();
    assertThat(resumedLive.visual(TerminalStyle.EMPTY).glyphs().getLast().style().bold()).isTrue();
  }
}
