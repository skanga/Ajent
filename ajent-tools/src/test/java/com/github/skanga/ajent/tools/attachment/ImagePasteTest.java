package com.github.skanga.ajent.tools.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.Attachment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImagePasteTest {
  private static final byte[] PNG = {
      (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1};

  @Test void recognizesEveryNativeImageSignature() {
    assertThat(ImagePaste.raw(PNG, "<paste>")).get().satisfies(image -> {
      assertThat(image.kind()).isEqualTo(Attachment.Kind.IMAGE);
      assertThat(image.mediaType()).isEqualTo("image/png");
      assertThat(image.path()).isEqualTo("<paste>");
    });
    assertThat(ImagePaste.raw(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}, "x"))
        .get().extracting(Attachment::mediaType).isEqualTo("image/jpeg");
    assertThat(ImagePaste.raw("GIF87a".getBytes(), "x"))
        .get().extracting(Attachment::mediaType).isEqualTo("image/gif");
    assertThat(ImagePaste.raw("RIFFxxxxWEBP".getBytes(), "x"))
        .get().extracting(Attachment::mediaType).isEqualTo("image/webp");
    assertThat(ImagePaste.raw("plain".getBytes(), "x")).isEmpty();
  }

  @Test void rejectsNearMissMagicPrefixesWithoutReadingPastTheirBounds() {
    assertThat(ImagePaste.raw(new byte[0], "x")).isEmpty();
    assertThat(ImagePaste.raw(new byte[8], "x")).isEmpty();
    assertThat(ImagePaste.raw(new byte[] {
        (byte) 0x89, 'X', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a}, "x")).isEmpty();
    assertThat(ImagePaste.raw(new byte[] {
        (byte) 0x89, 'P', 'X', 'G', 0x0d, 0x0a, 0x1a, 0x0a}, "x")).isEmpty();
    assertThat(ImagePaste.raw(new byte[] {
        (byte) 0x89, 'P', 'N', 'X', 0x0d, 0x0a, 0x1a, 0x0a}, "x")).isEmpty();
    assertThat(ImagePaste.raw(new byte[] {
        (byte) 0x89, 'P', 'N', 'G', 0, 0x0a, 0x1a, 0x0a}, "x")).isEmpty();
    assertThat(ImagePaste.raw(new byte[] {
        (byte) 0xff, 0, (byte) 0xff}, "x")).isEmpty();
    assertThat(ImagePaste.raw(new byte[] {
        (byte) 0xff, (byte) 0xd8, 0}, "x")).isEmpty();
    assertThat(ImagePaste.raw("GXF87a".getBytes(), "x")).isEmpty();
    assertThat(ImagePaste.raw("GIX87a".getBytes(), "x")).isEmpty();
    assertThat(ImagePaste.raw("GIFx7a".getBytes(), "x")).isEmpty();
    assertThat(ImagePaste.raw("GIF88a".getBytes(), "x")).isEmpty();
    assertThat(ImagePaste.raw("RIFXxxxxWEBP".getBytes(), "x")).isEmpty();
    assertThat(ImagePaste.raw("RIFFxxxxXEBP".getBytes(), "x")).isEmpty();
    assertThat(ImagePaste.raw("RIFFxxxxWEXP".getBytes(), "x")).isEmpty();
  }

  @Test void ingestsQuotedAndShellEscapedImagePaths(@TempDir Path root) throws Exception {
    Path image = root.resolve("screen shot.png");
    Files.write(image, PNG);
    String portable = image.toString().replace('\\', '/').replace(" ", "\\ ");

    assertThat(ImagePaste.path("  \"" + portable + "\"\r\n", Map.of())).get()
        .satisfies(attachment -> {
          assertThat(attachment.body()).containsExactly(PNG);
          assertThat(attachment.byteCount()).isEqualTo(PNG.length);
        });
  }

  @Test void expandsHomeAndRejectsNonImagesInvalidPathsAndOversizedFiles(@TempDir Path root)
      throws Exception {
    Files.write(root.resolve("shot.png"), PNG);
    assertThat(ImagePaste.path("~/shot.png", Map.of("HOME", root.toString())))
        .get().extracting(Attachment::mediaType).isEqualTo("image/png");
    String portable = root.resolve("shot.png").toString().replace('\\', '/');
    assertThat(ImagePaste.path("file://" + portable, Map.of())).isPresent();
    assertThat(ImagePaste.path("'" + portable + "'", Map.of())).isPresent();
    assertThat(ImagePaste.path("", Map.of())).isEmpty();
    assertThat(ImagePaste.path("''", Map.of())).isEmpty();
    assertThat(ImagePaste.path("'mismatched\"", Map.of())).isEmpty();
    assertThat(ImagePaste.path("file://", Map.of())).isEmpty();
    assertThat(ImagePaste.path("~/missing.png", Map.of())).isEmpty();
    assertThat(ImagePaste.path("missing\\", Map.of())).isEmpty();
    assertThat(ImagePaste.path("one\ntwo", Map.of())).isEmpty();
    assertThat(ImagePaste.path("x".repeat(4097), Map.of())).isEmpty();
    assertThat(ImagePaste.path("missing.png", Map.of())).isEmpty();
    assertThat(ImagePaste.path(root.toString(), Map.of())).isEmpty();
    assertThat(ImagePaste.path("\u0000", Map.of())).isEmpty();
    Files.write(root.resolve("large.png"), new byte[(int) ImagePaste.MAX_FILE_BYTES + 1]);
    assertThat(ImagePaste.path(root.resolve("large.png").toString(), Map.of())).isEmpty();
    Files.writeString(root.resolve("text.png"), "not an image");
    assertThat(ImagePaste.path(root.resolve("text.png").toString(), Map.of())).isEmpty();
  }
}
