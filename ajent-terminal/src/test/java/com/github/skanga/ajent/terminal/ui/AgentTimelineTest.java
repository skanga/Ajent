package com.github.skanga.ajent.terminal.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class AgentTimelineTest {
  @Test
  void rendersStableCompletedActionPanelWithStatsBodyAndFooter() {
    ToolUse read = tool("read", Map.of("path", "src/Main.java"),
        new ToolStatus.Done(1_000_000_000, 1_250_000_000, "alpha\nbeta"));

    List<AgentTimeline.Row> rows = AgentTimeline.render(
        new AgentTimeline.Config(List.of(read), 40, 30, 2_000_000_000));

    assertThat(text(rows)).containsExactly(
        "╭ A C T I O N S ───────────────────────╮",
        "│ I N S P E C T 1                      │",
        "│                                      │",
        "│ ── ✓  Read  src/Main.java  ·  2 lin… │",
        "│    │     1 │ alpha                   │",
        "│    │     2 │ beta                    │",
        "│                                      │",
        "│    ✓ D O N E   1/1 action   250ms    │",
        "╰──────────────────────────────────────╯");
    assertThat(rows.get(3).spans()).anySatisfy(span -> {
      assertThat(span.text()).isEqualTo("✓");
      assertThat(span.tone()).isEqualTo(AgentTimeline.Tone.SUCCESS);
      assertThat(span.bold()).isTrue();
    }).anySatisfy(span -> {
      assertThat(span.text()).isEqualTo("Read");
      assertThat(span.tone()).isEqualTo(AgentTimeline.Tone.INSPECT);
      assertThat(span.dim()).isTrue();
    }).anySatisfy(span -> {
      assertThat(span.text()).startsWith("src/Main.java  ·  2 lin");
      assertThat(span.italic()).isTrue();
    });
    assertThat(rows.get(7).spans()).anySatisfy(span -> {
      assertThat(span.text()).isEqualTo("1/1 action   250ms");
      assertThat(span.tone()).isEqualTo(AgentTimeline.Tone.WHITE);
    });
  }

  @Test
  void remainsRenderSafeAtEveryPositiveTerminalWidth() {
    ToolUse read = tool("read", Map.of("path", "src/Main.java"),
        new ToolStatus.Pending(1_000_000_000));

    for (int width = 1; width <= 4; width++) {
      int currentWidth = width;
      List<AgentTimeline.Row> rows = AgentTimeline.render(
          new AgentTimeline.Config(List.of(read), currentWidth, 1, 2_000_000_000));

      assertThat(rows).isNotEmpty();
      assertThat(rows).allSatisfy(row ->
          assertThat(com.github.skanga.ajent.terminal.render.UnicodeWidth.stringWidth(
              row.text(),
              com.github.skanga.ajent.terminal.render.UnicodeWidth.Mode.MODERN))
              .isLessThanOrEqualTo(currentWidth));
    }
  }

  @Test
  void rendersMultiEventTreeAndFailureSummary() {
    ToolUse write = tool("write", Map.of("path", "out.txt", "content", "hello"),
        new ToolStatus.Done(1_000_000_000, 1_100_000_000, "wrote out.txt"));
    ToolUse bash = tool("bash", Map.of("command", "mvn test"),
        new ToolStatus.Failed(1_100_000_000, 1_500_000_000, "failed with exit code 1"));

    List<String> rows = text(AgentTimeline.render(
        new AgentTimeline.Config(List.of(write, bash), 48, 30, 2_000_000_000)));

    assertThat(rows).contains(
        "│ M U T A T E 1  ·  E X E C U T E 1            │",
        "│ ╭─ ✓  Write  out.txt                         │",
        "│    │                                         │",
        "│ ╰─ ✗  Bash  mvn test                         │",
        "│    ✗ 1   F A I L E D   2/2 actions   500ms   │");
    assertThat(rows.getFirst()).isEqualTo(
        "╭ A C T I O N S " + "─".repeat(31) + "╮");
    assertThat(rows.getLast()).isEqualTo("╰" + "─".repeat(46) + "╯");
  }

  @Test
  void livePanelKeepsStableHeaderAndUsesStaticEventGlyphWithAnimatedFooter() {
    ToolUse running = tool("bash", Map.of("command", "mvn test"),
        new ToolStatus.Running(1_000_000_000, "building"));

    List<String> first = text(AgentTimeline.render(
        new AgentTimeline.Config(List.of(running), 40, 20, 1_160_000_000)));
    List<String> later = text(AgentTimeline.render(
        new AgentTimeline.Config(List.of(running), 40, 20, 1_240_000_000)));

    assertThat(first.getFirst()).isEqualTo(later.getFirst())
        .isEqualTo("╭ A C T I O N S ───────────────────────╮");
    assertThat(first).anySatisfy(row -> assertThat(row).contains("── ●  Bash  mvn test"));
    assertThat(first).anySatisfy(
        row -> assertThat(row).contains("R U N N I N G   0/1 action"));
    assertThat(first).isNotEqualTo(later);
  }

  @Test
  void rendersEveryNativeToolSpecificDetailSummary() {
    List<ToolUse> calls = List.of(
        done("bash", Map.of("command", "mvn test"), "failed with exit code 2"),
        done("grep", Map.of("pattern", "TODO", "path", "src"),
            "Found 3 matches across 2 files"),
        done("glob", Map.of("pattern", "**/*.java"), "Found 0 files:"),
        done("list_dir", Map.of("path", "src"), "a\nb\n"),
        done("find_definition", Map.of("symbol", "Main"),
            "## Matches in A.java\n## Matches in B.java"),
        done("web_fetch", Map.of("url", "https://example.test"), "HTTP 200 OK\nbody"),
        done("web_search", Map.of("query", "java agents"), "1. One\n2. Two\n"),
        done("git_commit", Map.of("message", "ship it\nbody"), "[main abc123] ship it"),
        done("git_status", Map.of(), "## main...origin/main\n M a\nM  b\n?? c\n"),
        done("remember", Map.of("scope", "project", "text", "keep this"),
            "Remembered (id=abc123)"),
        done("forget", Map.of("substring", "old fact"), "Forgot 2 memories"),
        done("task", Map.of("agent_type", "explorer", "display_description", "map code"),
            "Subagent report (explorer, 3 turns):\ndone"));

    List<String> rows = text(AgentTimeline.render(
        new AgentTimeline.Config(calls, 200, 50, 2_000_000_000)));

    assertThat(rows).anySatisfy(row -> assertThat(row).contains("mvn test  ·  exit 2"));
    assertThat(rows).anySatisfy(row -> assertThat(row).contains("TODO  in  src  ·  3 matches"));
    assertThat(rows).anySatisfy(row -> assertThat(row).contains("**/*.java  ·  no hits"));
    assertThat(rows).anySatisfy(row -> assertThat(row).contains("src  ·  2 entries"));
    assertThat(rows).anySatisfy(row -> assertThat(row).contains("Main  ·  2 files"));
    assertThat(rows).anySatisfy(
        row -> assertThat(row).contains("https://example.test  ·  200"));
    assertThat(rows).anySatisfy(row -> assertThat(row).contains("java agents  ·  2 results"));
    assertThat(rows).anySatisfy(row -> assertThat(row).contains("ship it  ·  abc123"));
    assertThat(rows).anySatisfy(row -> assertThat(row).contains("main  ·  1M 1S 1?"));
    assertThat(rows).anySatisfy(row -> assertThat(row).contains("[project] keep this  ·  abc123"));
    assertThat(rows).anySatisfy(row -> assertThat(row).contains("“old fact”  ·  2 removed"));
    assertThat(rows).anySatisfy(
        row -> assertThat(row).contains("explorer  ·  map code  ·  3 turns"));
  }

  @Test
  void shortensWorkspacePathsAndSafelyTruncatesFallbackTaskPrompts() {
    String workspacePath = java.nio.file.Path.of("").toAbsolutePath() + "/src/Main.java";
    String prompt = "é".repeat(31);
    List<String> rows = text(AgentTimeline.render(new AgentTimeline.Config(List.of(
        done("read", Map.of("path", workspacePath), "one line"),
        done("task", Map.of("agent_type", "explorer", "prompt", prompt), "done")),
        160, 30, 2_000_000_000)));

    assertThat(rows).anySatisfy(row -> assertThat(row).contains("Read  src/Main.java"));
    assertThat(rows).anySatisfy(row -> assertThat(row)
        .contains("Agent  explorer  ·  " + "é".repeat(28) + "…"));
  }

  private static ToolUse tool(String name, Map<String, Object> arguments, ToolStatus status) {
    return new ToolUse(new ToolCallId(name), new ToolName(name), arguments, status);
  }

  private static ToolUse done(String name, Map<String, Object> arguments, String output) {
    return tool(name, arguments, new ToolStatus.Done(1_000_000_000, 1_100_000_000, output));
  }

  private static List<String> text(List<AgentTimeline.Row> rows) {
    return rows.stream().map(AgentTimeline.Row::text).toList();
  }
}
