package com.github.skanga.ajent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.ActiveTurn;
import com.github.skanga.ajent.domain.CancellationSignal;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Production reveal-on wire translation of AgenTTY's reveal_scrollback_test.cpp. */
final class RevealScrollbackTest {
  private static final int[][] NORMAL = {{80, 24}, {60, 16}, {100, 20}, {72, 12}};
  private static final int[][] TALL = {{80, 40}, {100, 50}, {120, 32}, {90, 60}};

  @Test void longProseRevealAndSettleStayAppendOnlyAcrossEveryNativeShape() {
    forEachShape(true, shape -> {
      var messages = new ArrayList<Message>();
      messages.add(message(Role.USER, "Please walk through the whole rendering pipeline."));
      Message reply = message(Role.ASSISTANT, "Let me check the diagram source:");
      messages.add(reply);
      var harness = new Harness(messages, shape[0], shape[1]);
      for (int frame = 0; frame < 6; frame++) harness.frame(active());
      for (int delta = 0; delta < 60; delta++) {
        reply = withText(reply, reply.text() + "\n\nParagraph " + delta + " (#"
            + (delta * 7 + 3) + "): builds tree-" + delta + ", lays region-"
            + (delta * 2) + ", diffs canvas-" + (delta * 3)
            + " and serializes runs batch-" + (delta * 5) + ".");
        messages.set(1, reply);
        harness.frame(active());
        harness.frame(active());
      }
      harness.settle();
      harness.assertNoDuplicates();
      assertThat(harness.ui.frozenThrough()).isEqualTo(messages.size());
    });
  }

  @Test void multiturnRevealSubmitAndCodeFoldRemainDuplicateFree() {
    forEachShape(true, RevealScrollbackTest::exerciseMultiturn);
    forEachShape(false, RevealScrollbackTest::exerciseSubmitMidReveal);
    forEachShape(true, RevealScrollbackTest::exerciseCodeFold);
  }

  @Test void priorTallWriteAndGrowingFailedToolRemainSingleCopy() {
    forEachShape(true, RevealScrollbackTest::exercisePriorWrite);
    forEachShape(true, RevealScrollbackTest::exerciseToolGrowth);
  }

  @Test void repeatedFrontTrimStormsPreservePhysicalScrollbackAcrossEveryShape() {
    forEachShape(true, shape -> {
      var messages = new ArrayList<Message>();
      var harness = new Harness(messages, shape[0], shape[1]);
      for (int turn = 0; turn < 14; turn++) {
        String opening = "Beginning turn " + turn + " investigation:";
        messages.add(message(Role.USER, "Turn " + turn + ": walk the pipeline."));
        Message reply = message(Role.ASSISTANT, opening);
        messages.add(reply);
        for (int frame = 0; frame < 3; frame++) harness.frame(active());
        int bodyLines = shape[1] + shape[1] / 4 + 6;
        for (int delta = 0; delta < bodyLines; delta++) {
          reply = withText(reply, reply.text() + "\n\nStep " + turn + '.' + delta
              + ": builds tree-" + (turn * 1000 + delta) + ", diffs canvas-"
              + (turn * 1000 + delta) + ", serializes runs batch-"
              + (turn * 1000 + delta) + ".");
          messages.set(messages.size() - 1, reply);
          harness.frame(active());
        }
        harness.settle();
        for (int frame = 0; frame < 3; frame++) harness.frame(idle());
      }
      harness.assertNoDuplicates();
      assertThat(harness.ui.renderedText().lines().count()).isGreaterThanOrEqualTo(shape[1]);
    });
  }

  private static void exerciseMultiturn(int[] shape) {
    var messages = new ArrayList<Message>();
    messages.add(message(Role.USER, "Investigate the pipeline end to end."));
    var harness = new Harness(messages, shape[0], shape[1]);
    for (int turn = 0; turn < 6; turn++) {
      String opening = "Let me check diagram source " + turn + ':';
      Message prose = message(Role.ASSISTANT, opening);
      messages.add(prose);
      for (int frame = 0; frame < 4; frame++) harness.frame(active());
      for (int delta = 0; delta < 4; delta++) {
        prose = withText(prose, prose.text() + "\n\nStep " + turn + '.' + delta
            + ": tracing call path-" + (turn * 4 + delta) + " through layout-"
            + turn + " and serializes runs batch-" + (turn * 10 + delta) + ".");
        messages.set(messages.size() - 1, prose);
        harness.frame(active());
      }
      messages.add(assistant("", read("mt" + turn, 8)));
      for (int frame = 0; frame < 3; frame++) harness.frame(active());
    }
    harness.settle();
    harness.assertNoDuplicates();
  }

  private static void exerciseSubmitMidReveal(int[] shape) {
    var messages = new ArrayList<Message>();
    var harness = new Harness(messages, shape[0], shape[1]);
    for (int turn = 0; turn < 4; turn++) {
      messages.add(message(Role.USER, "Question " + turn + ": explain a subsystem."));
      harness.frame(idle());
      String opening = "Diagram lookup " + turn + " begins now:";
      Message reply = message(Role.ASSISTANT, opening);
      messages.add(reply);
      for (int delta = 0; delta < 30; delta++) {
        reply = withText(reply, reply.text() + "\n\nPara " + turn + '-' + delta
            + ": builds tree-" + (turn * 100 + delta) + ", lays region-"
            + (turn * 100 + delta) + ", diffs canvas-" + (turn * 100 + delta)
            + ", serializes runs batch-" + (turn * 100 + delta) + ".");
        messages.set(messages.size() - 1, reply);
        harness.frame(active());
      }
      harness.frame(idle());
    }
    harness.settle();
    harness.assertNoDuplicates();
  }

  private static void exerciseCodeFold(int[] shape) {
    var messages = new ArrayList<Message>();
    messages.add(message(Role.USER, "Show the rendering pipeline diagram."));
    Message reply = message(Role.ASSISTANT, "Let me check the diagram source:\n\n```mermaid");
    messages.add(reply);
    var harness = new Harness(messages, shape[0], shape[1]);
    for (int line = 0; line < 70; line++) {
      reply = withText(reply, reply.text() + "\nnode" + line + " --> tree-" + line
          + "; diffs canvas-" + line + "; serializes runs batch-" + line + ';');
      messages.set(1, reply);
      harness.frame(active());
      harness.frame(active());
    }
    reply = withText(reply, reply.text() + "\n```\n\nThat is the full pipeline graph.");
    messages.set(1, reply);
    for (int frame = 0; frame < 6; frame++) harness.frame(active());
    assertThat(harness.ui.renderedText()).contains("▸ 70 lines hidden")
        .doesNotContain("node69 -->");
    harness.settle();
    harness.assertNoDuplicates();
  }

  private static void exercisePriorWrite(int[] shape) {
    var messages = new ArrayList<Message>();
    messages.add(message(Role.USER, "Create random.txt."));
    messages.add(assistant("Done — created random.txt.", write("prior", shape[1] + 20,
        new ToolStatus.Done("wrote file"))));
    var harness = new Harness(messages, shape[0], shape[1]);
    harness.frame(idle());
    messages.add(message(Role.USER, "now edit it"));
    Message reply = message(Role.ASSISTANT, "Sure, here are three trivia facts:");
    messages.add(reply);
    for (int delta = 0; delta < 40; delta++) {
      reply = withText(reply, reply.text() + "\n\nParagraph " + delta
          + ": builds tree-" + delta + ", diffs canvas-" + delta
          + ", serializes runs batch-" + delta + ".");
      messages.set(messages.size() - 1, reply);
      harness.frame(active());
      harness.frame(active());
    }
    harness.settle();
    harness.assertNoDuplicates();
    assertThat(harness.terminal.count("prior-line-" + (shape[1] + 19))).isOne();
  }

  private static void exerciseToolGrowth(int[] shape) {
    var messages = new ArrayList<Message>();
    messages.add(message(Role.USER, "push and run tests"));
    Message prose = message(Role.ASSISTANT, "I'll push both repos and run the suite:");
    messages.add(prose);
    var harness = new Harness(messages, shape[0], shape[1]);
    for (int delta = 0; delta < 30; delta++) {
      prose = withText(prose, prose.text() + "\n\nStep " + delta
          + ": builds tree-" + delta + ", diffs canvas-" + delta
          + ", serializes runs batch-" + delta + ".");
      messages.set(1, prose);
      harness.frame(active());
      harness.frame(active());
    }
    ToolUse running = new ToolUse(new ToolCallId("bash-grow"), new ToolName("bash"),
        Map.of("command", "git commit"), new ToolStatus.Running(""));
    messages.add(assistant("", running));
    for (int frame = 0; frame < 5; frame++) harness.frame(active());
    String failure = "fatal: pathspec did not match any files\n"
        + "git commit failed while adding paths\nsubprocess exited 128";
    messages.set(2, assistant("", new ToolUse(running.id(), running.name(), running.arguments(),
        new ToolStatus.Failed(failure))));
    for (int frame = 0; frame < 12; frame++) harness.frame(active());
    harness.settle();
    harness.assertNoDuplicates();
    assertThat(harness.terminal.count("fatal: pathspec did not match any files")).isOne();
  }

  private static void forEachShape(boolean includeTall, java.util.function.Consumer<int[]> scenario) {
    for (int[] shape : NORMAL) scenario.accept(shape);
    if (includeTall) for (int[] shape : TALL) scenario.accept(shape);
  }

  private static ToolUse read(String tag, int lines) {
    var output = new StringBuilder();
    for (int line = 0; line < lines; line++) output.append(line).append(": ")
        .append(tag).append(" source line\n");
    return new ToolUse(new ToolCallId("read-" + tag), new ToolName("read"),
        Map.of("path", "src/" + tag + ".java"), new ToolStatus.Done(output.toString()));
  }

  private static ToolUse write(String tag, int lines, ToolStatus status) {
    var content = new StringBuilder();
    for (int line = 0; line < lines; line++) content.append(tag).append("-line-")
        .append(line).append(" written body content\n");
    return new ToolUse(new ToolCallId("write-" + tag), new ToolName("write"),
        Map.of("file_path", "/tmp/" + tag + ".txt", "content", content.toString()), status);
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

  private static SessionPhase idle() { return new SessionPhase.Idle(); }

  static final class Harness {
    private final List<Message> messages;
    private final AtomicReference<AgentState> state;
    private final ManualAnimation animation = new ManualAnimation();
    private final WireTerminal terminal;
    private final InteractiveCommand.Ui ui;
    private List<String> committed = List.of();

    Harness(List<Message> messages, int columns, int rows) {
      this.messages = messages;
      state = new AtomicReference<>(state(idle()));
      terminal = new WireTerminal(columns, rows);
      ui = new InteractiveCommand.Ui(terminal, state,
          new InteractiveCommand.PermissionGate(), animation);
    }

    void frame(SessionPhase phase) {
      animation.now += 20_000_000;
      animation.frame = null;
      state.set(state(phase));
      ui.render();
      assertThat(ui.frameSynced()).as("inline shadow at %sx%s",
          terminal.size.columns(), terminal.size.rows()).isTrue();
      List<String> next = terminal.emulator.scrollback();
      if (!committed.isEmpty()) {
        assertThat(next).startsWith(committed.toArray(String[]::new));
      }
      committed = next;
      assertNoDuplicates();
    }

    void settle() {
      for (int guard = 0; guard < 500; guard++) {
        frame(idle());
        if (animation.frame == null) return;
      }
      throw new AssertionError("reveal did not settle");
    }

    void assertNoDuplicates() {
      var seen = new HashSet<String>();
      List<String> transcript = terminal.emulator.transcript();
      int scrollbackRows = terminal.emulator.scrollback().size();
      var tokens = new HashSet<String>();
      for (int rowIndex = 0; rowIndex < transcript.size(); rowIndex++) {
        String row = transcript.get(rowIndex);
        boolean oracleRow = row.contains("tracing call path-")
            || row.contains("serializes runs batch-")
            || row.contains("diffs canvas-") || row.contains("builds tree-");
        if (oracleRow) {
          assertThat(seen.add(row)).as("duplicate transcript row <%s> at %sx%s", row,
              terminal.size.columns(), terminal.size.rows()).isTrue();
        }
        int marker = row.indexOf("uniq-");
        if (marker >= 0) {
          int end = marker;
          while (end < row.length() && row.charAt(end) != ' ' && row.charAt(end) != '.') end++;
          String token = row.substring(marker, end);
          if (rowIndex < scrollbackRows) {
            assertThat(tokens.add(token)).as("committed duplicate token <%s> at %sx%s", token,
                terminal.size.columns(), terminal.size.rows()).isTrue();
          } else {
            tokens.add(token);
          }
        }
      }
    }

    private AgentState state(SessionPhase phase) {
      var thread = new com.github.skanga.ajent.domain.Thread(new ThreadId("reveal"), "",
          messages, Instant.EPOCH, Instant.EPOCH, List.of());
      AgentState initial = AgentState.initial(thread);
      return new AgentState(thread, phase, initial.activeTurnId(), initial.turnCounter(),
          initial.tokensIn(), initial.tokensOut(), initial.lastTickNanos(), initial.status(),
          initial.toolDraft(), initial.queued(), initial.compaction(),
          initial.oauthRefreshInFlight(), initial.truncatedToolIds(), initial.sessionGrants());
    }
  }

  private static final class WireTerminal implements InteractiveCommand.TerminalPort {
    private final JLineTerminalSession.Size size;
    private final MidrunWireTest.AnsiEmulator emulator;
    private WireTerminal(int columns, int rows) {
      size = new JLineTerminalSession.Size(columns, rows);
      emulator = new MidrunWireTest.AnsiEmulator(columns, rows);
    }
    @Override public JLineTerminalSession.Size size() { return size; }
    @Override public void write(String value) { emulator.feed(value); }
    private long count(String marker) {
      return emulator.transcript().stream().filter(row -> row.contains(marker)).count();
    }
  }

  private static final class ManualAnimation implements InteractiveCommand.AnimationPort {
    private long now;
    private Runnable frame;
    @Override public long nowNanos() { return now; }
    @Override public void request(Runnable next) { frame = next; }
  }
}
