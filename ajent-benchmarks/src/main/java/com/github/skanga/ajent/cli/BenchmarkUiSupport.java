package com.github.skanga.ajent.cli;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Production-UI fixtures shared by source-derived JMH probes. */
final class BenchmarkUiSupport {
  private BenchmarkUiSupport() {}

  static Fixture fixture(List<Message> messages, int width, int height, boolean active) {
    return fixture(messages, width, height, active, false);
  }

  static Fixture fixture(
      List<Message> messages, int width, int height, boolean active, boolean captureWire) {
    var thread = new com.github.skanga.ajent.domain.Thread(
        new ThreadId("benchmark"), "benchmark", messages);
    AgentState initial = AgentState.initial(thread);
    if (active) {
      initial = withPhase(initial, new SessionPhase.ExecutingTool(
          ActiveTurn.start(new CancellationSignal(), 1)));
    }
    var state = new AtomicReference<>(initial);
    var clock = new Clock();
    var terminal = new Terminal(width, height, captureWire);
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate(), clock);
    return new Fixture(state, ui, clock, terminal);
  }

  static Message user(String text) {
    return new Message(Role.USER, text, List.of(), List.of());
  }

  static Message assistant(String text, List<ToolUse> tools) {
    return new Message(Role.ASSISTANT, text, List.of(), tools);
  }

  static ToolUse write(String id, int lines, ToolStatus status) {
    return new ToolUse(new ToolCallId(id), new ToolName("write"), Map.of(
        "file_path", "src/foo.cpp", "content", code(lines)), status);
  }

  static ToolUse edit(String id, int index, int lines, ToolStatus status) {
    var edits = new ArrayList<Map<String, String>>();
    var oldText = new StringBuilder();
    var newText = new StringBuilder();
    for (int line = 0; line < lines; line++) {
      oldText.append("    const auto old_").append(line).append(" = compute(line);\n");
      newText.append("    const auto new_").append(line).append(" = compute(line) + 1;\n");
    }
    for (int hunk = 0; hunk < 2; hunk++) {
      edits.add(Map.of("old_text", oldText.toString(), "new_text", newText.toString()));
    }
    return new ToolUse(new ToolCallId(id), new ToolName("edit"), Map.of(
        "file_path", "src/module_" + index + ".cpp", "edits", edits), status);
  }

  static ToolUse bashRunning() {
    return new ToolUse(new ToolCallId("bash-live"), new ToolName("bash"),
        Map.of("command", "cmake --build build -j10"), new ToolStatus.Running(""));
  }

  static String paragraph(int index) {
    return "\n\nParagraph " + index
        + ": the refactor threads the new provider through the login flow, "
        + "updating every caller and surfacing init errors as a Result so "
        + "the CLI can report them instead of crashing at startup.";
  }

  static Message withText(Message message, String text) {
    return new Message(message.id(), message.role(), text, message.images(), message.attachments(),
        message.thinking(), message.thinkingSignature(), message.toolCalls(), message.timestamp(),
        message.checkpointId(), message.error(), message.textBlockClosed(),
        message.isCompactSummary());
  }

  static Message withTools(Message message, List<ToolUse> tools) {
    return message.withToolCalls(tools);
  }

  static void replaceLast(Fixture fixture, Message replacement) {
    AgentState state = fixture.state().get();
    var messages = new ArrayList<>(state.thread().messages());
    messages.set(messages.size() - 1, replacement);
    var thread = new com.github.skanga.ajent.domain.Thread(state.thread().id(),
        state.thread().title(), messages, state.thread().createdAt(), state.thread().updatedAt(),
        state.thread().compactions());
    fixture.state().set(new AgentState(thread, state.phase(), state.activeTurnId(),
        state.turnCounter(), state.tokensIn(), state.tokensOut(), state.lastTickNanos(),
        state.status(), state.toolDraft(), state.queued(), state.compaction(),
        state.oauthRefreshInFlight(), state.truncatedToolIds(), state.sessionGrants()));
  }

  static String code(int lines) {
    var result = new StringBuilder(lines * 64);
    for (int line = 0; line < lines; line++) {
      result.append("    auto x = compute(i) + offset; // line of plausible code\n");
    }
    return result.toString();
  }

  private static AgentState withPhase(AgentState state, SessionPhase phase) {
    return new AgentState(state.thread(), phase, state.activeTurnId(), state.turnCounter(),
        state.tokensIn(), state.tokensOut(), state.lastTickNanos(), state.status(),
        state.toolDraft(), state.queued(), state.compaction(), state.oauthRefreshInFlight(),
        state.truncatedToolIds(), state.sessionGrants());
  }

  record Fixture(AtomicReference<AgentState> state, InteractiveCommand.Ui ui,
                 Clock clock, Terminal terminal) {
    void render() {
      clock.now += 16_000_000;
      ui.render();
    }

    void renderAfter(long nanos) {
      clock.now += nanos;
      ui.render();
    }
  }

  static final class Clock implements InteractiveCommand.AnimationPort {
    private long now;
    @Override public long nowNanos() { return now; }
    @Override public void request(Runnable frame) { }
  }

  static final class Terminal implements InteractiveCommand.TerminalPort {
    private final JLineTerminalSession.Size size;
    private final boolean capture;
    private long bytes;
    private final StringBuilder pending = new StringBuilder();
    Terminal(int width, int height) { this(width, height, false); }
    Terminal(int width, int height, boolean capture) {
      size = new JLineTerminalSession.Size(width, height);
      this.capture = capture;
    }
    @Override public JLineTerminalSession.Size size() { return size; }
    @Override public void write(String value) {
      bytes += value.length();
      if (capture) pending.append(value);
    }
    long bytes() { return bytes; }
    String drain() {
      String result = pending.toString();
      pending.setLength(0);
      return result;
    }
  }
}
