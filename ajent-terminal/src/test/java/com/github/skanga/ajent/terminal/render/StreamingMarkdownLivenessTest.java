package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Translation of the widget-level gates in the pinned stream_liveness_test. */
final class StreamingMarkdownLivenessTest {
  private static final int WIDTH = 100;
  private static final long FRAME_NANOS = 16_000_000;

  @Test
  void liveWidgetStaysArmedAcrossAQuietWireGapAfterCursorReachesEdge() {
    var markdown = live("Ok");
    markdown.render(WIDTH, 0);
    markdown.render(WIDTH, 1_000_000_000);

    markdown.render(WIDTH, 6_000_000_000L);

    assertThat(markdown.isLive()).isTrue();
    assertThat(markdown.requiresAnimation()).isTrue();
    assertThat(markdown.settled()).isFalse();
  }

  @Test
  void finalizationDisarmsAndStaysDisarmedWithinTheNativeBound() {
    var markdown = live("Short reply body.");
    markdown.render(WIDTH, 0);
    markdown.finish();

    long now = 0;
    int frames = 0;
    while (markdown.requiresAnimation() && frames < 600) {
      now += FRAME_NANOS;
      markdown.render(WIDTH, now);
      frames++;
    }

    assertThat(frames).isLessThan(600);
    assertThat(markdown.requiresAnimation()).isFalse();
    assertThat(markdown.settled()).isTrue();
    for (int frame = 0; frame < 3; frame++) {
      markdown.render(WIDTH, now += FRAME_NANOS);
      assertThat(markdown.requiresAnimation()).isFalse();
    }
  }

  @Test
  void freezeGateOpensOnlyAfterRevealDrainAndBlocksNewWireBytes() {
    var markdown = live("word ".repeat(80));
    markdown.render(WIDTH, 0);
    markdown.finish();
    markdown.render(WIDTH, FRAME_NANOS);
    assertThat(markdown.settled()).isFalse();

    long now = FRAME_NANOS;
    for (int frame = 0; frame < 600 && !markdown.settled(); frame++) {
      markdown.render(WIDTH, now += FRAME_NANOS);
    }
    assertThat(markdown.settled()).isTrue();

    markdown.setLive(true);
    markdown.append("trailing");
    markdown.render(WIDTH, now += FRAME_NANOS);
    assertThat(markdown.settled()).isFalse();
  }

  @Test
  void liveOnlyStateBlocksFreezeEvenWithRevealEffectsDisabled() {
    var markdown = new StreamingMarkdown();
    markdown.setRevealEffects(false);
    markdown.setContent("Short settled reply body.");
    markdown.setLive(true);
    markdown.render(WIDTH, 0);

    assertThat(markdown.isLive()).isTrue();
    assertThat(markdown.requiresAnimation()).isTrue();
    assertThat(markdown.settled()).isFalse();

    markdown.finish();
    markdown.render(WIDTH, FRAME_NANOS);
    assertThat(markdown.isLive()).isFalse();
    assertThat(markdown.requiresAnimation()).isFalse();
    assertThat(markdown.settled()).isTrue();
  }

  private static StreamingMarkdown live(String content) {
    var markdown = new StreamingMarkdown();
    markdown.setContent(content);
    markdown.setLive(true);
    return markdown;
  }
}
