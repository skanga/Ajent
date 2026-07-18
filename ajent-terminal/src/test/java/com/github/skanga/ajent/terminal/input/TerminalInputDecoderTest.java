package com.github.skanga.ajent.terminal.input;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class TerminalInputDecoderTest {
  @Test void decodesAsciiControlsAndUtf8AcrossChunks() {
    var decoder = new TerminalInputDecoder();
    assertThat(decoder.feedUtf8("a \r\t"))
        .containsExactly(character('a'), character(' '), special(TerminalKey.SpecialKey.ENTER),
            special(TerminalKey.SpecialKey.TAB));
    assertThat(decoder.feed(new byte[] {3})).containsExactly(
        character('c', new TerminalKey.Modifiers(true, false, false)));
    byte[] smile = "🙂".getBytes(StandardCharsets.UTF_8);
    assertThat(decoder.feed(new byte[] {smile[0], smile[1]})).isEmpty();
    assertThat(decoder.hasPending()).isTrue();
    assertThat(decoder.feed(new byte[] {smile[2], smile[3]})).containsExactly(character(0x1f642));
  }

  @Test void decodesNavigationSs3TildeAndShiftTab() {
    var decoder = new TerminalInputDecoder();
    assertThat(decoder.feed(ascii("\u001b[A\u001b[B\u001b[C\u001b[D\u001b[H\u001b[F")))
        .containsExactly(special(TerminalKey.SpecialKey.UP),
            special(TerminalKey.SpecialKey.DOWN), special(TerminalKey.SpecialKey.RIGHT),
            special(TerminalKey.SpecialKey.LEFT), special(TerminalKey.SpecialKey.HOME),
            special(TerminalKey.SpecialKey.END));
    assertThat(decoder.feed(ascii("\u001bOP\u001bOQ\u001b[3~\u001b[5~\u001b[Z")))
        .containsExactly(special(TerminalKey.SpecialKey.F1), special(TerminalKey.SpecialKey.F2),
            special(TerminalKey.SpecialKey.DELETE), special(TerminalKey.SpecialKey.PAGE_UP),
            special(TerminalKey.SpecialKey.BACK_TAB,
                new TerminalKey.Modifiers(false, false, true)));
  }

  @Test void decodesCsiAndModifyOtherKeysModifiers() {
    var decoder = new TerminalInputDecoder();
    assertThat(decoder.feed(ascii("\u001b[1;5D\u001b[27;2;13~")))
        .containsExactly(special(TerminalKey.SpecialKey.LEFT,
                new TerminalKey.Modifiers(true, false, false)),
            special(TerminalKey.SpecialKey.ENTER,
                new TerminalKey.Modifiers(false, false, true)));
  }

  @Test void decodesKittyUnicodeAndNavigationProtocol() {
    var decoder = new TerminalInputDecoder();
    assertThat(decoder.feed(ascii("\u001b[122;6u\u001b[57356;3u\u001b[13;2u")))
        .containsExactly(character('z', new TerminalKey.Modifiers(true, false, true)),
            special(TerminalKey.SpecialKey.UP,
                new TerminalKey.Modifiers(false, true, false)),
            special(TerminalKey.SpecialKey.ENTER,
                new TerminalKey.Modifiers(false, false, true)));
  }

  @Test void decodesAltKeysAndFlushesAStandaloneEscape() {
    var decoder = new TerminalInputDecoder();
    assertThat(decoder.feed(ascii("\u001bv\u001b\r"))).containsExactly(
        character('v', new TerminalKey.Modifiers(false, true, false)),
        special(TerminalKey.SpecialKey.ENTER,
            new TerminalKey.Modifiers(false, true, false)));
    assertThat(decoder.feed(new byte[] {0x1b})).isEmpty();
    assertThat(decoder.flushEscape()).containsExactly(special(TerminalKey.SpecialKey.ESCAPE));
    assertThat(decoder.flushEscape()).isEmpty();
  }

  @Test void accumulatesBracketedPasteAcrossArbitraryReads() {
    var decoder = new TerminalInputDecoder();
    assertThat(decoder.feed(ascii("\u001b[200~hello\u001b[20"))).isEmpty();
    assertThat(decoder.hasPending()).isTrue();
    assertThat(decoder.feed(ascii("1~"))).containsExactly(new TerminalEvent.Paste("hello"));
    assertThat(decoder.hasPending()).isFalse();
  }

  @Test void decodesFocusAndSgrMouseShapes() {
    var decoder = new TerminalInputDecoder();
    assertThat(decoder.feed(ascii("\u001b[I\u001b[O")))
        .containsExactly(new TerminalEvent.Focus(true), new TerminalEvent.Focus(false));
    assertThat(decoder.feed(ascii("\u001b[<0;5;3M\u001b[<2;1;1m\u001b[<64;4;2M")))
        .containsExactly(
            new TerminalEvent.Mouse(TerminalEvent.Button.LEFT, TerminalEvent.Kind.PRESS,
                5, 3, TerminalKey.Modifiers.NONE),
            new TerminalEvent.Mouse(TerminalEvent.Button.RIGHT, TerminalEvent.Kind.RELEASE,
                1, 1, TerminalKey.Modifiers.NONE),
            new TerminalEvent.Mouse(TerminalEvent.Button.SCROLL_UP, TerminalEvent.Kind.PRESS,
                4, 2, TerminalKey.Modifiers.NONE));
  }

  @Test void resetDropsPendingStateAndUnknownCsiBecomesQuestionKey() {
    var decoder = new TerminalInputDecoder();
    decoder.feed(new byte[] {(byte) 0xe2});
    decoder.reset();
    assertThat(decoder.hasPending()).isFalse();
    assertThat(decoder.feed(ascii("\u001b[999x"))).containsExactly(character('?'));
  }

  @Test void coversLegacyBackspaceSlashAltAndInvalidUtf8Forms() {
    var decoder = new TerminalInputDecoder();
    assertThat(decoder.feed(new byte[] {'\n', 0x7f, 0x08, 0x1f}))
        .containsExactly(special(TerminalKey.SpecialKey.ENTER),
            special(TerminalKey.SpecialKey.BACKSPACE),
            special(TerminalKey.SpecialKey.BACKSPACE,
                new TerminalKey.Modifiers(true, false, false)),
            character('/', new TerminalKey.Modifiers(true, false, false)));
    assertThat(decoder.feed(new byte[] {0x1b, 0x7f, 0x1b, 1}))
        .containsExactly(special(TerminalKey.SpecialKey.BACKSPACE,
                new TerminalKey.Modifiers(false, true, false)),
            character('a', new TerminalKey.Modifiers(true, true, false)));
    assertThat(decoder.feed("é".getBytes(StandardCharsets.UTF_8))).containsExactly(character('é'));
    assertThat(decoder.feed(new byte[] {(byte) 0xff})).containsExactly(character(255));
    assertThat(decoder.feed(new byte[] {(byte) 0xe2, 'x'})).containsExactly(character('x'));
  }

  @Test void coversEverySs3AndTildeFunctionMapping() {
    var decoder = new TerminalInputDecoder();
    String ss3 = "\u001bOA\u001bOB\u001bOC\u001bOD\u001bOH\u001bOF\u001bOR\u001bOS\u001bOX";
    assertThat(decoder.feed(ascii(ss3))).extracting(event -> ((TerminalEvent.Key) event).value().key())
        .containsExactly(TerminalKey.SpecialKey.UP, TerminalKey.SpecialKey.DOWN,
            TerminalKey.SpecialKey.RIGHT, TerminalKey.SpecialKey.LEFT,
            TerminalKey.SpecialKey.HOME, TerminalKey.SpecialKey.END,
            TerminalKey.SpecialKey.F3, TerminalKey.SpecialKey.F4,
            new TerminalKey.CharacterKey('?'));

    int[] codes = {1, 2, 4, 6, 11, 12, 13, 14, 15, 17, 18, 19, 20, 21, 23, 24};
    var sequences = new StringBuilder();
    for (int code : codes) sequences.append("\u001b[").append(code).append('~');
    assertThat(decoder.feed(ascii(sequences.toString()))).hasSize(codes.length);
    assertThat(decoder.feed(ascii("\u001b[999~"))).isEmpty();
  }

  @Test void coversKittySpecialCodesAndIgnoredInvalidCodepoints() {
    var decoder = new TerminalInputDecoder();
    int[] codes = {9, 27, 127, 57344, 57355, 57357, 57358, 57359, 57360, 57361,
        57362, 57363, 57364, 57365};
    var sequences = new StringBuilder();
    for (int code : codes) sequences.append("\u001b[").append(code).append(";1u");
    assertThat(decoder.feed(ascii(sequences.toString()))).hasSize(codes.length);
    assertThat(decoder.feed(ascii("\u001b[1u\u001b[99999999u"))).isEmpty();
    assertThat(decoder.feed(ascii("\u001b[65u"))).containsExactly(character('A'));
  }

  @Test void coversModifiedMovingAndMalformedMouseReports() {
    var decoder = new TerminalInputDecoder();
    assertThat(decoder.feed(ascii(
        "\u001b[<29;2;2M\u001b[<33;3;4M\u001b[<65;5;6M\u001b[<3;7;8M")))
        .containsExactly(
            new TerminalEvent.Mouse(TerminalEvent.Button.MIDDLE, TerminalEvent.Kind.PRESS,
                2, 2, new TerminalKey.Modifiers(true, true, true)),
            new TerminalEvent.Mouse(TerminalEvent.Button.MIDDLE, TerminalEvent.Kind.MOVE,
                3, 4, TerminalKey.Modifiers.NONE),
            new TerminalEvent.Mouse(TerminalEvent.Button.SCROLL_DOWN, TerminalEvent.Kind.PRESS,
                5, 6, TerminalKey.Modifiers.NONE),
            new TerminalEvent.Mouse(TerminalEvent.Button.NONE, TerminalEvent.Kind.PRESS,
                7, 8, TerminalKey.Modifiers.NONE));
    assertThat(decoder.feed(ascii("\u001b[<0;0;1M\u001b[<0;1M"))).isEmpty();
    assertThat(decoder.feed(ascii("\u001b[<x"))).containsExactly(character('?'));
  }

  @Test void acceptsEmptyCsiParametersAndSplitSequenceBytes() {
    var decoder = new TerminalInputDecoder();
    assertThat(decoder.feed(ascii("\u001b["))).isEmpty();
    assertThat(decoder.feed(ascii("1;;A"))).containsExactly(special(TerminalKey.SpecialKey.UP));
  }

  @Test void decodesOsc52TextAndBinaryAcrossBothTerminatorsAndChunks() {
    var decoder = new TerminalInputDecoder();
    assertThat(decoder.feed(ascii("\u001b]52;c;aGk=\u001b\\")))
        .containsExactly(new TerminalEvent.Paste("hi"));
    assertThat(decoder.feed(ascii("\u001b]52;c;bWF5YQ==\u0007")))
        .containsExactly(new TerminalEvent.Paste("maya"));
    assertThat(decoder.feed(ascii("\u001b]52;c;iVBORw0KGgo=\u001b\\")))
        .singleElement().satisfies(event -> assertThat(((TerminalEvent.Paste) event).content())
            .containsExactly((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47,
                (byte) 0x0d, (byte) 0x0a, (byte) 0x1a, (byte) 0x0a));
    assertThat(decoder.feed(ascii("\u001b]52;c;b2"))).isEmpty();
    assertThat(decoder.feed(ascii("s=\u001b\\")))
        .containsExactly(new TerminalEvent.Paste("ok"));
  }

  @Test void dropsEmptyRefusedMalformedAndUnrelatedOscReplies() {
    var decoder = new TerminalInputDecoder();
    assertThat(decoder.feed(ascii("\u001b]52;c;\u001b\\\u001b]52;c;?\u001b\\"))).isEmpty();
    assertThat(decoder.feed(ascii("\u001b]52;c;%%%\u001b\\\u001b]52-no-semi\u0007"))).isEmpty();
    assertThat(decoder.feed(ascii("\u001b]0;title\u0007\u001b]11;rgb:0/0/0\u001b\\"))).isEmpty();
  }

  @Test void reassemblesKittyImageChunksAndPrefersImagesOverText() {
    var decoder = new TerminalInputDecoder();
    String frames = osc("type=read:status=OK")
        + osc("type=read:status=DATA:mime=dGV4dC9wbGFpbg==;aGVsbG8=")
        + osc("type=read:status=DATA:mime=aW1hZ2UvcG5n;iVBORw0K")
        + osc("type=read:status=DATA:mime=aW1hZ2UvcG5n;Ggo=")
        + osc("type=read:status=DATA:mime=dGV4dC9wbGFpbg==;aWdub3JlZA==")
        + osc("type=read:status=DONE");
    assertThat(decoder.feed(ascii(frames))).singleElement().satisfies(event ->
        assertThat(((TerminalEvent.Paste) event).content())
            .containsExactly((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47,
                (byte) 0x0d, (byte) 0x0a, (byte) 0x1a, (byte) 0x0a));
  }

  @Test void handlesKittyTextErrorsStraysAndRecovery() {
    var decoder = new TerminalInputDecoder();
    String text = osc("type=read:status=OK")
        + osc("type=read:status=DATA:mime=dGV4dC9wbGFpbg==;aGVsbG8=")
        + osc("type=read:status=DONE");
    assertThat(decoder.feed(ascii(text))).containsExactly(new TerminalEvent.Paste("hello"));
    assertThat(decoder.feed(ascii(osc("type=read:status=DATA:mime=dGV4dC9wbGFpbg==;b2s=")
        + osc("type=read:status=DONE") + osc("type=write:status=DONE")))).isEmpty();
    assertThat(decoder.feed(ascii(osc("type=read:status=OK")
        + osc("type=read:status=DATA:mime=%%%25;bad")
        + osc("type=read:status=DONE")))).isEmpty();
    assertThat(decoder.feed(ascii(osc("type=read:status=OK")
        + osc("type=read:status=EPERM") + text))).containsExactly(
            new TerminalEvent.Paste("hello"));
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static String osc(String body) {
    return "\u001b]5522;" + body + "\u001b\\";
  }

  private static TerminalEvent.Key special(TerminalKey.SpecialKey key) {
    return special(key, TerminalKey.Modifiers.NONE);
  }

  private static TerminalEvent.Key special(
      TerminalKey.SpecialKey key, TerminalKey.Modifiers modifiers) {
    return new TerminalEvent.Key(new TerminalKey(key, modifiers));
  }

  private static TerminalEvent.Key character(int codePoint) {
    return character(codePoint, TerminalKey.Modifiers.NONE);
  }

  private static TerminalEvent.Key character(
      int codePoint, TerminalKey.Modifiers modifiers) {
    return new TerminalEvent.Key(
        new TerminalKey(new TerminalKey.CharacterKey(codePoint), modifiers));
  }
}
