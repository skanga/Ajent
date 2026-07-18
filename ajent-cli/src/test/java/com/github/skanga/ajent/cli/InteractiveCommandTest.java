package com.github.skanga.ajent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.ActiveTurn;
import com.github.skanga.ajent.domain.CancellationSignal;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Profile;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.SessionPhase;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.provider.auth.CredentialStore;
import com.github.skanga.ajent.runtime.AgentState;
import com.github.skanga.ajent.runtime.PermissionPort;
import com.github.skanga.ajent.runtime.RuntimeMessage;
import com.github.skanga.ajent.terminal.JLineTerminalSession;
import com.github.skanga.ajent.terminal.input.TerminalKey;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class InteractiveCommandTest {
  @TempDir Path directory;

  @Test void configurationRejectsBadWorkspaceProfileAndSandboxThenComposesLocal() throws Exception {
    var command = command(Map.of());
    var error = new ByteArrayOutputStream();
    assertThat(command.configure(CliArguments.parse(new String[] {"--workspace", "missing"}),
        stream(error))).isNull();
    assertThat(error.toString(StandardCharsets.UTF_8)).contains("not a directory");

    error.reset();
    assertThat(command.configure(CliArguments.parse(new String[] {"--profile", "danger"}),
        stream(error))).isNull();
    assertThat(error.toString(StandardCharsets.UTF_8)).contains("must be write, ask, or minimal");

    error.reset();
    assertThat(command.configure(CliArguments.parse(new String[] {"--sandbox", "invalid"}),
        stream(error))).isNull();
    assertThat(error.toString(StandardCharsets.UTF_8)).contains("sandbox");

    var configured = command.configure(CliArguments.parse(new String[] {
        "--sandbox", "off", "--provider", "ollama", "--model", "local",
        "--profile", "minimal"}), stream(error));
    assertThat(configured).isNotNull();
    assertThat(configured.profile()).isEqualTo(Profile.MINIMAL);
    assertThat(configured.model()).isEqualTo("local");

    error.reset();
    assertThat(command.configure(CliArguments.parse(new String[] {
        "--sandbox", "off", "--provider", "ollama", "--profile", "write"}), stream(error)))
        .isNotNull().extracting(InteractiveCommand.Configuration::profile)
        .isEqualTo(Profile.WRITE);
    assertThat(command.configure(CliArguments.parse(new String[] {
        "--workspace", "\0"}), stream(error))).isNull();
    assertThat(command.run(CliArguments.parse(new String[] {
        "--workspace", "missing"}), stream(error))).isEqualTo(2);

    java.nio.file.Files.createDirectories(directory.resolve("docs"));
    var defaults = command.configure(CliArguments.parse(new String[] {
        "--sandbox", "off", "--key", "secret"}), stream(error));
    assertThat(defaults).isNotNull();
    assertThat(defaults.profile()).isEqualTo(Profile.ASK);
    assertThat(defaults.model()).isEqualTo("claude-opus-4-5");

    var invalidDocs = command(Map.of("AGENTTY_DOCS_DIR", "\0"));
    assertThat(invalidDocs.configure(CliArguments.parse(new String[] {
        "--sandbox", "off", "--provider", "ollama"}), stream(error))).isNotNull();
  }

  @Test void composerRoutesEditingSubmissionCancellationAndQuit() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var gate = new InteractiveCommand.PermissionGate();
    var ui = new InteractiveCommand.Ui(terminal, state, gate);
    var agent = new FakeAgent(state);
    ui.render();
    assertThat(terminal.bytes.toString()).contains("Ajent");

    assertThat(ui.key(character('a'), agent)).isTrue();
    ui.key(special(TerminalKey.SpecialKey.LEFT), agent);
    ui.key(character('b'), agent);
    ui.key(special(TerminalKey.SpecialKey.END), agent);
    ui.key(special(TerminalKey.SpecialKey.BACKSPACE), agent);
    ui.key(character('c'), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER, false, false, true), agent);
    ui.insert("paste");
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(agent.messages).singleElement().satisfies(message ->
        assertThat(((RuntimeMessage.Submit) message).text()).isEqualTo("bc\npaste"));

    ui.key(character('x'), agent);
    ui.key(character('u', true), agent);
    assertThat(ui.key(character('d', true), agent)).isFalse();

    state.set(withPhase(state.get(), new SessionPhase.Streaming(
        ActiveTurn.start(new CancellationSignal(), 1))));
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);
    assertThat(agent.messages.getLast()).isInstanceOf(RuntimeMessage.Cancel.class);
    assertThat(ui.key(character('c', true), agent)).isFalse();
  }

  @Test void composerCoversIdleControlsBoundariesAndIgnoredKeys() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var ui = new InteractiveCommand.Ui(new FakeTerminal(), state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    assertThat(ui.key(special(TerminalKey.SpecialKey.ENTER), agent)).isTrue();
    assertThat(agent.messages).isEmpty();
    assertThat(ui.key(special(TerminalKey.SpecialKey.RIGHT), agent)).isTrue();
    assertThat(ui.key(special(TerminalKey.SpecialKey.HOME), agent)).isTrue();
    assertThat(ui.key(special(TerminalKey.SpecialKey.BACKSPACE), agent)).isTrue();
    assertThat(ui.key(special(TerminalKey.SpecialKey.TAB), agent)).isTrue();
    assertThat(ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent)).isTrue();
    ui.key(character('a'), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER, false, true, false), agent);
    ui.key(character('u', true), agent);
    ui.key(character('x'), agent);
    assertThat(ui.key(character('d', true), agent)).isTrue();
    ui.key(character('u', true), agent);
    assertThat(ui.key(character('d', true), agent)).isFalse();
    ui.key(character('z'), agent);
    assertThat(ui.key(character('c', true), agent)).isFalse();
    var another = new InteractiveCommand.Ui(new FakeTerminal(), state,
        new InteractiveCommand.PermissionGate());
    assertThat(another.key(new TerminalKey(new TerminalKey.CharacterKey('q'),
        new TerminalKey.Modifiers(false, true, false)), agent)).isTrue();
    var notReady = new InteractiveCommand.Ui(new FakeTerminal(), new AtomicReference<>(),
        new InteractiveCommand.PermissionGate());
    notReady.render();
  }

  @Test void frameSchedulerCoalescesPendingWakeupsAndCanScheduleAgain() throws Exception {
    var first = new CountDownLatch(1);
    var second = new CountDownLatch(1);
    try (var scheduler = new InteractiveCommand.FrameScheduler()) {
      assertThat(scheduler.nowNanos()).isPositive();
      scheduler.request(first::countDown);
      scheduler.request(() -> { throw new AssertionError("not coalesced"); });
      assertThat(first.await(2, TimeUnit.SECONDS)).isTrue();
      scheduler.request(second::countDown);
      assertThat(second.await(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test void commandPaletteOwnsKeysFiltersDispatchesCompactAndCanQuit() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    ui.key(character('k', true), agent);
    for (int codePoint : "compact".codePoints().toArray()) ui.key(character(codePoint), agent);
    ui.key(special(TerminalKey.SpecialKey.DOWN), agent);
    ui.key(special(TerminalKey.SpecialKey.UP), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(agent.messages).contains(new RuntimeMessage.CompactContext());

    ui.key(character('k', true), agent);
    ui.key(character('x'), agent);
    ui.key(special(TerminalKey.SpecialKey.BACKSPACE), agent);
    for (int codePoint : "quit".codePoints().toArray()) ui.key(character(codePoint), agent);
    assertThat(ui.key(special(TerminalKey.SpecialKey.ENTER), agent)).isFalse();
    assertThat(terminal.bytes.toString()).contains("Commands");

    ui.key(character('k', true), agent);
    assertThat(ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent)).isTrue();
    ui.key(character('k', true), agent);
    assertThat(ui.key(special(TerminalKey.SpecialKey.ENTER), agent)).isTrue();
    assertThat(agent.newThreads).isEqualTo(1);

    ui.key(character('k', true), agent);
    for (int codePoint : "cycle profile".codePoints().toArray()) ui.key(character(codePoint), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(agent.profile).isEqualTo(Profile.MINIMAL);
    assertThat(terminal.bytes.toString()).contains("profile: minimal");

    ui.key(character('k', true), agent);
    for (int codePoint : "inspect tool".codePoints().toArray()) ui.key(character(codePoint), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(terminal.bytes.toString()).contains("no tool outputs");
  }

  @Test void permissionGateBlocksUntilModalKeysResolveAllDecisions() throws Exception {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var gate = new InteractiveCommand.PermissionGate();
    var ui = new InteractiveCommand.Ui(new FakeTerminal(), state, gate);
    var agent = new FakeAgent(state);
    for (var shape : List.of(
        new DecisionKey(character('y'), new PermissionPort.Decision(true, false)),
        new DecisionKey(character('a'), new PermissionPort.Decision(true, true)),
        new DecisionKey(character('n'), new PermissionPort.Decision(false, false)),
        new DecisionKey(special(TerminalKey.SpecialKey.ESCAPE),
            new PermissionPort.Decision(false, false)))) {
      var result = CompletableFuture.supplyAsync(() -> gate.request(tool("output")));
      while (gate.current() == null) java.lang.Thread.onSpinWait();
      ui.key(shape.key(), agent);
      assertThat(result.get(2, TimeUnit.SECONDS)).isEqualTo(shape.decision());
    }
    gate.cancel();
  }

  @Test void permissionModalIgnoresUnknownKeyUntilExplicitlyResolved() throws Exception {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var gate = new InteractiveCommand.PermissionGate();
    var ui = new InteractiveCommand.Ui(new FakeTerminal(), state, gate);
    var result = CompletableFuture.supplyAsync(() -> gate.request(tool("output")));
    while (gate.current() == null) java.lang.Thread.onSpinWait();
    assertThat(gate.request(tool("second")))
        .isEqualTo(new PermissionPort.Decision(false, false));
    assertThat(ui.key(character('x'), new FakeAgent(state))).isTrue();
    assertThat(result.isDone()).isFalse();
    assertThat(gate.resolve(false, false)).isTrue();
    assertThat(result.get(2, TimeUnit.SECONDS)).isEqualTo(new PermissionPort.Decision(false, false));
    assertThat(gate.resolve(true, false)).isFalse();
  }

  @Test void renderingCoversTranscriptToolsErrorsStatusAndNarrowWrapping() {
    ToolUse tool = tool("failed output");
    Message assistant = new Message(com.github.skanga.ajent.domain.MessageId.random(),
        Role.ASSISTANT, "long response", List.of(), List.of(), "", "", List.of(tool),
        Instant.now(), Optional.empty(), Optional.of("boom"), false, false);
    AgentState initial = AgentState.initial(thread(List.of(
        new Message(Role.USER, "hello", List.of(), List.of()), assistant)));
    var withStatus = new AgentState(initial.thread(), initial.phase(), initial.activeTurnId(),
        initial.turnCounter(), initial.tokensIn(), initial.tokensOut(), initial.lastTickNanos(),
        "error: retry", initial.toolDraft(), initial.queued(), initial.compaction(),
        initial.oauthRefreshInFlight(), initial.truncatedToolIds(), initial.sessionGrants());
    var terminal = new FakeTerminal();
    terminal.size = new JLineTerminalSession.Size(8, 5);
    new InteractiveCommand.Ui(terminal, new AtomicReference<>(withStatus),
        new InteractiveCommand.PermissionGate()).render();
    assertThat(terminal.bytes.toString()).contains("assistan").contains("error:");
  }

  @Test void renderingCoversSuccessfulToolNormalStatusEmptyTextAndResizeDiff() {
    ToolUse done = new ToolUse(new ToolCallId("done"), new ToolName("read"), Map.of(),
        new ToolStatus.Done(0, 1, "ok"));
    Message assistant = new Message(Role.ASSISTANT, "", List.of(), List.of(done));
    AgentState initial = AgentState.initial(thread(List.of(assistant)));
    var normal = new AgentState(initial.thread(), initial.phase(), initial.activeTurnId(),
        initial.turnCounter(), initial.tokensIn(), initial.tokensOut(), initial.lastTickNanos(),
        "ready", initial.toolDraft(), initial.queued(), initial.compaction(),
        initial.oauthRefreshInFlight(), initial.truncatedToolIds(), initial.sessionGrants());
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, new AtomicReference<>(normal),
        new InteractiveCommand.PermissionGate());
    ui.render();
    terminal.size = new JLineTerminalSession.Size(20, 4);
    ui.render();
    assertThat(terminal.bytes.toString()).contains("read").contains("ready");
  }

  @Test void streamingTranscriptRequestsFramesAndFinalizationSettles() {
    Message assistant = new Message(Role.ASSISTANT, "abcdef", List.of(), List.of());
    AgentState streaming = withPhase(AgentState.initial(thread(List.of(assistant))),
        new SessionPhase.Streaming(ActiveTurn.start(new CancellationSignal(), 1)));
    var state = new AtomicReference<>(streaming);
    var terminal = new FakeTerminal();
    var animation = new ManualAnimation();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate(), animation);

    ui.render();
    assertThat(animation.requested).isEqualTo(1);
    assertThat(terminal.bytes.toString()).doesNotContain("abcdef");
    animation.now = 20_000_000;
    animation.runFrame();
    assertThat(animation.requested).isEqualTo(2);

    state.set(withPhase(state.get(), new SessionPhase.Idle()));
    for (int index = 0; index < 20 && animation.frame != null; index++) {
      animation.now += 16_000_000;
      animation.runFrame();
    }
    // The inline renderer emits only changed cells, so the final suffix proves catch-up.
    assertThat(terminal.bytes.toString()).contains("ef");
    assertThat(animation.frame).isNull();
  }

  @Test void liveToolViewerOwnsListBodyNavigationAndOsc52Copy() {
    ToolUse first = new ToolUse(new ToolCallId("one"), new ToolName("read_file"), Map.of(),
        new ToolStatus.Done(0, 100_000_000, "first"));
    ToolUse second = new ToolUse(new ToolCallId("two"), new ToolName("bash"), Map.of(),
        new ToolStatus.Failed(0, 100_000_000, "second"));
    var state = new AtomicReference<>(AgentState.initial(thread(List.of(
        new Message(Role.ASSISTANT, "", List.of(), List.of(first, second))))));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    ui.key(character('o', true), agent);
    assertThat(terminal.bytes.toString()).contains("Tool outputs");
    ui.key(special(TerminalKey.SpecialKey.DOWN), agent);
    ui.key(special(TerminalKey.SpecialKey.UP), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    ui.key(special(TerminalKey.SpecialKey.PAGE_DOWN), agent);
    ui.key(special(TerminalKey.SpecialKey.PAGE_UP), agent);
    ui.key(special(TerminalKey.SpecialKey.HOME), agent);
    ui.key(special(TerminalKey.SpecialKey.END), agent);
    ui.key(special(TerminalKey.SpecialKey.RIGHT), agent);
    ui.key(special(TerminalKey.SpecialKey.LEFT), agent);
    ui.key(character('j'), agent);
    ui.key(character('k'), agent);
    ui.key(character('l'), agent);
    ui.key(character('h'), agent);
    ui.key(character('y'), agent);
    assertThat(terminal.bytes.toString()).contains("\u001b]52;c;c2Vjb25k\u0007");
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);
    ui.key(character('q'), agent);
  }

  @Test void liveToolViewerReportsEmptySnapshots() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    ui.key(character('o', true), new FakeAgent(state));
    assertThat(terminal.bytes.toString()).contains("no tool outputs");
  }

  private InteractiveCommand command(Map<String, String> environment) {
    return new InteractiveCommand(directory, directory, environment,
        new CredentialStore(directory.resolve("credentials.json"), "seed"),
        HttpClient.newHttpClient());
  }

  private static PrintStream stream(ByteArrayOutputStream bytes) {
    return new PrintStream(bytes, true, StandardCharsets.UTF_8);
  }

  private static com.github.skanga.ajent.domain.Thread thread(List<Message> messages) {
    return new com.github.skanga.ajent.domain.Thread(new ThreadId("thread"), "", messages);
  }

  private static AgentState withPhase(AgentState state, SessionPhase phase) {
    return new AgentState(state.thread(), phase, state.activeTurnId(), state.turnCounter(),
        state.tokensIn(), state.tokensOut(), state.lastTickNanos(), state.status(),
        state.toolDraft(), state.queued(), state.compaction(), state.oauthRefreshInFlight(),
        state.truncatedToolIds(), state.sessionGrants());
  }

  private static ToolUse tool(String output) {
    return new ToolUse(new ToolCallId("call"), new ToolName("bash"), Map.of(),
        new ToolStatus.Failed(0, 100_000_000, output));
  }

  private static TerminalKey character(int value) { return character(value, false); }
  private static TerminalKey character(int value, boolean ctrl) {
    return new TerminalKey(new TerminalKey.CharacterKey(value),
        new TerminalKey.Modifiers(ctrl, false, false));
  }
  private static TerminalKey special(TerminalKey.SpecialKey value) {
    return special(value, false, false, false);
  }
  private static TerminalKey special(
      TerminalKey.SpecialKey value, boolean ctrl, boolean alt, boolean shift) {
    return new TerminalKey(value, new TerminalKey.Modifiers(ctrl, alt, shift));
  }

  private static final class FakeTerminal implements InteractiveCommand.TerminalPort {
    private final StringBuilder bytes = new StringBuilder();
    private JLineTerminalSession.Size size = new JLineTerminalSession.Size(40, 12);
    @Override public JLineTerminalSession.Size size() { return size; }
    @Override public void write(String value) { bytes.append(value); }
  }

  private static final class FakeAgent implements InteractiveCommand.AgentControl {
    private final AtomicReference<AgentState> state;
    private final List<RuntimeMessage> messages = new ArrayList<>();
    private int newThreads;
    private Profile profile = Profile.ASK;
    FakeAgent(AtomicReference<AgentState> state) { this.state = state; }
    @Override public AgentState state() { return state.get(); }
    @Override public void dispatch(RuntimeMessage message) { messages.add(message); }
    @Override public void newThread() { newThreads++; }
    @Override public Profile cycleProfile() {
      profile = switch (profile) {
        case WRITE -> Profile.ASK;
        case ASK -> Profile.MINIMAL;
        case MINIMAL -> Profile.WRITE;
      };
      return profile;
    }
  }

  private static final class ManualAnimation implements InteractiveCommand.AnimationPort {
    private long now;
    private int requested;
    private Runnable frame;
    @Override public long nowNanos() { return now; }
    @Override public void request(Runnable next) { requested++; frame = next; }
    private void runFrame() {
      Runnable next = frame;
      frame = null;
      if (next != null) next.run();
    }
  }

  private record DecisionKey(TerminalKey key, PermissionPort.Decision decision) {}
}
