package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Exact behavioral translation of Ajent's stream_async_freeze_test.cpp. */
final class StreamingMarkdownAsyncFreezeTest {
  private static final int ASYNC_THRESHOLD = 16 * 1024;

  @Test void divergentAsyncBodyOverSixteenKibKeepsTheOldTreeUntilRenderAdoptsIt() {
    var markdown = new StreamingMarkdown();
    markdown.setLive(true);
    String bodyA = bigBody(20, 'A');
    markdown.setContentAsync(bodyA);

    assertThat(markdown.isParsing()).isFalse();
    assertThat(markdown.content()).isEqualTo(bodyA);

    String bodyB = bigBody(24, 'B');
    assertThat(bodyB.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        .isGreaterThanOrEqualTo(ASYNC_THRESHOLD);
    markdown.setContentAsync(bodyB);

    assertThat(markdown.isParsing()).isTrue();
    assertThat(markdown.content()).isEqualTo(bodyA);

    assertThat(drain(markdown)).isTrue();
    assertThat(markdown.content()).isEqualTo(bodyB);
  }

  @Test void synchronousDivergentBodiesNeverExposeAParsingWindow() {
    var markdown = new StreamingMarkdown();
    markdown.setLive(true);
    String bodyA = bigBody(20, 'A');
    String bodyB = bigBody(24, 'B');
    String bodyC = bigBody(28, 'C');

    markdown.setContent(bodyA);
    assertThat(markdown.isParsing()).isFalse();
    assertThat(markdown.content()).isEqualTo(bodyA);

    markdown.setContent(bodyB);
    assertThat(markdown.isParsing()).isFalse();
    assertThat(markdown.content()).isEqualTo(bodyB);

    markdown.setContent(bodyC);
    assertThat(markdown.isParsing()).isFalse();
    assertThat(markdown.content()).isEqualTo(bodyC);
  }

  @Test void largePrefixPreservingAsyncAppendStaysOnTheSynchronousFastPath() {
    var markdown = new StreamingMarkdown();
    String prefix = bigBody(20, 'A');
    markdown.setContent(prefix);
    String appended = prefix + bigBody(4, 'B');

    markdown.setContentAsync(appended);

    assertThat(markdown.isParsing()).isFalse();
    assertThat(markdown.content()).isEqualTo(appended);
  }

  private static boolean drain(StreamingMarkdown markdown) {
    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    while (System.nanoTime() < deadline) {
      markdown.render(100, System.nanoTime());
      if (!markdown.isParsing()) return true;
      Thread.onSpinWait();
    }
    return !markdown.isParsing();
  }

  private static String bigBody(int kibibytes, char tag) {
    var body = new StringBuilder(kibibytes * 1024 + 64);
    body.append("# Heading ").append(tag)
        .append("\n\nOpening paragraph tagged ").append(tag).append(".\n\n");
    for (int item = 0; body.length() < kibibytes * 1024; item++) {
      body.append("- item ").append(tag).append(item)
          .append(" some filler prose to pad the body out to size\n");
    }
    return body.toString();
  }
}
