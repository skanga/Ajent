package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class TextRevealEffectTest {
  @Test void unrevealedBodyIsBlankExceptForOneBrightSweepHead() {
    String body = "abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnop";
    TextRevealEffect.Parameters parameters =
        TextRevealEffect.Parameters.defaults(1_000, 0, 20, body.length())
            .withScramble(false);

    TextRevealEffect.Decoration result = TextRevealEffect.decorate(
        body, TerminalStyle.EMPTY, parameters);

    assertThat(result.glyphs()).hasSize(body.length());
    assertThat(IntStream.range(0, 20)
        .allMatch(index -> result.glyphs().get(index).text().equals(
            Character.toString(body.charAt(index)))
            && !TextRevealEffect.isGhost(result.glyphs().get(index).style()))).isTrue();
    assertThat(result.glyphs().get(20).text()).isEqualTo("u");
    assertThat(TextRevealEffect.isGhost(result.glyphs().get(20).style())).isFalse();
    assertThat(result.glyphs().subList(21, body.length()))
        .allSatisfy(glyph -> {
          assertThat(glyph.text()).isEqualTo(" ");
          assertThat(TextRevealEffect.isGhost(glyph.style())).isTrue();
        });
  }

  @Test void completedAndEmptyTextHaveNoGhostsOrMutation() {
    String body = "the quick brown fox jumps over the lazy dog";
    TextRevealEffect.Decoration complete = TextRevealEffect.decorate(body, TerminalStyle.EMPTY,
        TextRevealEffect.Parameters.defaults(2_000, 2_000,
            body.codePointCount(0, body.length()), body.codePointCount(0, body.length()))
            .withScramble(false));
    assertThat(complete.text()).isEqualTo(body);
    assertThat(complete.glyphs()).noneMatch(glyph -> TextRevealEffect.isGhost(glyph.style()));

    TextRevealEffect.Decoration empty = TextRevealEffect.decorate("", TerminalStyle.EMPTY,
        TextRevealEffect.Parameters.defaults(0, 0, 0, 0));
    assertThat(empty.text()).isEmpty();
    assertThat(empty.bytesChanged()).isFalse();
  }

  @Test void scrambleAndGhostDecorationPreserveTerminalWidthAcrossFrames() {
    String body = "The quick brown fox jumps over the lazy dog and keeps on running "
        + "down the road past the end of the visible viewport edge.";
    int width = UnicodeWidth.stringWidth(body, UnicodeWidth.Mode.MODERN);
    for (int reveal = 0; reveal <= 40; reveal += 8) {
      for (long age : new long[] {0, 120, 500, 900}) {
        for (long frame = 0; frame < 8; frame++) {
          TextRevealEffect.Decoration result = TextRevealEffect.decorate(body, TerminalStyle.EMPTY,
              TextRevealEffect.Parameters.defaults(frame * 47 + 17, age, reveal,
                  body.codePointCount(0, body.length())));
          assertThat(UnicodeWidth.stringWidth(result.text(), UnicodeWidth.Mode.MODERN))
              .isEqualTo(width);
          assertThat(result.glyphs()).allSatisfy(glyph -> assertThat(
              UnicodeWidth.stringWidth(glyph.text(), UnicodeWidth.Mode.MODERN))
              .isEqualTo(UnicodeWidth.of(glyph.sourceCodePoint())));
        }
      }
    }

    String wide = "typed λ then 跳 remains hidden";
    TextRevealEffect.Decoration ghosted = TextRevealEffect.decorate(wide, TerminalStyle.EMPTY,
        TextRevealEffect.Parameters.defaults(1_000, 0, 8,
            wide.codePointCount(0, wide.length())).withScramble(false));
    assertThat(UnicodeWidth.stringWidth(ghosted.text(), UnicodeWidth.Mode.MODERN))
        .isEqualTo(UnicodeWidth.stringWidth(wide, UnicodeWidth.Mode.MODERN));
  }

  @Test void clippingNeverSplitsUnicodeAndEndCaretIsWidthStable() {
    assertThat(TextRevealEffect.clipToCursor("the quick brown fox", 9)).isEqualTo("the quick");
    assertThat(TextRevealEffect.clipToCursor("aλμb", 2)).isEqualTo("aλ");
    assertThat(TextRevealEffect.clipToCursor("body", 999)).isEqualTo("body");
    assertThat(TextRevealEffect.clipToCursor("", 2)).isEmpty();

    TextRevealEffect.Decoration caret =
        TextRevealEffect.decorateEndCaret("hello", TerminalStyle.EMPTY, 100, 650);
    assertThat(caret.text()).isEqualTo("hello");
    assertThat(caret.glyphs().getLast().style().bold()).isTrue();
    TextRevealEffect.Decoration emptyCaret =
        TextRevealEffect.decorateEndCaret("", TerminalStyle.EMPTY, 100, 650);
    assertThat(emptyCaret.text()).isEqualTo("▊");
    assertThat(UnicodeWidth.stringWidth(emptyCaret.text(), UnicodeWidth.Mode.MODERN)).isOne();
  }

  @Test void gradientPulseAndScrambleMatchPinnedNativeBands() {
    assertThat(TextRevealEffect.trailStyle(0).orElseThrow())
        .isEqualTo(TerminalStyle.EMPTY.withForeground(TerminalColor.rgb(255, 90, 200))
            .withBold());
    assertThat(TextRevealEffect.trailStyle(120).orElseThrow())
        .isEqualTo(TerminalStyle.EMPTY.withForeground(TerminalColor.rgb(120, 230, 255))
            .withBold());
    assertThat(TextRevealEffect.trailStyle(220).orElseThrow().foreground())
        .isEqualTo(TerminalColor.rgb(130, 205, 238));
    assertThat(TextRevealEffect.trailStyle(700)).isEmpty();
    assertThat(TextRevealEffect.pulse01(0, 650)).isZero();
    assertThat(TextRevealEffect.pulse01(325, 650)).isEqualTo(1.0);
    assertThat(TextRevealEffect.pulse01(-325, 650)).isEqualTo(1.0);
    assertThat(TextRevealEffect.pulse01(5, 0)).isZero();
    assertThat(TextRevealEffect.scramblePick(17, 0, 44))
        .isEqualTo(TextRevealEffect.scramblePick(17, 0, 0));
    assertThat(TextRevealEffect.scrambleGlyphs())
        .contains(TextRevealEffect.scramblePick(17, 0, 45));
  }

  @Test void eagerFractionCanProtectStructureAndConfineEffectsToLastLine() {
    String row = "settled\n│ A 跳 │";
    TextRevealEffect.Decoration result = TextRevealEffect.decorate(row, TerminalStyle.EMPTY,
        TextRevealEffect.Parameters.defaults(90, 0, 0, 0)
            .withRevealFraction(0.25).withStructureProtection(true, true));

    assertThat(result.text()).startsWith("settled\n│").endsWith("│");
    assertThat(result.glyphs().subList(0, "settled\n".codePointCount(0, 8)))
        .allSatisfy(glyph -> assertThat(glyph.style()).isEqualTo(TerminalStyle.EMPTY));
    assertThat(UnicodeWidth.stringWidth(result.text(), UnicodeWidth.Mode.MODERN))
        .isEqualTo(UnicodeWidth.stringWidth(row, UnicodeWidth.Mode.MODERN));

    TextRevealEffect.Decoration visibleGhost = TextRevealEffect.decorate("abcdef",
        TerminalStyle.EMPTY, TextRevealEffect.Parameters.defaults(0, 0, 2, 6)
            .withScramble(false).withGhostBlank(false));
    assertThat(visibleGhost.text()).isEqualTo("abcdef");
    assertThat(visibleGhost.glyphs().subList(3, 6))
        .allSatisfy(glyph -> assertThat(TextRevealEffect.isGhost(glyph.style())).isTrue());
  }
}
