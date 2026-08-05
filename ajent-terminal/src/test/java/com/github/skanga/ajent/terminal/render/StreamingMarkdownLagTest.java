package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Behavioral and performance-bound translation of Ajent's stream_md_lag_test.cpp. */
final class StreamingMarkdownLagTest {
  private static final int WIDTH = 100;
  private static final long FRAME_NANOS = 2_000_000;

  @Test void longTurnPerFrameCostDoesNotGrowByAnOrderOfMagnitude() {
    String body = longReply(400);
    assertThat(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        .isGreaterThanOrEqualTo(60_000);
    var markdown = liveMarkdown();
    long now = drive(markdown, body, 256, 2_000, 256, 0).nextNanos();

    Window early = drive(markdown, body, 2_000, 6_000, 256, now);
    Window late = drive(markdown, body, 40_000, 60_000, 256, early.nextNanos());

    assertThat(early.frames()).isPositive();
    assertThat(late.frames()).isPositive();
    assertThat(late.microsPerFrame() / Math.max(0.1, early.microsPerFrame()))
        .isLessThanOrEqualTo(24.0);
  }

  @Test void revealedPrefixCommitsBehindTheCursorAndFinishFlushesTheTail() {
    String body = longReply(400);
    Measurement small = streamAndMeasure(body, 6_000);
    Measurement large = streamAndMeasure(body, body.length());

    assertThat(large.tailBytes()).isLessThanOrEqualTo(16_000);
    assertThat(large.microsPerFrame() / Math.max(0.1, small.microsPerFrame()))
        .isLessThanOrEqualTo(24.0);

    var finished = liveMarkdown();
    finished.setContent(body);
    finished.render(WIDTH, 0);
    finished.finish();
    finished.render(WIDTH, FRAME_NANOS);
    assertThat(finished.committedBytes()).isGreaterThanOrEqualTo(body.length() / 2);
  }

  private static StreamingMarkdown liveMarkdown() {
    var markdown = new StreamingMarkdown();
    markdown.setRevealEffects(true);
    markdown.setRevealPacing(20_000, 0.05);
    markdown.setLive(true);
    return markdown;
  }

  private static Window drive(StreamingMarkdown markdown, String body,
      int from, int to, int step, long initialNanos) {
    long totalNanos = 0;
    int frames = 0;
    long now = initialNanos;
    for (int length = from; length <= to && length <= body.length(); length += step) {
      long started = System.nanoTime();
      markdown.setContent(body.substring(0, length));
      markdown.render(WIDTH, now);
      totalNanos += System.nanoTime() - started;
      frames++;
      now += FRAME_NANOS;
    }
    return new Window(totalNanos / 1_000.0 / Math.max(1, frames), frames, now);
  }

  private static Measurement streamAndMeasure(String body, int target) {
    var markdown = liveMarkdown();
    long now = 0;
    for (int length = 256; length <= target && length <= body.length(); length += 512) {
      markdown.setContent(body.substring(0, length));
      markdown.render(WIDTH, now);
      now += FRAME_NANOS;
    }
    if (!markdown.content().equals(body.substring(0, Math.min(target, body.length())))) {
      markdown.setContent(body.substring(0, Math.min(target, body.length())));
    }
    for (int frame = 0; frame < 40; frame++) {
      markdown.render(WIDTH, now);
      now += FRAME_NANOS;
    }
    int repetitions = 200;
    long started = System.nanoTime();
    for (int frame = 0; frame < repetitions; frame++) {
      markdown.render(WIDTH, now);
      now += FRAME_NANOS;
    }
    double micros = (System.nanoTime() - started) / 1_000.0 / repetitions;
    int sourceBytes = markdown.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    return new Measurement(micros, sourceBytes - markdown.committedBytes());
  }

  private static String longReply(int paragraphs) {
    var body = new StringBuilder(paragraphs * 220);
    for (int index = 0; index < paragraphs; index++) {
      if (index % 7 == 3) {
        body.append("Here is a short example for step ").append(index)
            .append(":\n\n```cpp\nint step_").append(index).append("(int x) {\n")
            .append("    return x * ").append(index + 1).append(" + 1;\n}\n```\n\n");
      } else if (index % 5 == 2) {
        body.append("Key points for section ").append(index).append(":\n\n")
            .append("- first consideration that matters here\n")
            .append("- second consideration with a bit more detail\n")
            .append("- third consideration to round things out\n\n");
      } else {
        body.append("## Section ").append(index).append("\n\n")
            .append("This paragraph explains part ").append(index)
            .append(" of the answer in enough words to look like a real assistant reply, ")
            .append("with some **bold** and `inline code` so the inline parser has real work ")
            .append("to do every frame.\n\n");
      }
    }
    return body.toString();
  }

  private record Window(double microsPerFrame, int frames, long nextNanos) {}
  private record Measurement(double microsPerFrame, int tailBytes) {}
}
