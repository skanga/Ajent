package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exact workload translation of Ajent's md_cache_probe.cpp. */
final class StreamingMarkdownCacheProbeTest {
  private static final int WIDTH = 100;
  private static final int CHUNK = 24;
  private static final long FRAME_NANOS = 16_000_000;

  @Test void allLongStreamingShapesKeepAFlatAmortizedFrameProfile() {
    for (Shape shape : shapes()) {
      Metrics metrics = exercise(shape.body());
      boolean escapedMemo = metrics.fourthQuartileMicros() > 1_000
          && metrics.growthRatio() > 3.0;
      assertThat(escapedMemo).as("%s: %s", shape.name(), metrics).isFalse();
    }
  }

  private static Metrics exercise(String body) {
    var markdown = new StreamingMarkdown();
    markdown.setRevealEffects(true);
    markdown.setRevealPacing(2_000, 0.3);
    markdown.setLive(true);
    var frameMicros = new ArrayList<Double>();
    long now = 0;
    for (int fed = 0; fed < body.length(); fed += CHUNK) {
      int end = Math.min(body.length(), fed + CHUNK);
      markdown.append(body.substring(fed, end));
      now += FRAME_NANOS;
      long started = System.nanoTime();
      markdown.render(WIDTH, now);
      frameMicros.add((System.nanoTime() - started) / 1_000.0);
    }
    int frames = frameMicros.size();
    double first = mean(frameMicros, 0, frames / 4);
    double fourth = mean(frameMicros, 3 * frames / 4, frames);
    return new Metrics(frames, first, fourth, first > 0 ? fourth / first : 0);
  }

  private static double mean(List<Double> samples, int start, int end) {
    double total = 0;
    int count = 0;
    for (int index = start; index < end && index < samples.size(); index++) {
      total += samples.get(index);
      count++;
    }
    return count == 0 ? 0 : total / count;
  }

  private static List<Shape> shapes() {
    var loose = new StringBuilder();
    for (int index = 0; index < 400; index++) {
      loose.append("- loose item number ").append(index).append(" with a bit of text\n\n");
    }
    loose.append("after paragraph\n");

    var tight = new StringBuilder();
    for (int index = 0; index < 400; index++) {
      tight.append("- tight item number ").append(index).append(" with a bit of text\n");
    }
    tight.append("\nafter paragraph\n");

    var quoteFence = new StringBuilder("> intro\n> ```\n");
    for (int index = 0; index < 300; index++) {
      quoteFence.append("> quoted code line ").append(index).append('\n');
    }
    quoteFence.append("> ```\n\nafter\n");

    var paragraphs = new StringBuilder();
    for (int index = 0; index < 200; index++) {
      paragraphs.append("paragraph number ").append(index)
          .append(" has some words in it to wrap around maybe.\n\n");
    }

    var table = new StringBuilder("| Col A | Col B | Col C |\n|---|---|---|\n");
    for (int index = 0; index < 300; index++) {
      table.append("| row ").append(index).append(" | data | more |\n");
    }
    table.append("\nafter\n");

    var references = new StringBuilder("See [a][1] and [b][2].\n\n");
    for (int index = 0; index < 200; index++) {
      references.append('[').append(index).append("]: https://example.com/")
          .append(index).append('\n');
    }
    references.append("\nafter\n");

    return List.of(
        new Shape("loose_list_400", loose.toString()),
        new Shape("tight_list_400", tight.toString()),
        new Shape("quote_fence_300", quoteFence.toString()),
        new Shape("paras_200", paragraphs.toString()),
        new Shape("table_300", table.toString()),
        new Shape("link_refs_200", references.toString()));
  }

  private record Shape(String name, String body) {}
  private record Metrics(int frames, double firstQuartileMicros,
                         double fourthQuartileMicros, double growthRatio) {}
}
