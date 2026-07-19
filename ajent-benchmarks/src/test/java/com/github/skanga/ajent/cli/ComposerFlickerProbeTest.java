package com.github.skanga.ajent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class ComposerFlickerProbeTest {
  @Test void streamsTheNativeTightListShapeThroughTheProductionInlineRenderer() {
    var report = new ByteArrayOutputStream();
    ComposerFlickerProbe.Metrics metrics = ComposerFlickerProbe.run(
        12, 64, new PrintStream(report));

    assertThat(metrics.frames()).isGreaterThan(12);
    assertThat(metrics.wire().total()).isPositive();
    assertThat(metrics.rowsRewrittenMean()).isGreaterThanOrEqualTo(0);
    assertThat(report.toString(StandardCharsets.UTF_8)).contains(
        "composer row trajectory", "content-height shrink events",
        "wire bytes/frame", "out-of-order item appearances");
  }

  @Test void ansiEmulatorHandlesSplitCursorAndEraseOperations() {
    var screen = new ComposerFlickerProbe.AnsiScreen(12, 3);
    screen.feed("alpha\r\n> prompt");
    screen.feed("\u001b[1A\r\u001b[Kbeta");

    assertThat(screen.screen()).containsExactly(
        "beta        ", "> prompt    ", "            ");
  }
}
