package com.github.skanga.ajent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.ActiveTurn;
import com.github.skanga.ajent.domain.CancellationSignal;
import com.github.skanga.ajent.domain.Effort;
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
import com.github.skanga.ajent.terminal.input.TerminalKey;
import com.github.skanga.ajent.terminal.render.UnicodeWidth;
import com.github.skanga.ajent.terminal.ui.ModelPicker;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Wire-level translation of AgenTTY's midrun_wire_test.cpp terminal oracle. */
final class MidrunWireTest {
  @Test void growingLiveRunAndLateGrepHighlightStaySyncedAndAppendOnly() {
    var messages = new ArrayList<Message>();
    messages.add(message(Role.USER, "investigate the renderer"));
    var harness = new Harness(messages, active(), 100, 30);
    List<String> committed = List.of();
    for (int card = 0; card < 6; card++) {
      messages.add(assistant("", read("c" + card, 30 + card * 17,
          "src/file" + card + ".java")));
      committed = harness.renderAndAssertAppendOnly(active(), committed);
      String marker = (29 + card * 17) + ": c" + card + " source line text here";
      assertThat(harness.ui.renderedText()).contains(marker);
      assertThat(harness.terminal.count(marker))
          .as("physical transcript: %s", harness.terminal.transcript()).isOne();
      assertThat(harness.ui.frozenThrough()).isOne();
    }

    String path = "src/highlight.java";
    messages.add(message(Role.USER, "read then grep"));
    Message batch = assistant("", read("highlight", 60, path));
    messages.add(batch);
    committed = harness.renderAndAssertAppendOnly(active(), committed);
    messages.set(messages.size() - 1, new Message(batch.id(), batch.role(), batch.text(),
        batch.images(), batch.attachments(), batch.thinking(), batch.thinkingSignature(),
        List.of(batch.toolCalls().getFirst(), grep("late", path, 42, 61, 88)),
        batch.timestamp(), batch.checkpointId(), batch.error(), batch.textBlockClosed(),
        batch.isCompactSummary()));
    harness.renderAndAssertAppendOnly(active(), committed);

    assertThat(harness.ui.renderedText()).contains("matches: 42, 61, 88");
    assertThat(harness.terminal.count("matches: 42, 61, 88")).isOne();
  }

  @Test void writeRunningDoneAndSingleFreezePreserveThePhysicalScrollback() {
    var messages = leadInWrites(6, 4);
    messages.add(message(Role.USER, "write a big file"));
    ToolUse running = write("life", 120, new ToolStatus.Running(""));
    messages.add(assistant("", running));
    messages.add(message(Role.ASSISTANT, "..."));
    var harness = new Harness(messages, active(), 100, 30);

    List<String> committed = harness.renderAndAssertAppendOnly(active(), List.of());
    messages.set(messages.size() - 2, assistant("", write("life", 120,
        new ToolStatus.Done("Created /tmp/life.md"))));
    committed = harness.renderAndAssertAppendOnly(active(), committed);
    messages.set(messages.size() - 1, message(Role.ASSISTANT, "continuing"));
    harness.settleAndAssertAppendOnly(committed);

    assertThat(harness.ui.frozenThrough()).isEqualTo(messages.size());
    assertThat(harness.terminal.count("life-line-0")).isOne();
    assertThat(harness.terminal.count("life-line-119")).isOne();
  }

  @Test void activeDoneWriteCanFreezeWithoutRecoveryOrDuplicateRows() {
    var messages = new ArrayList<Message>();
    messages.add(message(Role.USER, "write and stop"));
    messages.add(assistant("", write("idle", 120,
        new ToolStatus.Done("Created /tmp/idle.md"))));
    var harness = new Harness(messages, active(), 100, 30);

    List<String> committed = harness.renderAndAssertAppendOnly(active(), List.of());
    harness.settleAndAssertAppendOnly(committed);

    assertThat(harness.terminal.count("idle-line-0")).isOne();
    assertThat(harness.terminal.count("idle-line-119")).isOne();
  }

  @Test void longTextFinishAndPureBottomShrinkStayOnTheIncrementalWirePath() {
    var messages = new ArrayList<Message>();
    messages.add(message(Role.USER, "explain at length"));
    String prefix = paragraphs(80);
    Message reply = message(Role.ASSISTANT, prefix + tail(10));
    messages.add(reply);
    var harness = new Harness(messages, active(), 80, 24);
    harness.render(active());
    harness.drainReveal(active());
    List<String> committed = harness.terminal.scrollback();
    assertThat(committed).isNotEmpty();

    messages.set(1, withText(reply, prefix + tail(0)));
    harness.renderAndAssertAppendOnly(active(), committed);
    committed = harness.terminal.scrollback();
    harness.settleAndAssertAppendOnly(committed);

    assertThat(harness.ui.renderedText()).contains("reply-line-79");
    assertThat(harness.terminal.count("reply-line-79"))
        .as("physical transcript: %s", harness.terminal.transcript()).isOne();
  }

  @Test void outputElidedAndFullBodyTrimKeepAViewportAndNeverDuplicateBoundaries() {
    exerciseTrim(false);
    exerciseTrim(true);
  }

  @Test void modelPickerOpenCloseCyclesNeverGrowNativeScrollback() {
    var messages = new ArrayList<Message>();
    var harness = new Harness(messages, idle(), 100, 5);
    harness.render(idle());
    List<String> committed = harness.terminal.scrollback();
    InteractiveCommand.AgentControl loop = pickerLoop(harness.state);

    for (int cycle = 0; cycle < 4; cycle++) {
      harness.ui.openModelPicker(loop);
      assertThat(harness.ui.frameSynced()).isTrue();
      harness.assertScrollbackStartsWith(committed);
      assertThat(harness.terminal.count("Models")).isOne();
      harness.ui.key(new TerminalKey(TerminalKey.SpecialKey.ESCAPE), loop);
      assertThat(harness.ui.frameSynced()).isTrue();
      harness.assertScrollbackStartsWith(committed);
      assertThat(harness.terminal.count("a calm middleware")).isOne();
    }
    assertThat(harness.terminal.scrollback()).containsExactlyElementsOf(committed);
  }

  private static InteractiveCommand.AgentControl pickerLoop(
      AtomicReference<AgentState> state) {
    return (InteractiveCommand.AgentControl) Proxy.newProxyInstance(
        MidrunWireTest.class.getClassLoader(),
        new Class<?>[] {InteractiveCommand.AgentControl.class}, (proxy, method, arguments) -> {
          return switch (method.getName()) {
            case "state" -> state.get();
            case "model" -> "claude-opus-4-5";
            case "effort" -> Effort.HIGH;
            case "loadModels" -> {
              @SuppressWarnings("unchecked")
              var receiver = (java.util.function.Consumer<List<ModelPicker.Model>>) arguments[0];
              receiver.accept(List.of(
                  new ModelPicker.Model("claude-opus-4-5", "Claude Opus 4.1", false),
                  new ModelPicker.Model("claude-sonnet-4-5", "Claude Sonnet 4.5", false)));
              yield null;
            }
            case "windows", "checkpointsAvailable", "selectProvider",
                "installAnthropicKey", "installProviderKey", "switchCustomHost" -> false;
            default -> null;
          };
        });
  }

  private static void exerciseTrim(boolean fullWrites) {
    var messages = new ArrayList<Message>();
    var harness = new Harness(messages, active(), 100, 30);
    List<String> committed = List.of();
    int turns = fullWrites ? 8 : 18;
    for (int turn = 0; turn < turns; turn++) {
      messages.add(message(Role.USER, "turn " + turn));
      ToolUse tool = fullWrites
          ? write("trim" + turn, 40, new ToolStatus.Done("Created"))
          : bash("trim" + turn, 120);
      messages.add(assistant("", tool));
      committed = harness.renderAndAssertAppendOnly(idle(), committed);
    }
    messages.add(message(Role.ASSISTANT, "continuing"));
    harness.renderAndAssertAppendOnly(active(), committed);

    assertThat(harness.ui.renderedText().lines().count()).isGreaterThanOrEqualTo(30);
    String marker = fullWrites ? "trim7-line-20" : "src/trim17-119.java";
    assertThat(harness.terminal.count(marker)).isOne();
  }

  private static ArrayList<Message> leadInWrites(int turns, int rows) {
    var messages = new ArrayList<Message>();
    for (int turn = 0; turn < turns; turn++) {
      messages.add(message(Role.USER, "lead " + turn));
      messages.add(assistant("", write("lead" + turn, rows, new ToolStatus.Done("Created"))));
    }
    return messages;
  }

  private static ToolUse write(String tag, int rows, ToolStatus status) {
    var content = new StringBuilder();
    for (int row = 0; row < rows; row++) {
      content.append(tag).append("-line-").append(row).append(" plausible body text\n");
    }
    return new ToolUse(new ToolCallId("write-" + tag), new ToolName("write"),
        Map.of("file_path", "/tmp/" + tag + ".md", "content", content.toString()), status);
  }

  private static ToolUse read(String tag, int rows, String path) {
    var output = new StringBuilder();
    for (int row = 0; row < rows; row++) {
      output.append(row).append(": ").append(tag).append(" source line text here\n");
    }
    return new ToolUse(new ToolCallId("read-" + tag), new ToolName("read"), Map.of("path", path),
        new ToolStatus.Done(output.toString()));
  }

  private static ToolUse grep(String tag, String path, int... lines) {
    var output = new StringBuilder("## Matches in ").append(path).append("\n\n");
    for (int line : lines) {
      output.append("### L").append(line).append('-').append(line)
          .append("\n```\n").append(line).append(": match here\n```\n\n");
    }
    return new ToolUse(new ToolCallId("grep-" + tag), new ToolName("grep"),
        Map.of("pattern", "needle"), new ToolStatus.Done(output.toString()));
  }

  private static ToolUse bash(String tag, int rows) {
    var output = new StringBuilder();
    for (int row = 0; row < rows; row++) {
      output.append("src/").append(tag).append('-').append(row)
          .append(".java:").append(row).append(": plausible match\n");
    }
    return new ToolUse(new ToolCallId("bash-" + tag), new ToolName("bash"),
        Map.of("command", "grep -rn x src"), new ToolStatus.Done(output.toString()));
  }

  private static String paragraphs(int count) {
    var text = new StringBuilder();
    for (int index = 0; index < count; index++) {
      text.append("reply-line-").append(index).append(" with enough text\n\n");
    }
    return text.toString();
  }

  private static String tail(int repetitions) {
    var text = new StringBuilder("TAIL");
    for (int index = 0; index < repetitions; index++) text.append(" wwwwwwww wwwwwwww wwwwwwww");
    return text.toString();
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

  private static final class Harness {
    private final List<Message> messages;
    private final AtomicReference<AgentState> state;
    private final WireTerminal terminal;
    private final ManualAnimation animation = new ManualAnimation();
    private final InteractiveCommand.Ui ui;

    private Harness(List<Message> messages, SessionPhase phase, int columns, int rows) {
      this.messages = messages;
      state = new AtomicReference<>(state(phase));
      terminal = new WireTerminal(columns, rows);
      ui = new InteractiveCommand.Ui(terminal, state,
          new InteractiveCommand.PermissionGate(), animation);
    }

    private void render(SessionPhase phase) {
      state.set(state(phase));
      ui.render();
      assertThat(ui.frameSynced()).as("inline frame shadow").isTrue();
    }

    private List<String> renderAndAssertAppendOnly(SessionPhase phase, List<String> prior) {
      render(phase);
      assertScrollbackStartsWith(prior);
      return terminal.scrollback();
    }

    private void settleAndAssertAppendOnly(List<String> prior) {
      render(idle());
      assertScrollbackStartsWith(prior);
      for (int frame = 0; frame < 500 && animation.frame != null; frame++) {
        animation.now += 16_000_000;
        animation.runFrame();
        assertThat(ui.frameSynced()).as("settle frame %s", frame).isTrue();
        assertScrollbackStartsWith(prior);
      }
      assertThat(animation.frame).as("settle reveal drain").isNull();
    }

    private void drainReveal(SessionPhase phase) {
      for (int frame = 0; frame < 2_000 && animation.frame != null; frame++) {
        animation.now += 16_000_000;
        state.set(state(phase));
        animation.runFrame();
        assertThat(ui.frameSynced()).as("reveal frame %s", frame).isTrue();
      }
    }

    private void assertScrollbackStartsWith(List<String> prior) {
      assertThat(terminal.scrollback().subList(0,
          Math.min(prior.size(), terminal.scrollback().size())))
          .containsExactlyElementsOf(prior);
      assertThat(terminal.scrollback().size()).isGreaterThanOrEqualTo(prior.size());
    }

    private AgentState state(SessionPhase phase) {
      var thread = new com.github.skanga.ajent.domain.Thread(new ThreadId("wire"), "",
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
    private final AnsiEmulator emulator;

    private WireTerminal(int columns, int rows) {
      size = new JLineTerminalSession.Size(columns, rows);
      emulator = new AnsiEmulator(columns, rows);
    }

    @Override public JLineTerminalSession.Size size() { return size; }
    @Override public void write(String value) { emulator.feed(value); }
    private List<String> scrollback() { return emulator.scrollback(); }
    private List<String> transcript() { return emulator.transcript(); }
    private long count(String marker) {
      return emulator.transcript().stream().filter(row -> row.contains(marker)).count();
    }
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

  /** Byte-faithful subset of ANSI used by InlineFrameRenderer. */
  static final class AnsiEmulator {
    private final int columns;
    private final int rows;
    private final List<char[]> screen = new ArrayList<>();
    private final List<String> scrollback = new ArrayList<>();
    private int column;
    private int row;
    private boolean autoWrap = true;

    AnsiEmulator(int columns, int rows) {
      this.columns = columns;
      this.rows = rows;
      for (int index = 0; index < rows; index++) screen.add(blank());
    }

    void feed(String input) {
      for (int offset = 0; offset < input.length();) {
        int codePoint = input.codePointAt(offset);
        offset += Character.charCount(codePoint);
        if (codePoint == '\r') { column = 0; continue; }
        if (codePoint == '\n') { newline(); continue; }
        if (codePoint == 0x1b) { offset = escape(input, offset); continue; }
        if (codePoint >= 0x20) put(codePoint, UnicodeWidth.of(codePoint));
      }
    }

    List<String> transcript() {
      var result = new ArrayList<>(scrollback);
      for (char[] line : screen) result.add(trim(line));
      return result;
    }

    List<String> scrollback() { return List.copyOf(scrollback); }

    private int escape(String input, int offset) {
      if (offset >= input.length() || input.charAt(offset) != '[') return offset + 1;
      int cursor = offset + 1;
      boolean privateMode = cursor < input.length() && input.charAt(cursor) == '?';
      if (privateMode) cursor++;
      var parameters = new ArrayList<Integer>();
      int value = 0;
      boolean present = false;
      while (cursor < input.length()) {
        char character = input.charAt(cursor);
        if (Character.isDigit(character)) {
          value = value * 10 + character - '0';
          present = true;
          cursor++;
        } else if (character == ';') {
          parameters.add(present ? value : 0);
          value = 0;
          present = false;
          cursor++;
        } else {
          if (present || !parameters.isEmpty()) parameters.add(present ? value : 0);
          csi(character, privateMode, parameters);
          return cursor + 1;
        }
      }
      return cursor;
    }

    private void csi(char command, boolean privateMode, List<Integer> parameters) {
      int first = parameters.isEmpty() || parameters.getFirst() == 0 ? 1 : parameters.getFirst();
      switch (command) {
        case 'A' -> row = Math.max(0, row - first);
        case 'B' -> { for (int count = 0; count < first; count++) newline(); }
        case 'C' -> column = Math.min(columns, column + first);
        case 'D' -> column = Math.max(0, column - first);
        case 'G' -> column = Math.clamp(first - 1, 0, columns);
        case 'H', 'f' -> {
          int targetRow = parameters.isEmpty() ? 1 : Math.max(1, parameters.getFirst());
          int targetColumn = parameters.size() < 2 ? 1 : Math.max(1, parameters.get(1));
          row = Math.clamp(targetRow - 1, 0, rows - 1);
          column = Math.clamp(targetColumn - 1, 0, columns);
        }
        case 'K' -> eraseLine(parameters.isEmpty() ? 0 : parameters.getFirst());
        case 'J' -> eraseDisplay(parameters.isEmpty() ? 0 : parameters.getFirst());
        case 'h' -> { if (privateMode && parameters.contains(7)) autoWrap = true; }
        case 'l' -> { if (privateMode && parameters.contains(7)) autoWrap = false; }
        default -> { }
      }
    }

    private void put(int codePoint, int width) {
      if (width <= 0) return;
      if (column + width > columns) {
        if (autoWrap) { column = 0; newline(); }
        else column = Math.max(0, columns - width);
      }
      screen.get(row)[column] = codePoint < 128 ? (char) codePoint : '?';
      for (int index = 1; index < width && column + index < columns; index++) {
        screen.get(row)[column + index] = codePoint < 128 ? ' ' : '?';
      }
      column += width;
    }

    private void newline() {
      if (row < rows - 1) { row++; return; }
      scrollback.add(trim(screen.removeFirst()));
      screen.add(blank());
    }

    private void eraseLine(int mode) {
      char[] line = screen.get(row);
      if (mode == 2) Arrays.fill(line, ' ');
      else if (mode == 1) Arrays.fill(line, 0, Math.min(columns, column + 1), ' ');
      else Arrays.fill(line, Math.min(column, columns), columns, ' ');
    }

    private void eraseDisplay(int mode) {
      if (mode == 3) scrollback.clear();
      else if (mode == 2) for (char[] line : screen) Arrays.fill(line, ' ');
      else {
        eraseLine(0);
        for (int index = row + 1; index < rows; index++) Arrays.fill(screen.get(index), ' ');
      }
    }

    private char[] blank() {
      char[] line = new char[columns];
      Arrays.fill(line, ' ');
      return line;
    }

    private static String trim(char[] line) {
      int end = line.length;
      while (end > 0 && line[end - 1] == ' ') end--;
      return new String(line, 0, end);
    }
  }
}
