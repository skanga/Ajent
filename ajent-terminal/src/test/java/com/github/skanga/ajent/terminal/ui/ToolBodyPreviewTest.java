package com.github.skanga.ajent.terminal.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ToolBodyPreviewTest {
  @Test void editUsesSettledDiffFenceStreamingArgumentHunksAndFailureText() {
    var settled = ToolBodyPreview.describe(call("edit", Map.of(), new ToolStatus.Done(
        "Applied\n```diff\n@@ -1 +1 @@\n-old\n+new\n```\nDone")), 24, Map.of());
    assertThat(settled.kind()).isEqualTo(ToolBodyPreview.Kind.GIT_DIFF);
    assertThat(settled.text()).isEqualTo("@@ -1 +1 @@\n-old\n+new");
    assertThat(settled.showAll()).isTrue();

    var streaming = ToolBodyPreview.describe(call("edit", Map.of("edits", List.of(
        Map.of("old_string", "old", "new_string", "new"),
        Map.of("old_text", "before", "new_text", "after"))),
        new ToolStatus.Pending()), 18, Map.of());
    assertThat(streaming.kind()).isEqualTo(ToolBodyPreview.Kind.EDIT_DIFF);
    assertThat(streaming.hunks()).containsExactly(
        new ToolBodyPreview.EditHunk("old", "new"),
        new ToolBodyPreview.EditHunk("before", "after"));
    assertThat(streaming.streaming()).isTrue();
    assertThat(streaming.editTailPerSide()).isEqualTo(1);
    assertThat(streaming.streamHunkNumber()).isEqualTo(2);

    var failed = ToolBodyPreview.describe(call("edit", Map.of("old_text", "x"),
        new ToolStatus.Failed("old_text not found")), 24, Map.of());
    assertThat(failed.kind()).isEqualTo(ToolBodyPreview.Kind.FAILURE);
    assertThat(failed.text()).isEqualTo("old_text not found");
  }

  @Test void bashUsesLiveProgressAndStripsSettledOutputScaffolding() {
    var running = ToolBodyPreview.describe(call("bash", Map.of(),
        new ToolStatus.Running("compile\ntest")), 24, Map.of());
    assertThat(running.kind()).isEqualTo(ToolBodyPreview.Kind.BASH_OUTPUT);
    assertThat(running.text()).isEqualTo("compile\ntest");
    assertThat(running.streaming()).isTrue();

    var done = ToolBodyPreview.describe(call("diagnostics", Map.of(), new ToolStatus.Done(
        "Command mvn\n\n```text\none\ntwo\n```\n\n[elapsed: 1.2s]")), 24, Map.of());
    assertThat(done.kind()).isEqualTo(ToolBodyPreview.Kind.BASH_OUTPUT);
    assertThat(done.text()).isEqualTo("Command mvn\n\none\ntwo");
  }

  @Test void writeStreamsATerminalSizedTailAndSettlesToTheFullBodyWithStats() {
    String content = "one\ntwo\nthree\nfour\nfive\nsix";
    var running = ToolBodyPreview.describe(call("write", Map.of("content", content),
        new ToolStatus.Pending()), 18, Map.of());
    assertThat(running.kind()).isEqualTo(ToolBodyPreview.Kind.FILE_WRITE);
    assertThat(running.streaming()).isTrue();
    assertThat(running.showAll()).isFalse();
    assertThat(running.codeTail()).isEqualTo(3);
    assertThat(running.footerStats()).isFalse();

    var done = ToolBodyPreview.describe(call("write", Map.of("content", content),
        new ToolStatus.Done("wrote file")), 24, Map.of());
    assertThat(done.text()).isEqualTo(content);
    assertThat(done.showAll()).isTrue();
    assertThat(done.footerStats()).isTrue();
  }

  @Test void grepHitsAnchorSubsequentReadLinesAndAliasesResolvePaths() {
    ToolUse grep = call("grep", Map.of(), new ToolStatus.Done(
        "## Matches in src/A.java\n### L42-45\nbody\n### L61-61\n"
            + "## Matches in src/B.java\n### L7-8"));
    Map<String, Set<Integer>> hits = ToolBodyPreview.collectGrepHits(List.of(
        call("grep", Map.of(), new ToolStatus.Pending()), grep));
    assertThat(hits).containsEntry("src/A.java", Set.of(42, 61))
        .containsEntry("src/B.java", Set.of(7));

    var read = ToolBodyPreview.describe(call("read", Map.of(
        "filename", "src/A.java", "start_line", 40), new ToolStatus.Done("a\nb\nc")),
        24, hits);
    assertThat(read.kind()).isEqualTo(ToolBodyPreview.Kind.FILE_READ);
    assertThat(read.startLine()).isEqualTo(40);
    assertThat(read.highlightLines()).containsExactly(42, 61);
  }

  @Test void settledStructuredGenericTaskFailureAndTodoToolsChooseNativeKinds() {
    assertThat(preview("git_diff", Map.of(), new ToolStatus.Done("no changes")).kind())
        .isEqualTo(ToolBodyPreview.Kind.NONE);
    assertThat(preview("git_diff", Map.of(), new ToolStatus.Done("@@ x\n-old\n+new")).kind())
        .isEqualTo(ToolBodyPreview.Kind.GIT_DIFF);
    assertThat(preview("web_fetch", Map.of(), new ToolStatus.Done("{\"ok\":true}")).kind())
        .isEqualTo(ToolBodyPreview.Kind.JSON);
    assertThat(preview("grep", Map.of(), new ToolStatus.Done("matches")).kind())
        .isEqualTo(ToolBodyPreview.Kind.CODE_BLOCK);

    var task = preview("task", Map.of(), new ToolStatus.Done(
        "Subagent report (explore, 2 turns):\n\nfirst\nsecond\nthird\nfourth\nfifth"));
    assertThat(task.kind()).isEqualTo(ToolBodyPreview.Kind.CODE_BLOCK);
    assertThat(task.text()).isEqualTo("first\nsecond\nthird\nfourth\n⋯ 1 more line");
    assertThat(task.showAll()).isTrue();

    var failure = preview("read", Map.of(), new ToolStatus.Failed("not found"));
    assertThat(failure.kind()).isEqualTo(ToolBodyPreview.Kind.FAILURE);

    var todos = preview("todo", Map.of("todos", List.of(
        Map.of("content", "done", "status", "completed"),
        Map.of("content", "work", "status", "in_progress"),
        Map.of("content", "later", "status", "future"), "bad")),
        new ToolStatus.Pending());
    assertThat(todos.kind()).isEqualTo(ToolBodyPreview.Kind.TODO_LIST);
    assertThat(todos.todos()).containsExactly(
        new ToolBodyPreview.TodoItem("done", ToolBodyPreview.TodoStatus.COMPLETED),
        new ToolBodyPreview.TodoItem("work", ToolBodyPreview.TodoStatus.IN_PROGRESS),
        new ToolBodyPreview.TodoItem("later", ToolBodyPreview.TodoStatus.PENDING));
  }

  @Test void rendersNativeTodoReadWriteAndBashShapesAsTypedRows() {
    var todos = preview("todo", Map.of("todos", List.of(
        Map.of("content", "done", "status", "completed"),
        Map.of("content", "work", "status", "in_progress"),
        Map.of("content", "later"))), new ToolStatus.Pending());
    assertThat(ToolBodyPreview.render(todos)).containsExactly(
        new ToolBodyPreview.Row("  ✓ │ done", ToolBodyPreview.Tone.SUCCESS),
        new ToolBodyPreview.Row("  ◍ │ work", ToolBodyPreview.Tone.ACCENT),
        new ToolBodyPreview.Row("  ○ │ later", ToolBodyPreview.Tone.MUTED));

    var read = ToolBodyPreview.describe(call("read", Map.of("path", "x", "offset", 40),
        new ToolStatus.Done("forty\nforty-one\nforty-two")), 24, Map.of("x", Set.of(42, 99)));
    assertThat(ToolBodyPreview.render(read)).containsExactly(
        new ToolBodyPreview.Row("▸ matches: 42, 99", ToolBodyPreview.Tone.ACCENT),
        new ToolBodyPreview.Row("  40 │ forty", ToolBodyPreview.Tone.NORMAL),
        new ToolBodyPreview.Row("  41 │ forty-one", ToolBodyPreview.Tone.NORMAL),
        new ToolBodyPreview.Row("▸ 42 │ forty-two", ToolBodyPreview.Tone.ACCENT));

    var write = preview("write", Map.of("content", "hé\nbye"), new ToolStatus.Done("ok"));
    assertThat(ToolBodyPreview.render(write)).containsExactly(
        new ToolBodyPreview.Row("  1 hé", ToolBodyPreview.Tone.SUCCESS),
        new ToolBodyPreview.Row("  2 bye", ToolBodyPreview.Tone.SUCCESS),
        new ToolBodyPreview.Row("    2 lines · 7 B", ToolBodyPreview.Tone.MUTED));

    var tests = preview("bash", Map.of(),
        new ToolStatus.Done("[==========] 4 tests passed."));
    assertThat(ToolBodyPreview.render(tests)).containsExactly(
        new ToolBodyPreview.Row("✓ 4/4 tests passed", ToolBodyPreview.Tone.SUCCESS));
  }

  @Test void rendersDiffsAndBoundedGenericTailWithoutPlumbingNoise() {
    var diff = preview("git_diff", Map.of(), new ToolStatus.Done(
        "diff --git a/x b/x\nindex 1..2\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new\n same"));
    assertThat(ToolBodyPreview.render(diff)).containsExactly(
        new ToolBodyPreview.Row("~ @@ -1 +1 @@", ToolBodyPreview.Tone.ACCENT),
        new ToolBodyPreview.Row(" - old", ToolBodyPreview.Tone.DANGER),
        new ToolBodyPreview.Row(" + new", ToolBodyPreview.Tone.SUCCESS),
        new ToolBodyPreview.Row("   same", ToolBodyPreview.Tone.NORMAL));

    var generic = preview("glob", Map.of(), new ToolStatus.Done("one\ntwo\nthree\nfour\nfive"));
    assertThat(ToolBodyPreview.render(generic)).extracting(ToolBodyPreview.Row::text)
        .containsExactly("  1 │ two", "  2 │ three", "  3 │ four", "  4 │ five");

    var edit = preview("edit", Map.of("old_text", "a\nb", "new_text", "c"),
        new ToolStatus.Done("no fenced diff"));
    assertThat(ToolBodyPreview.render(edit)).containsExactly(
        new ToolBodyPreview.Row("   −2 / +1", ToolBodyPreview.Tone.ACCENT),
        new ToolBodyPreview.Row(" - a", ToolBodyPreview.Tone.DANGER),
        new ToolBodyPreview.Row(" - b", ToolBodyPreview.Tone.DANGER),
        new ToolBodyPreview.Row(" + c", ToolBodyPreview.Tone.SUCCESS));
  }

  @Test void rendersFailureJsonFailedTestsStreamingWritesAndTodoOverflow() {
    assertThat(ToolBodyPreview.render(preview("read", Map.of(),
        new ToolStatus.Failed("missing")))).containsExactly(
            new ToolBodyPreview.Row("  1 │ missing", ToolBodyPreview.Tone.DANGER));
    assertThat(ToolBodyPreview.render(preview("web_fetch", Map.of(),
        new ToolStatus.Done("{\"value\":1}")))).extracting(ToolBodyPreview.Row::text)
            .containsExactly("{", "  \"value\": 1", "}");
    assertThat(ToolBodyPreview.render(preview("bash", Map.of(),
        new ToolStatus.Failed("2 tests passed, 1 tests failed")))).containsExactly(
            new ToolBodyPreview.Row("✗ 1/3 tests failed", ToolBodyPreview.Tone.DANGER),
            new ToolBodyPreview.Row("    ⋯ 1 more failing", ToolBodyPreview.Tone.MUTED));
    assertThat(ToolBodyPreview.render(preview("bash", Map.of(),
        new ToolStatus.Done("a\nb\nc\nd\ne")))).extracting(ToolBodyPreview.Row::text)
            .containsExactly("  > │ b", "  > │ c", "  > │ d", "  > │ e");

    var streamingWrite = ToolBodyPreview.describe(call("write", Map.of(
        "content", "one\ntwo\nthree\nfour\nfive"), new ToolStatus.Pending()), 18, Map.of());
    assertThat(ToolBodyPreview.render(streamingWrite)).extracting(ToolBodyPreview.Row::text)
        .containsExactly("  3 three", "  4 four", "  5 five");

    var values = new java.util.ArrayList<Map<String, Object>>();
    for (int index = 0; index < 9; index++) values.add(Map.of("content", "item " + index));
    assertThat(ToolBodyPreview.render(preview("todo", Map.of("todos", values),
        new ToolStatus.Pending())).getLast()).isEqualTo(
            new ToolBodyPreview.Row("⋯ 1 more", ToolBodyPreview.Tone.MUTED));
  }

  @Test void streamingAndElidedMultiEditRowsStayBoundedAcrossHunks() {
    var streaming = ToolBodyPreview.describe(call("edit", Map.of("edits", List.of(
        Map.of("old_text", "old one", "new_text", "new one"),
        Map.of("old_text", "old two", "new_text", "new two"))),
        new ToolStatus.Pending()), 18, Map.of());
    assertThat(ToolBodyPreview.render(streaming)).containsExactly(
        new ToolBodyPreview.Row("   edit 2  ·  −1 / +1", ToolBodyPreview.Tone.ACCENT),
        new ToolBodyPreview.Row(" - old two", ToolBodyPreview.Tone.DANGER),
        new ToolBodyPreview.Row(" + new two", ToolBodyPreview.Tone.SUCCESS));

    List<ToolBodyPreview.EditHunk> hunks = java.util.stream.IntStream.range(0, 5)
        .mapToObj(index -> new ToolBodyPreview.EditHunk("old " + index, "new " + index)).toList();
    var bounded = configured(ToolBodyPreview.Kind.EDIT_DIFF, "", hunks, List.of(),
        false, false, 1, Set.of(), 3, true, 2, 0);
    assertThat(ToolBodyPreview.render(bounded).getLast()).isEqualTo(
        new ToolBodyPreview.Row("⋯ 1 more edits", ToolBodyPreview.Tone.MUTED));
    assertThat(ToolBodyPreview.render(bounded).getFirst().text()).startsWith("   edit 1/5");
  }

  @Test void handlesEmptyPendingAliasAndTaskPreviewBoundaries() {
    assertThat(preview("edit", Map.of(), new ToolStatus.Pending()).kind())
        .isEqualTo(ToolBodyPreview.Kind.NONE);
    assertThat(preview("bash", Map.of(), new ToolStatus.Pending()).kind())
        .isEqualTo(ToolBodyPreview.Kind.NONE);
    assertThat(preview("bash", Map.of(), new ToolStatus.Done("")).kind())
        .isEqualTo(ToolBodyPreview.Kind.NONE);
    assertThat(preview("write", Map.of(), new ToolStatus.Pending()).kind())
        .isEqualTo(ToolBodyPreview.Kind.FILE_WRITE);
    assertThat(preview("write", Map.of(), new ToolStatus.Done("")).kind())
        .isEqualTo(ToolBodyPreview.Kind.NONE);
    assertThat(preview("write", Map.of(), new ToolStatus.Failed("denied")).kind())
        .isEqualTo(ToolBodyPreview.Kind.FAILURE);
    assertThat(preview("read", Map.of(), new ToolStatus.Done("")).kind())
        .isEqualTo(ToolBodyPreview.Kind.NONE);
    assertThat(preview("web_fetch", Map.of(), new ToolStatus.Done("")).kind())
        .isEqualTo(ToolBodyPreview.Kind.NONE);
    assertThat(preview("glob", Map.of(), new ToolStatus.Done("")).kind())
        .isEqualTo(ToolBodyPreview.Kind.NONE);
    assertThat(preview("task", Map.of(), new ToolStatus.Pending()).kind())
        .isEqualTo(ToolBodyPreview.Kind.NONE);

    String activity = java.util.stream.IntStream.rangeClosed(1, 10)
        .mapToObj(String::valueOf).collect(java.util.stream.Collectors.joining("\n"));
    var task = preview("task", Map.of(), new ToolStatus.Running(activity));
    assertThat(task.text()).isEqualTo("3\n4\n5\n6\n7\n8\n9\n10");
    assertThat(preview("read", Map.of("offset", 0, "filepath", "x"),
        new ToolStatus.Done("body")).startLine()).isEqualTo(1);
  }

  @Test void fullGitDiffHandlesFilePairsDeletionAndAllPlumbingFamilies() {
    String diff = "diff --git a/x b/x\nindex 1..2\nnew file mode 100644\n"
        + "deleted file mode 100644\nold mode 1\nnew mode 2\nsimilarity index 90%\n"
        + "rename from x\ncopy from y\n--- a/x\n+++ /dev/null\n-context";
    var full = configured(ToolBodyPreview.Kind.GIT_DIFF, diff, List.of(), List.of(),
        false, true, 1, Set.of(), 3, true, 2, 0);
    assertThat(ToolBodyPreview.render(full)).containsExactly(
        new ToolBodyPreview.Row("~ x", ToolBodyPreview.Tone.ACCENT),
        new ToolBodyPreview.Row(" - context", ToolBodyPreview.Tone.DANGER));
  }

  @Test void validatesBoundsAndFormatsLargeWriteStats() {
    assertThatIllegalArgumentException().isThrownBy(() -> configured(
        ToolBodyPreview.Kind.NONE, "", List.of(), List.of(), false, false,
        0, Set.of(), 3, true, 2, 0));
    String kilobytes = "x".repeat(1024);
    assertThat(ToolBodyPreview.render(preview("write", Map.of("content", kilobytes),
        new ToolStatus.Done("ok"))).getLast().text()).endsWith("1.0 KB");
    String megabytes = "x".repeat(1024 * 1024);
    assertThat(ToolBodyPreview.render(preview("write", Map.of("content", megabytes),
        new ToolStatus.Done("ok"))).getLast().text()).endsWith("1.00 MB");
    assertThat(ToolBodyPreview.stripBashOutputFence("```text\nbody")).isEqualTo("body");
    assertThat(ToolBodyPreview.stripBashOutputFence("header```" )).isEmpty();
  }

  @Test void rendersFailingTestNamesCompilerDiagnosticsAndPrettyJson() {
    var failures = preview("bash", Map.of(), new ToolStatus.Failed(
        "[  FAILED  ] Suite.first\n[  FAILED  ] Suite.second\n"
            + "3 tests failed, 2 tests passed"));
    assertThat(ToolBodyPreview.render(failures)).containsExactly(
        new ToolBodyPreview.Row("✗ 3/5 tests failed", ToolBodyPreview.Tone.DANGER),
        new ToolBodyPreview.Row("    Suite.first", ToolBodyPreview.Tone.DANGER),
        new ToolBodyPreview.Row("    Suite.second", ToolBodyPreview.Tone.DANGER),
        new ToolBodyPreview.Row("    ⋯ 1 more failing", ToolBodyPreview.Tone.MUTED));

    var diagnostics = preview("diagnostics", Map.of(), new ToolStatus.Failed(
        "src/A.java:42:7: error: missing symbol\n"
            + "src/A.java:43: warning: unchecked conversion"));
    assertThat(ToolBodyPreview.render(diagnostics)).containsExactly(
        new ToolBodyPreview.Row("✗ 1 issue in src/A.java", ToolBodyPreview.Tone.DANGER),
        new ToolBodyPreview.Row("    42:7  missing symbol", ToolBodyPreview.Tone.DANGER),
        new ToolBodyPreview.Row("    43  unchecked conversion", ToolBodyPreview.Tone.MUTED));

    var json = preview("web_fetch", Map.of(), new ToolStatus.Done(
        "{\"ok\":true,\"items\":[1,{\"name\":\"Ajent\"}]}"));
    assertThat(ToolBodyPreview.render(json)).extracting(ToolBodyPreview.Row::text)
        .containsExactly("      \"name\": \"Ajent\"", "    }", "  ]", "}");
    var nonJson = preview("web_fetch", Map.of(), new ToolStatus.Done("plain response"));
    assertThat(ToolBodyPreview.render(nonJson)).extracting(ToolBodyPreview.Row::text)
        .containsExactly("  1 │ plain response");
  }

  private static ToolBodyPreview.Preview configured(
      ToolBodyPreview.Kind kind, String text, List<ToolBodyPreview.EditHunk> hunks,
      List<ToolBodyPreview.TodoItem> todos, boolean streaming, boolean showAll,
      int startLine, Set<Integer> highlights, int codeTail, boolean footerStats,
      int editTail, int streamHunk) {
    return new ToolBodyPreview.Preview(kind, text, hunks, todos, streaming, showAll, true,
        false, startLine, highlights, codeTail, footerStats, editTail, streamHunk);
  }

  private static ToolBodyPreview.Preview preview(
      String name, Map<String, Object> arguments, ToolStatus status) {
    return ToolBodyPreview.describe(call(name, arguments, status), 24, Map.of());
  }

  private static ToolUse call(String name, Map<String, Object> arguments, ToolStatus status) {
    return new ToolUse(new ToolCallId(name + "-id"), new ToolName(name), arguments, status);
  }
}
