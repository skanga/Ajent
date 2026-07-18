package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Translation of the pinned md_shape_sweep streaming-height property oracle. */
final class MarkdownShapeSweepTest {
  private static final int WIDTH = 80;
  private static final long FRAME_NANOS = 16_000_000;

  @Test
  void everyPinnedMarkdownShapeHasMonotonicLiveHeight() {
    for (Shape shape : corpus()) {
      for (int chunk : new int[] {1, 3, 7}) exercise(shape, chunk);
    }
  }

  private static void exercise(Shape shape, int chunk) {
    var markdown = new StreamingMarkdown();
    markdown.setLive(true);
    int previousRows = markdown.render(WIDTH, 0).size();
    long now = 0;
    byte[] bytes = shape.body().getBytes(StandardCharsets.UTF_8);
    for (int end = chunk; end < bytes.length + chunk; end += chunk) {
      int boundary = Math.min(bytes.length, end);
      markdown.setContent(decodedPrefix(bytes, boundary));
      now += FRAME_NANOS;
      int rows = markdown.render(WIDTH, now).size();
      assertThat(rows).as("%s chunk=%s byte=%s", shape.name(), chunk, boundary)
          .isGreaterThanOrEqualTo(previousRows);
      previousRows = rows;
    }
    markdown.finish();
    now += FRAME_NANOS;
    for (int guard = 0; guard < 400 && markdown.requiresAnimation(); guard++) {
      int rows = markdown.render(WIDTH, now).size();
      assertThat(rows).as("%s chunk=%s drain=%s", shape.name(), chunk, guard)
          .isGreaterThanOrEqualTo(previousRows);
      previousRows = rows;
      now += FRAME_NANOS;
    }
    assertThat(markdown.requiresAnimation()).as("%s finalization", shape.name()).isFalse();
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
        shape("setext_h1", "Title line\n===\n\nbody text\n"),
        shape("setext_h2", "Sub title\n---\n\nbody text\n"),
        shape("hr_after_para", "some paragraph\n\n---\n\nmore text\n"),
        shape("loose_list", "- first item\n\n- second item\n\n- third item\n\nafter\n"),
        shape("nested_list", "- outer one\n  - inner a\n  - inner b\n- outer two\n\nafter\n"),
        shape("ordered_start", "3. third\n4. fourth\n5. fifth\n\nafter\n"),
        shape("task_list", "- [ ] todo one\n- [x] done two\n\nafter\n"),
        shape("quote_then_alert", "> [!NOTE]\n> this becomes an alert callout\n> with two rows\n\nafter\n"),
        shape("quote_lazy", "> quoted first line\nlazy continuation line\n\nafter\n"),
        shape("fence_tilde", "~~~py\nprint('hi')\nprint('bye')\n~~~\n\nafter\n"),
        shape("fence_nested_ticks", "````md\ninner ```js\ncode\n```\nstill inside\n````\n\nafter\n"),
        shape("fence_no_lang_close_immediately", "```\n```\n\nafter\n"),
        shape("indented_code", "    indented code line one\n    line two\n\nafter\n"),
        shape("table_wrapcell", "| A | B |\n|---|---|\n"
            + "| a very long cell body that will wrap in eighty columns for sure yes | b |\n"
            + "| c | d |\n\nafter\n"),
        shape("table_then_para", "| A | B |\n|---|---|\n| 1 | 2 |\n\n"
            + "plain paragraph after the table\n"),
        shape("heading_trailing_hashes", "## closed heading ##\n\nbody\n"),
        shape("html_block", "<div>\nraw html body\n</div>\n\nafter\n"),
        shape("link_ref", "See [the docs][d] for more.\n\n[d]: https://example.com\n\nafter\n"),
        shape("long_wrap_line", "x".repeat(300) + "\n\nafter\n"),
        shape("emphasis_spill", "some *emphasis that spans\n"
            + "multiple source lines* in a paragraph\n\nafter\n"),
        shape("list_then_fence", "- item one\n- item two\n\n```c\nint x;\n```\n\nafter\n"),
        shape("quote_code", "> quoted\n> ```\n> code in quote\n> ```\n\nafter\n"),
        shape("hard_break", "line one  \nline two\\\nline three\n\nafter\n"),
        shape("heading_h1_h6", "# one\n\n###### six\n\nbody\n"),
        shape("para_then_setext_trap", "could be paragraph\ncontinued here\n---\n\nafter\n"),
        shape("empty_lines_run", "para\n\n\n\n\npara two\n"),
        shape("footnote_like", "text with [^1] mark\n\n[^1]: the footnote body\n\nafter\n"),
        shape("bold_heading", "## **bold** heading with `code`\n\nbody\n"),
        shape("table_alignment", "| L | C | R |\n|:--|:-:|--:|\n| a | b | c |\n\nafter\n"),
        shape("strikethrough", "~~struck~~ text and more\n\nafter\n"),
        shape("push_summary_report", "Master pushed cleanly (`0b34b32..2528c70`).\n\n"
            + "Summary:\n"
            + "- **Pulled**: rebased local master onto `origin/master`, picking up the remote's "
            + "`0b34b32` (static-PIE build) commit.\n"
            + "- **Pushed**: your 2 commits (checkpoints + always-allow grants, now "
            + "`2539fb0`/`2528c70`) → `origin/master`. Fast-forward, no force needed.\n"
            + "- **Review work** stays local-only on the `review` branch + `review-feature` tag — "
            + "**not** on master, not pushed.\n\n"
            + "You asked to push \"Master\" specifically, so I left the `review` branch/tag local. "
            + "Want me to push those to the remote too (as a `review` branch you can pick up later), "
            + "or keep them local?\n"));
  }

  private static Shape shape(String name, String body) {
    return new Shape(name, body);
  }

  private record Shape(String name, String body) {}
}
