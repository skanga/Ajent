package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Translation of the pinned reveal_smoothness_probe content-cell gates. */
final class MarkdownRevealSmoothnessTest {
  private static final int WIDTH = 100;
  private static final int BYTES_PER_FRAME = 6;
  private static final long FRAME_NANOS = 16_000_000;
  private static final int BURST_CAP = 60;

  @Test
  void everyMarkdownBlockTypeRevealsWithoutHeightShrinkOrContentBurst() {
    String body = document();
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    var markdown = new StreamingMarkdown();
    markdown.setLive(true);
    int previousCells = 0;
    int previousRows = 0;
    long now = 0;

    for (int end = BYTES_PER_FRAME; end < bytes.length + BYTES_PER_FRAME;
        end += BYTES_PER_FRAME) {
      int boundary = Math.min(bytes.length, end);
      markdown.setContent(decodedPrefix(bytes, boundary));
      now += FRAME_NANOS;
      Metrics metrics = metrics(markdown.render(WIDTH, now));
      assertFrame(metrics, previousCells, previousRows, "wire byte " + boundary);
      previousCells = metrics.cells();
      previousRows = metrics.rows();
    }

    markdown.finish();
    int guard = 0;
    while (markdown.requiresAnimation() && guard < 600) {
      now += FRAME_NANOS;
      Metrics metrics = metrics(markdown.render(WIDTH, now));
      assertFrame(metrics, previousCells, previousRows, "drain frame " + guard);
      previousCells = metrics.cells();
      previousRows = metrics.rows();
      guard++;
    }
    assertThat(markdown.requiresAnimation()).isFalse();
    assertThat(guard).isLessThan(600);
  }

  private static void assertFrame(Metrics current, int previousCells, int previousRows,
      String description) {
    assertThat(current.rows()).as("height at %s", description)
        .isGreaterThanOrEqualTo(previousRows);
    assertThat(current.cells() - previousCells).as("content delta at %s", description)
        .isLessThanOrEqualTo(BURST_CAP);
  }

  private static Metrics metrics(java.util.List<MarkdownTerminalRenderer.Line> lines) {
    int cells = 0;
    for (MarkdownTerminalRenderer.Line line : lines) {
      for (int codePoint : line.text().codePoints().toArray()) {
        if (codePoint == 0 || codePoint == ' ') continue;
        if (codePoint >= 0x2500 && codePoint <= 0x259f) continue;
        cells++;
      }
    }
    return new Metrics(cells, lines.size());
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

  private static String document() {
    return "The quick brown fox jumps over the lazy dog while the markdown "
        + "renderer paces every codepoint at a bounded readable speed so the "
        + "reveal feels like typing rather than pasting. Another sentence "
        + "follows to give the cursor a long uninterrupted paragraph to walk.\n\n"
        + "## Section heading streams in smoothly\n\n"
        + "```cpp\nint main() {\n    auto x = compute_all_the_things();\n"
        + "    for (int i = 0; i < 100; ++i) {\n        process(i, x);\n    }\n"
        + "    return x > 0 ? 0 : 1;\n}\n```\n\n"
        + "| Component | Latency | Throughput | Notes |\n"
        + "|-----------|---------|------------|-------|\n"
        + "| parser    | 0.2ms   | 480 MB/s   | SIMD-assisted scan |\n"
        + "| layout    | 1.1ms   | n/a        | per-block memoised |\n"
        + "| paint     | 0.4ms   | 60 fps     | cell-cache blits |\n"
        + "| serialize | 0.3ms   | n/a        | row-diff only |\n\n"
        + "- first item with enough words to wrap the terminal column nicely\n"
        + "- second item continues the pattern of readable streaming text\n"
        + "- third item keeps the cadence going for the reveal cursor\n"
        + "- fourth item closes out the list body\n\n"
        + "> A blockquote that streams in with the same left-to-right glide\n"
        + "> as everything around it, keeping the turn visually uniform.\n\n"
        + "And a closing paragraph after all the structured blocks, so the "
        + "settle ramp has ordinary prose to land on at the end of the turn.\n";
  }

  private record Metrics(int cells, int rows) {}
}
