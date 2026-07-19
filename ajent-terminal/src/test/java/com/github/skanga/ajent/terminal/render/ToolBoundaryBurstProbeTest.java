package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Simulated-time translation of tool_boundary_burst_probe.cpp. */
final class ToolBoundaryBurstProbeTest {
  private static final int FRAME_MILLIS = 16;
  private static final long FRAME_NANOS = FRAME_MILLIS * 1_000_000L;
  private static final int WIDTH = 100;
  private static final List<Case> CASES = List.of(
      new Case(1_500, 0, true), new Case(1_500, 32, true),
      new Case(1_500, 80, true), new Case(1_500, 160, true),
      new Case(1_500, 300, true), new Case(600, 0, true),
      new Case(600, 160, true), new Case(3_000, 0, true),
      new Case(3_000, 32, true), new Case(3_000, 80, true),
      new Case(3_000, 160, true), new Case(3_000, 300, true),
      new Case(1_500, 80, false), new Case(3_000, 100, false),
      new Case(3_000, 300, false));

  @Test void unsafeSnapReproducesABurstAndPanelDeferralEliminatesEveryOne() {
    String body = body();
    long reproduced = CASES.stream()
        .map(scenario -> run(body, scenario, false))
        .filter(Result::burst)
        .count();
    assertThat(reproduced).as("unsafe source scenarios reproducing the boundary burst")
        .isPositive();

    for (Case scenario : CASES) {
      Result fixed = run(body, scenario, true);
      assertThat(fixed.burst())
          .as("fixed boundary at %s cps, %sms gap, signal=%s",
              scenario.wireCps(), scenario.gapMillis(), scenario.blockClosedSignal())
          .isFalse();
      assertThat(fixed.deferMaximum()).isLessThanOrEqualTo(120);
    }
  }

  private static Result run(String body, Case scenario, boolean deferralFix) {
    var markdown = new StreamingMarkdown();
    markdown.setRevealEffects(true);
    markdown.setRevealPacing(90, 0.15);
    markdown.setLive(true);
    var deferral = new ToolPanelDeferral();
    double fedFraction = 0;
    int fed = 0;
    int previous = 0;
    long steadySum = 0;
    int steadyFrames = 0;
    long now = 0;
    double perFrame = scenario.wireCps() * FRAME_MILLIS / 1_000.0;

    while (fed < body.length()) {
      fedFraction += perFrame;
      int next = Math.min(body.length(), (int) fedFraction);
      fed = Math.max(fed, next);
      markdown.setContent(body.substring(0, fed));
      now += FRAME_NANOS;
      int cells = contentCells(markdown.render(WIDTH, now));
      steadySum += cells - previous;
      steadyFrames++;
      previous = cells;
    }
    double steadyMean = steadyFrames == 0 ? 0 : (double) steadySum / steadyFrames;

    int elapsed = 0;
    boolean finalized = false;
    while (elapsed < scenario.gapMillis()) {
      if (!finalized && (scenario.blockClosedSignal() || elapsed >= 120)) {
        markdown.finish();
        finalized = true;
      }
      now += FRAME_NANOS;
      elapsed += FRAME_MILLIS;
      previous = contentCells(markdown.render(WIDTH, now));
    }

    int deferMaximum = 0;
    if (!deferralFix) {
      markdown.snapRevealToEdge(now);
    } else {
      markdown.finish();
      ToolPanelDeferral.Decision decision;
      do {
        now += FRAME_NANOS;
        int cells = contentCells(markdown.render(WIDTH, now));
        deferMaximum = Math.max(deferMaximum, cells - previous);
        previous = cells;
        decision = deferral.next("tool", true, markdown.revealInProgress(), now);
      } while (decision == ToolPanelDeferral.Decision.HOLD);
      if (decision == ToolPanelDeferral.Decision.SNAP_AND_SHOW) {
        markdown.snapRevealToEdge(now);
      }
    }

    now += FRAME_NANOS;
    int cells = contentCells(markdown.render(WIDTH, now));
    int snapDelta = cells - previous;
    double ratio = steadyMean > 0.1 ? snapDelta / steadyMean : 0;
    boolean burst = snapDelta > 60 && ratio > 3 || deferMaximum > 120;
    return new Result(steadyMean, snapDelta, deferMaximum, burst);
  }

  private static int contentCells(List<MarkdownTerminalRenderer.Line> lines) {
    return (int) lines.stream().flatMapToInt(line -> line.text().codePoints())
        .filter(codePoint -> !Character.isWhitespace(codePoint))
        .filter(codePoint -> codePoint < 0x2500 || codePoint > 0x259f)
        .count();
  }

  private static String body() {
    var body = new StringBuilder();
    for (int paragraph = 0; paragraph < 18; paragraph++) {
      body.append("Paragraph ").append(paragraph)
          .append(" walks through the plan in enough words that the reveal ")
          .append("cursor has real distance to cover while the model keeps ")
          .append("streaming ahead of it, exactly like a long assistant turn ")
          .append("that ends by calling a tool.\n\n");
    }
    return body.append("Now let me read that file to confirm before editing.").toString();
  }

  private record Case(double wireCps, int gapMillis, boolean blockClosedSignal) {}
  private record Result(double steadyMean, int snapDelta, int deferMaximum, boolean burst) {}
}
