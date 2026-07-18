package com.github.skanga.ajent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.ActiveTurn;
import com.github.skanga.ajent.domain.CancellationSignal;
import com.github.skanga.ajent.domain.CompactionRecord;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.SessionPhase;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.runtime.AgentState;
import com.github.skanga.ajent.terminal.JLineTerminalSession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Production-UI translation of AgenTTY's midrun_seam_test.cpp regression program. */
final class MidrunSeamTest {
  @Test void growingDeepRunKeepsEverySettledCardOnceAndFreezesOnlyAtSettle() {
    var messages = new ArrayList<Message>();
    messages.add(message(Role.USER, "please do many edits"));
    var harness = new Harness(messages, List.of(), active(), 40);
    List<String> previous = List.of();

    for (int index = 0; index < 30; index++) {
      messages.add(assistant("completed " + index, doneEdit("s" + index, 4)));
      Message placeholder = message(Role.ASSISTANT, "working");
      messages.add(placeholder);
      harness.render(active());
      List<String> current = rows(harness.ui.renderedText());

      assertThat(harness.ui.frozenThrough()).isOne();
      if (!previous.isEmpty()) {
        assertThat(firstCommittedDivergence(previous, current, 40)).isNegative();
      }
      for (int prior = 0; prior <= index; prior++) {
        assertThat(count(harness.ui.renderedText(), "src/s" + prior + ".java"))
            .as("settled card %s at depth %s", prior, index + 1).isOne();
      }
      previous = current;
      messages.removeLast();
    }

    harness.settle();

    assertThat(harness.ui.frozenThrough()).isEqualTo(messages.size());
    assertCommittedStable(previous, rows(harness.ui.renderedText()), 40);
    for (int index = 0; index < 30; index++) {
      assertThat(count(harness.ui.frozenText(), "src/s" + index + ".java")).isOne();
    }
  }

  @Test void runningEditSettlesToTheFullTallDiffBeforeTheSingleFreeze() {
    var messages = new ArrayList<Message>();
    messages.add(message(Role.USER, "edit one file"));
    ToolUse running = runningEdit("target", 120);
    messages.add(assistant("editing", running));
    messages.add(message(Role.ASSISTANT, "..."));
    var harness = new Harness(messages, List.of(), active(), 40);

    harness.render(active());
    List<String> runningRows = rows(harness.ui.renderedText());
    assertThat(harness.ui.renderedText()).contains("target-new-119")
        .doesNotContain("target-old-0");

    messages.set(1, assistant("editing", new ToolUse(running.id(), running.name(),
        running.arguments(), new ToolStatus.Done(diffOutput("target", 120)))));
    harness.render(active());
    List<String> doneRows = rows(harness.ui.renderedText());
    assertThat(firstCommittedDivergence(runningRows, doneRows, 40)).isNegative();
    assertThat(harness.ui.renderedText()).contains(
        "target-old-0", "target-new-60", "target-new-119");

    harness.settle();

    assertThat(firstCommittedDivergence(doneRows, rows(harness.ui.renderedText()), 40))
        .isNegative();
    assertThat(harness.ui.frozenThrough()).isEqualTo(messages.size());
    assertThat(harness.ui.frozenText()).contains(
        "target-old-0", "target-new-60", "target-new-119");
    assertThat(count(harness.ui.frozenText(), "target-new-119")).isOne();
  }

  @Test void runningWriteExpandsToItsWholeBodyAndFreezesWithoutElision() {
    var messages = new ArrayList<Message>();
    messages.add(message(Role.USER, "write a big file"));
    String content = numberedBody("line", 170);
    ToolUse running = new ToolUse(new ToolCallId("write-big"), new ToolName("write"),
        Map.of("file_path", "/tmp/big.txt", "content", content),
        new ToolStatus.Running(""));
    messages.add(assistant("writing", running));
    messages.add(message(Role.ASSISTANT, "..."));
    var harness = new Harness(messages, List.of(), active(), 40);

    harness.render(active());
    List<String> runningRows = rows(harness.ui.renderedText());
    assertThat(harness.ui.renderedText()).contains("line-169").doesNotContain("line-0");

    messages.set(1, assistant("writing", new ToolUse(running.id(), running.name(),
        running.arguments(), new ToolStatus.Done("Created /tmp/big.txt"))));
    harness.render(active());
    List<String> doneRows = rows(harness.ui.renderedText());
    assertThat(firstCommittedDivergence(runningRows, doneRows, 40)).isNegative();
    assertThat(harness.ui.renderedText()).contains("line-0", "line-85", "line-169");

    harness.settle();

    assertThat(firstCommittedDivergence(doneRows, rows(harness.ui.renderedText()), 40))
        .isNegative();
    assertThat(harness.ui.frozenText()).contains("line-0", "line-85", "line-169");
    assertThat(count(harness.ui.frozenText(), "line-85")).isOne();
  }

  @Test void settlingReadDoesNotHideTheFollowingEditBatch() {
    var messages = new ArrayList<Message>();
    messages.add(message(Role.USER, "inspect then edit"));
    ToolUse runningRead = new ToolUse(new ToolCallId("read-target"), new ToolName("read"),
        Map.of("path", "src/target.java"), new ToolStatus.Running(""));
    messages.add(assistant("reading", runningRead));
    messages.add(assistant("mutating", doneEdit("mutate", 4)));
    messages.add(message(Role.ASSISTANT, "..."));
    var harness = new Harness(messages, List.of(), active(), 40);

    harness.render(active());
    List<String> runningRows = rows(harness.ui.renderedText());
    assertThat(harness.ui.renderedText()).contains("src/mutate.java");

    messages.set(1, assistant("reading", new ToolUse(runningRead.id(), runningRead.name(),
        runningRead.arguments(), new ToolStatus.Done(numberedBody("read", 12)))));
    harness.render(active());
    List<String> doneRows = rows(harness.ui.renderedText());
    assertThat(firstCommittedDivergence(runningRows, doneRows, 40)).isNegative();
    assertThat(harness.ui.renderedText()).contains("read-11", "src/mutate.java");

    harness.settle();
    assertThat(firstCommittedDivergence(doneRows, rows(harness.ui.renderedText()), 40))
        .isNegative();
    assertThat(harness.ui.frozenText()).contains("read-11", "src/mutate.java");
  }

  @Test void chunkedStreamingMarkdownPreservesEveryByteUntilSettle() {
    var messages = new ArrayList<Message>();
    messages.add(message(Role.USER, "explain in detail"));
    Message live = message(Role.ASSISTANT, "");
    messages.add(live);
    var harness = new Harness(messages, List.of(), active(), 40);
    StringBuilder full = new StringBuilder();
    for (int index = 0; index < 400; index++) {
      if (index % 11 == 0) full.append("## Heading ").append(index).append("\n\n");
      if (index % 23 == 0) full.append("```java\nint value = ").append(index).append(";\n```\n\n");
      full.append("Sentence number ").append(index).append(" of a long answer.\n\n");
    }
    String source = full.toString();
    List<String> previous = List.of();
    for (int end = 1024; end < source.length() + 1024; end += 1024) {
      String prefix = source.substring(0, Math.min(end, source.length()));
      live = withText(live, prefix);
      messages.set(1, live);
      harness.render(active());
      List<String> current = rows(harness.ui.renderedText());
      assertThat(harness.ui.liveRevealContent()).isEqualTo(prefix);
      assertThat(harness.ui.frozenThrough()).isOne();
      if (!previous.isEmpty()) {
        assertThat(firstCommittedDivergence(previous, current, 40)).isNegative();
      }
      previous = current;
    }

    Seam seam = harness.settle();

    assertCommittedStable(seam.live(), seam.frozen(), 40);
    assertThat(harness.ui.frozenText()).contains("Heading 0", "Sentence number 399")
        .doesNotContain("## Heading 0");
  }

  @Test void compactionBoundaryIsSymmetricAcrossLiveAndFrozenProjection() {
    var messages = new ArrayList<Message>();
    messages.add(message(Role.USER, "old question"));
    messages.add(message(Role.ASSISTANT, "old answer"));
    messages.add(assistant("", doneEdit("post", 120)));
    messages.add(message(Role.ASSISTANT, "done"));
    var records = new ArrayList<>(List.of(
        new CompactionRecord(2, "summary of old conversation", Instant.EPOCH)));
    var harness = new Harness(messages, records, active(), 400);

    harness.render(active());
    List<String> liveRows = rows(harness.ui.renderedText());
    assertThat(count(harness.ui.renderedText(), "Conversation compacted")).isOne();
    assertThat(count(harness.ui.renderedText(), "post-new-119")).isOne();

    harness.settle();

    assertThat(firstCommittedDivergence(liveRows, rows(harness.ui.renderedText()), 40))
        .isNegative();
    assertThat(count(harness.ui.frozenText(), "Conversation compacted")).isOne();
    assertThat(count(harness.ui.frozenText(), "post-new-119")).isOne();
  }

  @Test void postCompactionSubmitFreezesThePendingDividerExactlyOnce() {
    var messages = new ArrayList<Message>();
    messages.add(message(Role.USER, "old question"));
    messages.add(message(Role.ASSISTANT, "old answer"));
    var records = new ArrayList<CompactionRecord>();
    var harness = new Harness(messages, records, idle(), 400);
    harness.settle();

    records.add(new CompactionRecord(2, "summary", Instant.EPOCH));
    harness.render(idle());
    assertThat(harness.ui.renderedText()).doesNotContain("Conversation compacted");

    messages.add(message(Role.USER, "new question after compaction"));
    messages.add(assistant("", doneEdit("post2", 120)));
    harness.render(active());
    List<String> runningRows = rows(harness.ui.renderedText());
    assertThat(count(harness.ui.renderedText(), "Conversation compacted")).isOne();

    harness.settle();
    assertCommittedStable(runningRows, rows(harness.ui.renderedText()), 40);
    assertThat(count(harness.ui.frozenText(), "Conversation compacted")).isOne();
    assertThat(count(harness.ui.frozenText(), "post2-new-119")).isOne();
  }

  @Test void thirdWriteNeverDuplicatesTheSecondSettledWrite() {
    var messages = new ArrayList<Message>();
    var harness = new Harness(messages, List.of(), idle(), 200);
    for (int turn = 1; turn <= 2; turn++) completeWriteTurn(harness, messages, turn);
    List<String> previous = rows(harness.ui.renderedText());

    messages.add(message(Role.USER, "write a file t3"));
    harness.render(active());
    List<String> current = rows(harness.ui.renderedText());
    assertThat(firstCommittedDivergence(previous, current, 40)).isNegative();
    previous = current;
    String content = numberedBody("t3-line", 60);
    ToolUse running = new ToolUse(new ToolCallId("write-t3"), new ToolName("write"),
        Map.of("file_path", "/tmp/t3.txt", "content", content), new ToolStatus.Running(""));
    messages.add(assistant("writing t3", running));
    messages.add(message(Role.ASSISTANT, "..."));
    harness.render(active());
    current = rows(harness.ui.renderedText());
    assertThat(firstCommittedDivergence(previous, current, 40)).isNegative();
    previous = current;
    messages.set(messages.size() - 2, assistant("writing t3", new ToolUse(
        running.id(), running.name(), running.arguments(), new ToolStatus.Done("Created"))));
    harness.render(active());
    current = rows(harness.ui.renderedText());
    assertThat(firstCommittedDivergence(previous, current, 40)).isNegative();
    previous = current;
    harness.settle();
    assertThat(firstCommittedDivergence(previous, rows(harness.ui.renderedText()), 40))
        .isNegative();

    assertThat(count(harness.ui.frozenText(), "t2-line-30")).isOne();
    assertThat(count(harness.ui.frozenText(), "t3-line-30")).isOne();
  }

  @Test void runningEdgeAndEveryPriorCardSurviveAUserFollowup() {
    var messages = new ArrayList<Message>();
    messages.add(message(Role.USER, "do a deep run"));
    for (int index = 0; index < 24; index++) {
      messages.add(assistant("done " + index, doneEdit("r" + index, 4)));
    }
    ToolUse running = runningEdit("live", 4);
    messages.add(assistant("still working", running));
    var harness = new Harness(messages, List.of(), active(), 400);

    harness.render(active());
    List<String> runningRows = rows(harness.ui.renderedText());
    assertThat(harness.ui.renderedText()).contains("live-old-3");
    for (int index = 0; index < 24; index++) {
      assertThat(count(harness.ui.renderedText(), "src/r" + index + ".java")).isOne();
    }

    messages.set(messages.size() - 1, assistant("finished", new ToolUse(
        running.id(), running.name(), running.arguments(),
        new ToolStatus.Done(diffOutput("live", 4)))));
    harness.settle();
    List<String> settledRows = rows(harness.ui.renderedText());
    assertCommittedStable(runningRows, settledRows, 40);
    messages.add(message(Role.USER, "now explain"));
    messages.add(message(Role.ASSISTANT, "Sure — I will explain"));
    harness.render(active());

    assertThat(firstCommittedDivergence(settledRows, rows(harness.ui.renderedText()), 40))
        .isNegative();
    assertThat(count(harness.ui.renderedText(), "src/live.java")).isOne();
    for (int index = 0; index < 24; index++) {
      assertThat(count(harness.ui.renderedText(), "src/r" + index + ".java")).isOne();
    }
  }

  private static void completeWriteTurn(Harness harness, List<Message> messages, int turn) {
    String tag = "t" + turn;
    messages.add(message(Role.USER, "write a file " + tag));
    harness.render(active());
    ToolUse write = new ToolUse(new ToolCallId("write-" + tag), new ToolName("write"),
        Map.of("file_path", "/tmp/" + tag + ".txt",
            "content", numberedBody(tag + "-line", 60)), new ToolStatus.Done("Created"));
    messages.add(assistant("writing " + tag, write));
    messages.add(message(Role.ASSISTANT, "done writing"));
    harness.render(active());
    harness.settle();
  }

  private static ToolUse doneEdit(String tag, int rows) {
    return new ToolUse(new ToolCallId("edit-" + tag), new ToolName("edit"),
        Map.of("path", "src/" + tag + ".java"), new ToolStatus.Done(diffOutput(tag, rows)));
  }

  private static ToolUse runningEdit(String tag, int rows) {
    StringBuilder oldText = new StringBuilder();
    StringBuilder newText = new StringBuilder();
    for (int index = 0; index < rows; index++) {
      oldText.append(tag).append("-old-").append(index).append('\n');
      newText.append(tag).append("-new-").append(index).append('\n');
    }
    return new ToolUse(new ToolCallId("edit-" + tag), new ToolName("edit"), Map.of(
        "path", "src/" + tag + ".java",
        "edits", List.of(Map.of("old_text", oldText.toString(),
            "new_text", newText.toString()))), new ToolStatus.Running(""));
  }

  private static String diffOutput(String tag, int rows) {
    var result = new StringBuilder("```diff\n--- a/src/").append(tag)
        .append(".java\n+++ b/src/").append(tag).append(".java\n@@ -1,")
        .append(rows).append(" +1,").append(rows).append(" @@\n");
    for (int index = 0; index < rows; index++) {
      result.append('-').append(tag).append("-old-").append(index).append('\n');
      result.append('+').append(tag).append("-new-").append(index).append('\n');
    }
    return result.append("```\n").toString();
  }

  private static String numberedBody(String prefix, int rows) {
    var result = new StringBuilder();
    for (int index = 0; index < rows; index++) {
      result.append(prefix).append('-').append(index).append(" plausible content\n");
    }
    return result.toString();
  }

  private static Message assistant(String text, ToolUse tool) {
    return new Message(Role.ASSISTANT, text, List.of(), List.of(tool));
  }

  private static Message message(Role role, String text) {
    return new Message(role, text, List.of(), List.of());
  }

  private static Message withText(Message message, String text) {
    return new Message(message.id(), message.role(), text, message.images(), message.attachments(),
        message.thinking(), message.thinkingSignature(), message.toolCalls(), message.timestamp(),
        message.checkpointId(), message.error(), message.textBlockClosed(), message.isCompactSummary());
  }

  private static SessionPhase active() {
    return new SessionPhase.Streaming(ActiveTurn.start(new CancellationSignal(), 1));
  }

  private static SessionPhase idle() {
    return new SessionPhase.Idle();
  }

  private static int count(String source, String needle) {
    int result = 0;
    for (int index = source.indexOf(needle); index >= 0;
        index = source.indexOf(needle, index + needle.length())) result++;
    return result;
  }

  private static List<String> rows(String source) {
    return source.lines().toList();
  }

  private static int firstCommittedDivergence(
      List<String> previous, List<String> current, int terminalRows) {
    int committedEnd = previous.size() - terminalRows;
    int common = Math.min(current.size(), committedEnd);
    for (int index = 0; index < common; index++) {
      if (!previous.get(index).equals(current.get(index))) return index;
    }
    return -1;
  }

  private static void assertCommittedStable(
      List<String> previous, List<String> current, int terminalRows) {
    int difference = firstCommittedDivergence(previous, current, terminalRows);
    String before = difference >= 0 && difference < previous.size()
        ? previous.get(difference) : "<none>";
    String after = difference >= 0 && difference < current.size()
        ? current.get(difference) : "<none>";
    assertThat(difference).as("committed row %s: <%s> -> <%s>", difference, before, after)
        .isNegative();
  }

  private static final class Harness {
    private final List<Message> messages;
    private final List<CompactionRecord> compactions;
    private final AtomicReference<AgentState> state;
    private final ManualAnimation animation = new ManualAnimation();
    private final InteractiveCommand.Ui ui;

    private Harness(List<Message> messages, List<CompactionRecord> compactions,
        SessionPhase phase, int terminalRows) {
      this.messages = messages;
      this.compactions = compactions;
      state = new AtomicReference<>(state(phase));
      ui = new InteractiveCommand.Ui(new FakeTerminal(terminalRows), state,
          new InteractiveCommand.PermissionGate(), animation);
    }

    private void render(SessionPhase phase) {
      state.set(state(phase));
      ui.render();
    }

    private Seam settle() {
      render(idle());
      String lastLive = ui.frozenThrough() < messages.size() ? ui.renderedText() : "";
      for (int index = 0; index < 100 && animation.frame != null; index++) {
        animation.now += 16_000_000;
        animation.runFrame();
        if (ui.frozenThrough() < messages.size()) lastLive = ui.renderedText();
      }
      assertThat(animation.frame).as("reveal drain").isNull();
      return new Seam(rows(lastLive), rows(ui.renderedText()));
    }

    private AgentState state(SessionPhase phase) {
      var thread = new com.github.skanga.ajent.domain.Thread(new ThreadId("seam"), "",
          messages, Instant.EPOCH, Instant.EPOCH, compactions);
      AgentState initial = AgentState.initial(thread);
      return new AgentState(thread, phase, initial.activeTurnId(), initial.turnCounter(),
          initial.tokensIn(), initial.tokensOut(), initial.lastTickNanos(), initial.status(),
          initial.toolDraft(), initial.queued(), initial.compaction(),
          initial.oauthRefreshInFlight(), initial.truncatedToolIds(), initial.sessionGrants());
    }
  }

  private record Seam(List<String> live, List<String> frozen) { }

  private static final class FakeTerminal implements InteractiveCommand.TerminalPort {
    private final JLineTerminalSession.Size size;
    private FakeTerminal(int rows) { size = new JLineTerminalSession.Size(100, rows); }
    @Override public JLineTerminalSession.Size size() { return size; }
    @Override public void write(String value) { }
  }

  private static final class ManualAnimation implements InteractiveCommand.AnimationPort {
    private long now;
    private Runnable frame;
    @Override public long nowNanos() { return now; }
    @Override public void request(Runnable next) { frame = next; }
    private void runFrame() {
      Runnable next = frame;
      frame = null;
      if (next != null) next.run();
    }
  }
}
