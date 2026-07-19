package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Simulated-time, assertion-bearing translation of reveal_lag_probe.cpp. */
final class RevealLagProbeTest {
  private static final int FRAME_MILLIS = 1_000 / 60;
  private static final long FRAME_NANOS = FRAME_MILLIS * 1_000_000L;

  @Test void eagerCodeTailGlidesThroughTheQuietWireFinalizeBoundary() {
    String body = body(60);
    var markdown = new StreamingMarkdown();
    markdown.setRevealEffects(true);
    markdown.setLive(true);
    markdown.setRevealPacing(90, 0.15);
    double owed = 0;
    int fed = 0;
    int quietMillis = 0;
    long now = 0;
    long finalizeRequested = -1;
    long revealDrained = -1;

    for (int frame = 0; frame < 60 * 30; frame++) {
      owed += 800 * (FRAME_MILLIS / 1_000.0);
      int take = Math.min((int) owed, body.length() - fed);
      if (take > 0) {
        fed += take;
        owed -= take;
      }
      markdown.setContent(body.substring(0, fed));
      now += FRAME_NANOS;
      boolean wireDone = fed == body.length();
      quietMillis = wireDone ? quietMillis + FRAME_MILLIS : 0;
      if (wireDone && quietMillis >= 120 && markdown.revealInProgress()
          && markdown.isLive()) {
        markdown.requestFinalize(Duration.ofMillis(160));
        finalizeRequested = now;
      }
      markdown.render(100, now);
      if (finalizeRequested >= 0 && !markdown.revealInProgress()) {
        revealDrained = now;
        break;
      }
    }

    assertThat(fed).isEqualTo(body.length());
    assertThat(finalizeRequested).isPositive();
    assertThat(revealDrained).isGreaterThanOrEqualTo(finalizeRequested);
    assertThat(revealDrained - finalizeRequested)
        .isLessThanOrEqualTo(Duration.ofMillis(192).toNanos());
    assertThat(markdown.revealInProgress()).isFalse();
    assertThat(markdown.isLive()).isFalse();
  }

  private static String body(int codeLines) {
    var prose = new StringBuilder("Here is the refactor and the resulting file.\n\n");
    for (int paragraph = 0; paragraph < 4; paragraph++) {
      prose.append("Paragraph ").append(paragraph)
          .append(" explains the change in enough words to wrap a couple of ")
          .append("rows in a normal terminal width so the reveal has work.\n\n");
    }
    prose.append("```cpp\n");
    for (int line = 0; line < codeLines; line++) {
      prose.append("    const auto v").append(line).append(" = compute(")
          .append(line).append(");\n");
    }
    return prose.append("```\n").toString();
  }
}
