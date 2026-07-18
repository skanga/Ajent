package com.github.skanga.ajent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttachmentTextTest {
  @Test void parsesPlaceholdersInBothDirectionsAndRejectsMalformedTokens() {
    String token = AttachmentText.placeholder(12);
    assertThat(token).isEqualTo("\u0001ATT:12\u0001");
    assertThat(AttachmentText.placeholderLengthAt("x" + token, 1)).isEqualTo(token.length());
    assertThat(AttachmentText.placeholderLengthEndingAt("x" + token, 1 + token.length()))
        .isEqualTo(token.length());
    assertThat(AttachmentText.placeholderIndex(token, 0)).isEqualTo(12);
    assertThat(AttachmentText.placeholderLengthAt("\u0001ATT:x\u0001", 0)).isZero();
    assertThat(AttachmentText.placeholderLengthAt("\u0001ATT:1", 0)).isZero();
    assertThat(AttachmentText.placeholderLengthAt("", 0)).isZero();
    assertThat(AttachmentText.placeholderLengthAt("x", -1)).isZero();
    assertThat(AttachmentText.placeholderLengthAt("\u0001ATX:1\u0001", 0)).isZero();
    assertThat(AttachmentText.placeholderLengthAt("\u0001ATT:\u0001", 0)).isZero();
    assertThat(AttachmentText.placeholderLengthEndingAt(token, 0)).isZero();
    assertThat(AttachmentText.placeholderLengthEndingAt(token, token.length() + 1)).isZero();
    assertThat(AttachmentText.placeholderLengthEndingAt("plain", 5)).isZero();
    assertThat(AttachmentText.placeholderLengthEndingAt("x:\u0001", 3)).isZero();
    assertThat(AttachmentText.placeholderLengthEndingAt("xxxx:1\u0001", 7)).isZero();
    assertThat(AttachmentText.placeholderLengthEndingAt("\u0001ATX:1\u0001", 7)).isZero();
    String enormous = "\u0001ATT:999999999999999999999999999999999\u0001";
    assertThat(AttachmentText.placeholderIndex(enormous, 0)).isEqualTo(-1);
    assertThat(AttachmentText.placeholderIndex("bad", 0)).isEqualTo(-1);
    assertThatIllegalArgumentException().isThrownBy(() -> AttachmentText.placeholder(-1));
  }

  @Test void expandsAttachmentsInTextOrderAndDropsStrayOrInvalidSentinels() {
    var first = attachment(Attachment.Kind.PASTE, "alpha", "", "", "", 1);
    var second = attachment(Attachment.Kind.PASTE, "beta", "", "", "", 1);
    String text = "before " + AttachmentText.placeholder(1) + AttachmentText.placeholder(0)
        + " after\u0001 " + AttachmentText.placeholder(9);
    assertThat(AttachmentText.expand(text, List.of(first, second)))
        .isEqualTo("before \n\nbeta\n\nalpha\n\n after ");
    assertThat(AttachmentText.display(text, List.of(first, second)))
        .isEqualTo("before [Pasted: beta][Pasted: alpha] after ");
  }

  @Test void rendersEveryNativeAttachmentKind() {
    var file = attachment(Attachment.Kind.FILE_REF, "class A {}", "src/A.java", "", "", 1);
    var symbol = new Attachment(Attachment.Kind.SYMBOL, "one\ntwo\nthree\n".getBytes(
        StandardCharsets.UTF_8), "src/A.java", "", "work", 2, 4, 14);
    var image = new Attachment(Attachment.Kind.IMAGE, new byte[0], "shot.png", "image/png", "",
        0, 0, 2048);
    var output = attachment(Attachment.Kind.OUTPUT, "ok", "", "", "echo ok", 1);
    assertThat(AttachmentText.expand(AttachmentText.placeholder(0), List.of(file)))
        .isEqualTo("// path: src/A.java\nclass A {}\n");
    assertThat(AttachmentText.expand(AttachmentText.placeholder(0), List.of(symbol)))
        .contains("// symbol: work (src/A.java:2)", "one\ntwo\nthree");
    assertThat(AttachmentText.expand(AttachmentText.placeholder(0), List.of(image)))
        .isEqualTo("[image: shot.png]\n");
    assertThat(AttachmentText.expand(AttachmentText.placeholder(0), List.of(output)))
        .isEqualTo("I ran:\n```sh\necho ok\n```\noutput:\n```\nok\n```\n");
  }

  @Test void buildsCompactNativeLabels() {
    assertThat(AttachmentText.chipLabel(attachment(
        Attachment.Kind.FILE_REF, "", "src/main/A.java", "", "", 0))).isEqualTo("@A.java");
    assertThat(AttachmentText.chipLabel(new Attachment(Attachment.Kind.SYMBOL, new byte[0],
        "src/A.java", "", "run", 42, 0, 0))).isEqualTo("#run \u00b7 A.java:42");
    assertThat(AttachmentText.chipLabel(attachment(
        Attachment.Kind.OUTPUT, "ok\n", "", "", "echo ok", 2)))
        .isEqualTo("Output: echo ok \u00b7 2 lines \u00b7 3 B");
  }

  @Test void labelsCoverLargeAndEmptyAttachmentVariants() {
    var largeOutput = new Attachment(Attachment.Kind.OUTPUT, new byte[2048], "", "",
        "a very long\tcommand that exceeds thirty two characters", 0, 12, 2048);
    assertThat(AttachmentText.chipLabel(largeOutput))
        .isEqualTo("Output: a very long command that exceeds\u2026 \u00b7 12 lines \u00b7 2 KB");
    assertThat(AttachmentText.chipLabel(new Attachment(Attachment.Kind.IMAGE, new byte[0],
        "<clipboard>", "", "", 0, 0, 12)))
        .isEqualTo("Image \u00b7 <clipboard> \u00b7 image \u00b7 0 lines \u00b7 12 B");
    assertThat(AttachmentText.chipLabel(new Attachment(Attachment.Kind.IMAGE, new byte[0],
        "images/shot.png", "image/png", "", 0, 0, 2048)))
        .isEqualTo("Image \u00b7 shot.png \u00b7 image/png \u00b7 0 lines \u00b7 2 KB");
    assertThat(AttachmentText.chipLabel(new Attachment(Attachment.Kind.PASTE, new byte[0],
        "", "", "", 0, 0, 0))).isEqualTo("Pasted text \u00b7 0 B");
    String longPaste = "012345678901234567890123456789012345678901234567890123";
    assertThat(AttachmentText.chipLabel(attachment(
        Attachment.Kind.PASTE, longPaste, "", "", "", 1)))
        .isEqualTo("Pasted: 01234567890123456789012345678901234567890123456789\u2026");
    assertThat(AttachmentText.chipLabel(new Attachment(Attachment.Kind.PASTE,
        "a\nb".getBytes(StandardCharsets.UTF_8), "", "", "", 0, 2, 2048)))
        .isEqualTo("Pasted text \u00b7 2 lines \u00b7 2 KB");
  }

  @Test void expansionCoversEmptyBodiesInlineImagesAndNewlineCollapse() {
    var emptyOutput = attachment(Attachment.Kind.OUTPUT, "", "", "", "true", 0);
    var inlineImage = new Attachment(Attachment.Kind.IMAGE, new byte[0], "", "image/png", "",
        0, 0, 0);
    String text = AttachmentText.placeholder(0) + "\n\n\n" + AttachmentText.placeholder(1);
    assertThat(AttachmentText.expand(text, List.of(emptyOutput, inlineImage)))
        .isEqualTo("I ran:\n```sh\ntrue\n```\noutput:\n```\n```\n\n[image: <inline>]\n");
  }

  @Test void fileExpansionTruncatesUtf8SafelyAndSymbolUsesTwentyLineWindow() {
    String huge = "x".repeat(256 * 1024 - 1) + "\u20ac" + "tail";
    var file = attachment(Attachment.Kind.FILE_REF, huge, "src/huge.txt", "", "", 1);
    assertThat(AttachmentText.expand(AttachmentText.placeholder(0), List.of(file)))
        .startsWith("// path: src/huge.txt\n")
        .contains("[\u2026 file truncated, 6 bytes elided]")
        .doesNotContain("\ufffd");

    StringBuilder numbered = new StringBuilder();
    for (int line = 1; line <= 40; line++) numbered.append(line).append('\n');
    var symbol = new Attachment(Attachment.Kind.SYMBOL,
        numbered.toString().getBytes(StandardCharsets.UTF_8), "src/A.java", "", "target",
        20, 40, numbered.length());
    assertThat(AttachmentText.expand(AttachmentText.placeholder(0), List.of(symbol)))
        .contains("// symbol: target (src/A.java:20)\n15\n", "35\n")
        .doesNotContain("14\n", "36\n");
  }

  private static Attachment attachment(Attachment.Kind kind, String body, String path,
      String mediaType, String name, int lines) {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    return new Attachment(kind, bytes, path, mediaType, name, 0, lines, bytes.length);
  }
}
