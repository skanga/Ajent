package com.github.skanga.ajent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AnthropicMarkdownStreamTest {
  @Test void loadsUtf8JsonlAndReplaysEveryDeltaThroughProductionMarkdown(@TempDir Path root)
      throws Exception {
    Path fixture = root.resolve("stream.jsonl");
    Files.writeString(fixture,
        "{\"t_ms\":0,\"delta\":\"# Title\\n\\n\"}\n"
            + "{\"t_ms\":24,\"delta\":\"- café\\n- two\\n\"}\n");

    List<AnthropicMarkdownStream.Delta> deltas = AnthropicMarkdownStream.loadFixture(fixture);
    assertThat(deltas).extracting(AnthropicMarkdownStream.Delta::tMs).containsExactly(0L, 24L);
    assertThat(deltas).extracting(AnthropicMarkdownStream.Delta::text)
        .containsExactly("# Title\n\n", "- café\n- two\n");

    var trace = new ByteArrayOutputStream();
    AnthropicMarkdownStream.replay(deltas,
        new AnthropicMarkdownStream.ReplayOptions(false, 80, true, 120, 0.8, 0, true),
        new PrintStream(new ByteArrayOutputStream()), new PrintStream(trace), ignored -> { });
    assertThat(trace.toString(StandardCharsets.UTF_8))
        .contains("frame=", "rows=", "visible=");
  }

  @Test void rejectsMissingArgumentsAndEmptyFixture(@TempDir Path root) throws Exception {
    var error = new ByteArrayOutputStream();
    assertThat(AnthropicMarkdownStream.run(new String[0],
        new PrintStream(new ByteArrayOutputStream()), new PrintStream(error))).isEqualTo(1);
    assertThat(error.toString(StandardCharsets.UTF_8)).contains("capture", "replay");

    Path empty = root.resolve("empty.jsonl");
    Files.writeString(empty, "\n");
    assertThat(AnthropicMarkdownStream.run(new String[] {"replay", empty.toString()},
        new PrintStream(new ByteArrayOutputStream()), new PrintStream(error))).isEqualTo(4);
  }
}
