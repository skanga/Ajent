package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Assertion-bearing translation of md_shrink_debug.cpp's eight selectable fixtures. */
final class MarkdownShrinkDebugTest {
  private static final int WIDTH = 80;
  private static final long FRAME_NANOS = 16_000_000;

  @Test void noNativeDebugFixtureShrinksWhileArrivingByteByByte() {
    for (Shape shape : corpus()) {
      var markdown = new StreamingMarkdown();
      markdown.setRevealEffects(true);
      markdown.setRevealPacing(90, 0.3);
      markdown.setLive(true);
      byte[] bytes = shape.body().getBytes(StandardCharsets.UTF_8);
      int previousRows = 0;
      long now = 0;
      for (int fed = 1; fed <= bytes.length; fed++) {
        markdown.setContent(decodedPrefix(bytes, fed));
        now += FRAME_NANOS;
        int rows = markdown.render(WIDTH, now).size();
        assertThat(rows).as("%s byte=%s", shape.name(), fed)
            .isGreaterThanOrEqualTo(previousRows);
        previousRows = rows;
      }
    }
  }

  private static String decodedPrefix(byte[] bytes, int length) {
    var decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    CharBuffer output = CharBuffer.allocate(length);
    var result = decoder.decode(ByteBuffer.wrap(bytes, 0, length), output, false);
    assertThat(result.isError()).isFalse();
    output.flip();
    return output.toString();
  }

  private static List<Shape> corpus() {
    return List.of(
        new Shape("link_ref",
            "See [the docs][d] for more.\n\n[d]: https://example.com\n\nafter\n"),
        new Shape("loose_list", "- first item\n\n- second item\n\n- third item\n\nafter\n"),
        new Shape("nested_list",
            "- outer one\n  - inner a\n  - inner b\n- outer two\n\nafter\n"),
        new Shape("html_block", "<div>\nraw html body\n</div>\n\nafter\n"),
        new Shape("quote_code", "> quoted\n> ```\n> code in quote\n> ```\n\nafter\n"),
        new Shape("push_summary", "Master pushed cleanly (`0b34b32..2528c70`).\n\n"
            + "Summary:\n"
            + "- **Pulled**: rebased local master onto `origin/master`, picking up the remote's "
            + "`0b34b32` (static-PIE build) commit.\n"
            + "- **Pushed**: your 2 commits (checkpoints + always-allow grants, now "
            + "`2539fb0`/`2528c70`) \u2192 `origin/master`. Fast-forward, no force needed.\n"
            + "- **Review work** stays local-only on the `review` branch + `review-feature` tag "
            + "\u2014 **not** on master, not pushed.\n\n"
            + "You asked to push \"Master\" specifically.\n"),
        new Shape("bold_bullet", "- **alpha**: one\n- **beta**: two\n\nafter\n"),
        new Shape("para_then_list", "Summary:\n- item one\n- item two\n\nafter\n"));
  }

  private record Shape(String name, String body) {}
}
