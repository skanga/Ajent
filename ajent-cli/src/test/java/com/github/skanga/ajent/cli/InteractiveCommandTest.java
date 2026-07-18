package com.github.skanga.ajent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.ActiveTurn;
import com.github.skanga.ajent.domain.CancellationSignal;
import com.github.skanga.ajent.domain.CheckpointId;
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
import com.github.skanga.ajent.terminal.ui.LoginModal;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.net.URI;
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
    assertThat(InteractiveCommand.systemDefault()).isNotNull();
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
    new CredentialStore(directory.resolve("credentials.json"), "seed").save(
        new com.github.skanga.ajent.provider.auth.Credential.OAuth(
            "access", "refresh", System.currentTimeMillis() + 60_000));
    assertThat(command(Map.of()).configure(CliArguments.parse(new String[] {
        "--sandbox", "off"}), stream(error))).isNotNull();
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
    ui.key(special(TerminalKey.SpecialKey.TAB), agent);
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
    ui.key(special(TerminalKey.SpecialKey.LEFT), agent);

    ui.key(character('k', true), agent);
    for (int codePoint : "switch provider".codePoints().toArray()) ui.key(character(codePoint), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    ui.key(special(TerminalKey.SpecialKey.DOWN), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(agent.provider).isEqualTo("ollama");
    assertThat(terminal.bytes.toString()).contains("Providers").contains("provider: Ollama");
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent); // closes model picker opened after switch

    ui.key(character('k', true), agent);
    for (int codePoint : "switch provider".codePoints().toArray()) ui.key(character(codePoint), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    ui.key(special(TerminalKey.SpecialKey.UP), agent);
    ui.key(special(TerminalKey.SpecialKey.HOME), agent);
    ui.key(special(TerminalKey.SpecialKey.END), agent);
    ui.key(special(TerminalKey.SpecialKey.PAGE_UP), agent);
    ui.key(special(TerminalKey.SpecialKey.PAGE_DOWN), agent);
    ui.key(special(TerminalKey.SpecialKey.TAB), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    agent.failCustomHost = true;
    ui.paste("https://host.test/v1");
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(terminal.bytes.toString()).contains("save failed");
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);
    agent.failCustomHost = false;
    ui.key(character('k', true), agent);
    for (int codePoint : "switch provider".codePoints().toArray()) ui.key(character(codePoint), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    ui.key(special(TerminalKey.SpecialKey.END), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    ui.paste("https://host.test/v1");
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(agent.provider).isEqualTo("host.test");
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);

    agent.rejectProvider = true;
    ui.key(character('k', true), agent);
    for (int codePoint : "switch provider".codePoints().toArray()) ui.key(character(codePoint), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    ui.key(character('x'), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    agent.failProviderKey = true;
    ui.paste("provider-key");
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(terminal.bytes.toString()).contains("save failed");
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);
    agent.failProviderKey = false;
    ui.key(character('k', true), agent);
    for (int codePoint : "switch provider".codePoints().toArray()) ui.key(character(codePoint), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    ui.paste("provider-key");
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(agent.providerKey).isEqualTo("provider-key");
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);
    agent.rejectProvider = false;

    ui.key(character('k', true), agent);
    for (int codePoint : "switch provider".codePoints().toArray()) ui.key(character(codePoint), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);
    assertThat(agent.messages.getLast()).isInstanceOf(RuntimeMessage.Cancel.class);
    assertThat(ui.key(character('c', true), agent)).isFalse();
  }

  @Test void composerCoversIdleControlsBoundariesAndIgnoredKeys() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var ui = new InteractiveCommand.Ui(new FakeTerminal(), state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    ui.paste("p");
    ui.key(character('u', true), agent);
    assertThat(ui.key(special(TerminalKey.SpecialKey.ENTER), agent)).isTrue();
    assertThat(agent.messages).isEmpty();
    assertThat(ui.key(special(TerminalKey.SpecialKey.RIGHT), agent)).isTrue();
    assertThat(ui.key(special(TerminalKey.SpecialKey.HOME), agent)).isTrue();
    assertThat(ui.key(special(TerminalKey.SpecialKey.BACKSPACE), agent)).isTrue();
    assertThat(ui.key(special(TerminalKey.SpecialKey.TAB), agent)).isTrue();
    assertThat(ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent)).isTrue();
    ui.key(character('a'), agent);
    ui.key(special(TerminalKey.SpecialKey.HOME), agent);
    ui.key(special(TerminalKey.SpecialKey.RIGHT), agent);
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
    for (int codePoint : "no such command".codePoints().toArray()) ui.key(character(codePoint), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
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

    ui.key(character('k', true), agent);
    for (int codePoint : "open model".codePoints().toArray()) ui.key(character(codePoint), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    ui.key(special(TerminalKey.SpecialKey.UP), agent);
    ui.key(special(TerminalKey.SpecialKey.HOME), agent);
    ui.key(special(TerminalKey.SpecialKey.END), agent);
    ui.key(special(TerminalKey.SpecialKey.PAGE_UP), agent);
    ui.key(special(TerminalKey.SpecialKey.PAGE_DOWN), agent);
    ui.key(character('z'), agent);
    ui.key(special(TerminalKey.SpecialKey.BACKSPACE), agent);
    ui.key(new TerminalKey(new TerminalKey.CharacterKey('x'),
        new TerminalKey.Modifiers(true, false, false)), agent);
    ui.key(new TerminalKey(new TerminalKey.CharacterKey('x'),
        new TerminalKey.Modifiers(false, true, false)), agent);
    ui.key(special(TerminalKey.SpecialKey.DOWN), agent);
    ui.key(character('f'), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(agent.model).isEqualTo("beta");
    assertThat(agent.favorites).containsExactly("beta");
    assertThat(terminal.bytes.toString()).contains("Models").contains("model: Beta");

    ui.key(character('k', true), agent);
    for (int codePoint : "open model".codePoints().toArray()) ui.key(character(codePoint), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    ui.key(special(TerminalKey.SpecialKey.TAB), agent);
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);
  }

  @Test void modelPickerRendersLoadingBeforeDeferredCatalogArrives() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    agent.deferModels = true;
    ui.key(character('k', true), agent);
    for (int codePoint : "open model".codePoints().toArray()) ui.key(character(codePoint), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(terminal.bytes.toString()).contains("loading");
    agent.completeModels();
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);
  }

  @Test void liveDiffReviewRoutesHunksAndPaletteWideActions() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    agent.changes = List.of(
        new com.github.skanga.ajent.terminal.ui.DiffReview.File("a.txt", List.of(
            new com.github.skanga.ajent.terminal.ui.DiffReview.Hunk("-old\n+new",
                com.github.skanga.ajent.terminal.ui.DiffReview.Status.PENDING))),
        new com.github.skanga.ajent.terminal.ui.DiffReview.File("b.txt", List.of(
            new com.github.skanga.ajent.terminal.ui.DiffReview.Hunk("+body",
                com.github.skanga.ajent.terminal.ui.DiffReview.Status.PENDING))));
    selectCommand(ui, agent, "review changes");
    ui.key(special(TerminalKey.SpecialKey.DOWN), agent);
    ui.key(character('a'), agent);
    ui.key(special(TerminalKey.SpecialKey.RIGHT), agent);
    ui.key(character('r'), agent);
    ui.key(special(TerminalKey.SpecialKey.LEFT), agent);
    ui.key(character('x'), agent);
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);
    assertThat(terminal.bytes.toString()).contains("hanges  a.txt");

    selectCommand(ui, agent, "accept all");
    assertThat(terminal.bytes.toString()).contains("accepted 2 hunks");
    selectCommand(ui, agent, "reject all");
    assertThat(agent.changes).isEmpty();
  }

  @Test void livePlanTracksTodoToolUpdatesAndOwnsKeysUntilEscape() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    terminal.size = new JLineTerminalSession.Size(80, 12);
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    ui.updatePlan(List.of(
        com.github.skanga.ajent.terminal.ui.PlanModal.Item.fromTool("queue", "pending"),
        com.github.skanga.ajent.terminal.ui.PlanModal.Item.fromTool("work", "in_progress"),
        com.github.skanga.ajent.terminal.ui.PlanModal.Item.fromTool("ship", "completed")));
    ui.key(character('t', true), agent);
    assertThat(terminal.bytes.toString()).contains(
        "Plan", "[ ] queue", "[-] work", "[x] ship", "1/3 completed");
    ui.key(character('z'), agent);
    assertThat(agent.messages).isEmpty();
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);
    ui.key(character('z'), agent);
    ui.key(character('u', true), agent);

    ui.updatePlan(List.of());
    selectCommand(ui, agent, "open plan");
    assertThat(terminal.bytes.toString())
        .contains("No tasks yet.", "The agent will create tasks as it works.");
  }

  @Test void todoLedgerPublishesInitialAndNormalizedImmutableSnapshots() {
    var ledger = new InteractiveCommand.TodoLedger();
    var observed = new AtomicReference<List<com.github.skanga.ajent.terminal.ui.PlanModal.Item>>();
    ledger.onChange(observed::set);
    assertThat(observed.get()).isEmpty();
    var source = new ArrayList<com.github.skanga.ajent.tools.host.HostServices.TodoItem>();
    source.add(new com.github.skanga.ajent.tools.host.HostServices.TodoItem("one", "completed"));
    source.add(new com.github.skanga.ajent.tools.host.HostServices.TodoItem("two", "future"));
    ledger.set(source);
    source.clear();
    assertThat(observed.get()).extracting(
        com.github.skanga.ajent.terminal.ui.PlanModal.Item::status).containsExactly(
            com.github.skanga.ajent.terminal.ui.PlanModal.Status.COMPLETED,
            com.github.skanga.ajent.terminal.ui.PlanModal.Status.PENDING);
  }

  @Test void liveCodeBlockPickerCopiesEditsRunsAndAttachesExplicitly() {
    Message reply = new Message(Role.ASSISTANT,
        "```sh\n$ echo one\n```\n```python\nprint(2)\n```", List.of(), List.of());
    var state = new AtomicReference<>(AgentState.initial(thread(List.of(reply))));
    var terminal = new FakeTerminal();
    terminal.size = new JLineTerminalSession.Size(80, 20);
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);

    ui.key(character('g', true), agent);
    assertThat(terminal.bytes.toString()).contains(
        "Run Code Block", "1  echo one", "python Â· 1 line");
    ui.key(special(TerminalKey.SpecialKey.DOWN), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(terminal.bytes.toString()).contains("isn't runnable here");
    ui.key(character('y'), agent);
    assertThat(terminal.bytes.toString()).contains("cHJpbnQoMik=");

    ui.key(character('g', true), agent);
    ui.key(character('e'), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(((RuntimeMessage.Submit) agent.messages.getLast()).text()).isEqualTo("echo one");

    ui.key(character('g', true), agent);
    ui.key(character('1'), agent);
    assertThat(agent.ranBlocks).extracting(
        com.github.skanga.ajent.terminal.ui.CodeBlockPicker.Block::body)
        .containsExactly("echo one");
    assertThat(terminal.bytes.toString()).contains("Run Result", "exit 0", "captured");
    ui.key(special(TerminalKey.SpecialKey.PAGE_DOWN), agent);
    ui.key(special(TerminalKey.SpecialKey.PAGE_UP), agent);
    ui.key(special(TerminalKey.SpecialKey.DOWN), agent);
    ui.key(special(TerminalKey.SpecialKey.UP), agent);
    ui.key(character('a'), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(((RuntimeMessage.Submit) agent.messages.getLast()).text())
        .contains("I ran:", "echo one", "output:", "captured");

    ui.key(character('g', true), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    ui.key(character('y'), agent);
    assertThat(terminal.bytes.toString()).contains("Y2FwdHVyZWQ=");
  }

  @Test void codeBlockEntryReportsEmptyAndBusyAndSafeResultDismissals() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    ui.key(character('g', true), agent);
    assertThat(terminal.bytes.toString()).contains("no code blocks in the last reply");

    state.set(withPhase(AgentState.initial(thread(List.of(new Message(Role.ASSISTANT,
        "```sh\necho ok\n```", List.of(), List.of())))), new SessionPhase.Streaming(
            ActiveTurn.start(new CancellationSignal(), 1))));
    selectCommand(ui, agent, "run code block");
    assertThat(terminal.bytes.toString()).contains("wait for the reply to finish");
    state.set(AgentState.initial(thread(List.of(new Message(Role.ASSISTANT,
        "```sh\necho ok\n```", List.of(), List.of())))));
    ui.key(character('g', true), agent);
    ui.key(special(TerminalKey.SpecialKey.TAB), agent);
    ui.key(character('q'), agent);
    agent.runOutput = "";
    agent.runExit = 1;
    agent.runTimedOut = true;
    ui.key(character('g', true), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(terminal.bytes.toString()).contains("timed out", "(no output captured)");
    ui.key(special(TerminalKey.SpecialKey.TAB), agent);
    ui.key(character('x'), agent);
    ui.key(character('q'), agent);
    agent.runOutput = "captured";
    agent.runExit = 0;
    agent.runTimedOut = false;
    ui.key(character('g', true), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    ui.key(character('g', true), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    ui.key(character('d'), agent);
  }

  @Test void checkpointPickerLoadsDiffsNavigatesAndRefillsPromptAfterRewind() {
    var first = checkpointMessage("cp1", "first change");
    var second = checkpointMessage("cp2", "second change\nmore");
    var state = new AtomicReference<>(AgentState.initial(thread(List.of(first,
        new Message(Role.ASSISTANT, "done", List.of(), List.of()), second))));
    var terminal = new FakeTerminal();
    terminal.size = new JLineTerminalSession.Size(80, 20);
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    agent.checkpointsAvailable = true;
    selectCommand(ui, agent, "rewind");
    assertThat(terminal.bytes.toString()).contains(
        "Rewind to Checkpoint", "Turn 1  first change", "Turn 2  second change",
        "2 files", "+3", "\u22124");
    ui.key(character('k'), agent);
    ui.key(character('j'), agent);
    ui.key(special(TerminalKey.SpecialKey.UP), agent);
    ui.key(special(TerminalKey.SpecialKey.DOWN), agent);
    ui.key(special(TerminalKey.SpecialKey.HOME), agent);
    ui.key(special(TerminalKey.SpecialKey.END), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(agent.restoredCheckpoint).isEqualTo(new CheckpointId("cp2"));
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(((RuntimeMessage.Submit) agent.messages.getLast()).text())
        .isEqualTo("second change\nmore");
    assertThat(terminal.bytes.toString()).contains("\u001b[2J\u001b[3J\u001b[H");
  }

  @Test void checkpointEntryExplainsRepoEmptyBusyFailureAndClosePaths() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    selectCommand(ui, agent, "rewind");
    assertThat(terminal.bytes.toString()).contains("checkpoints need a git repo");
    agent.checkpointsAvailable = true;
    selectCommand(ui, agent, "rewind");
    assertThat(terminal.bytes.toString()).contains("no checkpoints in this thread yet");

    state.set(withPhase(AgentState.initial(thread(List.of(checkpointMessage("cp", "prompt")))),
        new SessionPhase.Streaming(ActiveTurn.start(new CancellationSignal(), 1))));
    selectCommand(ui, agent, "rewind");
    assertThat(terminal.bytes.toString()).contains("cannot rewind while the agent is working");
    state.set(AgentState.initial(thread(List.of(checkpointMessage("cp", "prompt")))));
    selectCommand(ui, agent, "rewind");
    ui.key(special(TerminalKey.SpecialKey.TAB), agent);
    ui.key(character('x'), agent);
    ui.key(character('q'), agent);
    selectCommand(ui, agent, "rewind");
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);
    agent.checkpointFailure = "missing ref";
    selectCommand(ui, agent, "rewind");
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(terminal.bytes.toString()).contains("rewind failed: missing ref");
  }

  @Test void checkpointRestoreInFlightIsGatedAndCleanDiffIsRendered() {
    var state = new AtomicReference<>(AgentState.initial(
        thread(List.of(checkpointMessage("cp", "prompt")))));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    agent.checkpointsAvailable = true;
    agent.checkpointClean = true;
    agent.deferCheckpointRestore = true;
    selectCommand(ui, agent, "rewind");
    assertThat(terminal.bytes.toString()).contains("no changes");
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    selectCommand(ui, agent, "rewind");
    assertThat(terminal.bytes.toString()).contains("cannot rewind while the agent is working");
    agent.completeCheckpointRestore();
  }

  @Test void multilineBareCodeResultCoversFailureNewlineAndPluralRendering() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of(
        new Message(Role.ASSISTANT, "```\necho a\necho b\n```", List.of(), List.of())))));
    var terminal = new FakeTerminal();
    terminal.size = new JLineTerminalSession.Size(80, 20);
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    agent.runOutput = "failed\n";
    agent.runExit = 1;
    ui.key(character('g', true), agent);
    assertThat(terminal.bytes.toString()).contains("sh Â· 2 lines");
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(terminal.bytes.toString()).contains("echo a â€¦", "exit 1");
    ui.key(character('a'), agent);
  }

  private static Message checkpointMessage(String id, String text) {
    return new Message(new com.github.skanga.ajent.domain.MessageId(id), Role.USER, text,
        List.of(), List.of(), "", "", List.of(), Instant.EPOCH,
        Optional.of(new CheckpointId(id)), Optional.empty(), false);
  }

  @Test void savedThreadPickerLoadsAtCurrentNavigatesAndSwapsWholeView() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    agent.threads = List.of(
        threadEntry("new", "Newest"), threadEntry("thread", ""), threadEntry("old", "Oldest"));

    selectCommand(ui, agent, "open threads");
    assertThat(terminal.bytes.toString()).contains("Threads", "â— (untitled)", "2/3");
    ui.key(special(TerminalKey.SpecialKey.DOWN), agent);
    ui.key(special(TerminalKey.SpecialKey.HOME), agent);
    ui.key(special(TerminalKey.SpecialKey.END), agent);
    ui.key(special(TerminalKey.SpecialKey.PAGE_UP), agent);
    ui.key(special(TerminalKey.SpecialKey.PAGE_DOWN), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(agent.loadedThreads).containsExactly(new ThreadId("old"));
    assertThat(terminal.bytes.toString()).contains("\u001b[2J\u001b[3J\u001b[H");

    ui.key(character('j', true), agent);
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);
    ui.key(special(TerminalKey.SpecialKey.LEFT, false, true, false), agent);
    assertThat(agent.loadedThreads).containsExactly(new ThreadId("old"), new ThreadId("thread"));
    assertThat(terminal.bytes.toString()).contains("thread 2/3 Â· (untitled)");
  }

  @Test void threadPickerHandlesSameThreadNewEmptyFailureAndActiveCycle() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    agent.threads = List.of(threadEntry("thread", "Current"));
    selectCommand(ui, agent, "open threads");
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(agent.loadedThreads).isEmpty();

    selectCommand(ui, agent, "open threads");
    ui.key(character('n'), agent);
    assertThat(agent.newThreads).isEqualTo(1);

    agent.threads = List.of();
    ui.key(character('j', true), agent);
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);
    ui.key(special(TerminalKey.SpecialKey.RIGHT, false, true, false), agent);
    assertThat(terminal.bytes.toString()).contains("no other threads yet");

    agent.threads = List.of(threadEntry("other", "Other"));
    ui.key(character('j', true), agent);
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);
    state.set(withPhase(state.get(), new SessionPhase.Streaming(
        ActiveTurn.start(new CancellationSignal(), 1))));
    ui.key(special(TerminalKey.SpecialKey.RIGHT, false, true, false), agent);
    assertThat(terminal.bytes.toString()).contains("wait for the reply to finish");
    state.set(AgentState.initial(thread(List.of())));
    agent.threadFailure = "invalid JSON";
    ui.key(special(TerminalKey.SpecialKey.RIGHT, false, true, false), agent);
    assertThat(terminal.bytes.toString()).contains("error: invalid JSON");
  }

  private static com.github.skanga.ajent.terminal.ui.ThreadPicker.Entry threadEntry(
      String id, String title) {
    return new com.github.skanga.ajent.terminal.ui.ThreadPicker.Entry(
        new ThreadId(id), title, Instant.EPOCH);
  }

  @Test void structuredToolCompletionBuildsReviewPatch() {
    var changes = new AtomicReference<List<com.github.skanga.ajent.tools.runtime.FileChange>>(
        List.of());
    InteractiveCommand.recordChange(new RuntimeMessage.Tick(), changes);
    var change = new com.github.skanga.ajent.tools.runtime.FileChange(
        "file.txt", 1, 1, "old", "new");
    InteractiveCommand.recordChange(new RuntimeMessage.ToolCompleted(1, "call",
        new com.github.skanga.ajent.runtime.ToolCompletion.Success(
            "ok", Optional.of(change))), changes);
    assertThat(changes.get()).containsExactly(change);
    assertThat(InteractiveCommand.reviewFile(change).hunks().getFirst().patch())
        .contains("--- file.txt", "-old", "+new");
  }

  private static void selectCommand(
      InteractiveCommand.Ui ui, FakeAgent agent, String query) {
    ui.key(character('k', true), agent);
    for (int codePoint : query.codePoints().toArray()) ui.key(character(codePoint), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
  }

  @Test void liveLoginSupportsAnthropicKeyAndOauthBrowserExchange() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);

    ui.key(character('k', true), agent);
    for (int codePoint : "login".codePoints().toArray()) ui.key(character(codePoint), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    ui.key(character('2'), agent);
    ui.paste("secret");
    ui.key(special(TerminalKey.SpecialKey.LEFT), agent);
    ui.key(special(TerminalKey.SpecialKey.RIGHT), agent);
    ui.key(special(TerminalKey.SpecialKey.BACKSPACE), agent);
    ui.key(character('t'), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(agent.anthropicKey).isEqualTo("secret");
    assertThat(terminal.bytes.toString()).contains("API key").doesNotContain("secret");

    ui.key(character('k', true), agent);
    for (int codePoint : "login".codePoints().toArray()) ui.key(character(codePoint), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    ui.key(character('1'), agent);
    assertThat(agent.browser).isNotNull();
    ui.paste("oauth-code");
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(agent.oauthCode).isEqualTo("oauth-code");
    assertThat(terminal.bytes.toString()).contains("OAuth");
  }

  @Test void liveLoginRendersFailuresAndOwnsRecoveryKeys() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);

    openLogin(ui, agent);
    ui.key(special(TerminalKey.SpecialKey.TAB), agent);
    ui.key(character('2'), agent);
    ui.key(new TerminalKey(new TerminalKey.CharacterKey('x'),
        new TerminalKey.Modifiers(false, true, false)), agent);
    ui.key(new TerminalKey(new TerminalKey.CharacterKey('x'),
        new TerminalKey.Modifiers(true, false, false)), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(terminal.bytes.toString()).contains("no key entered");
    ui.key(character('2'), agent);
    ui.paste("key");
    agent.failAnthropicKey = true;
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(terminal.bytes.toString()).contains("save failed");
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);

    openLogin(ui, agent);
    ui.key(character('1'), agent);
    ui.paste("code");
    agent.deferOAuth = true;
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(terminal.bytes.toString()).contains("Exchanging OAuth code");
    agent.completeOAuth("exchange failed");
    assertThat(terminal.bytes.toString()).contains("exchange failed");
    ui.key(character('x'), agent); // Failed routes through the picking reducer.
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);
  }

  private static void openLogin(InteractiveCommand.Ui ui, FakeAgent agent) {
    ui.key(character('k', true), agent);
    for (int codePoint : "login".codePoints().toArray()) ui.key(character(codePoint), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
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
    var changes = new AtomicReference<>(0);
    gate.onChange(() -> changes.updateAndGet(value -> value + 1));
    var ui = new InteractiveCommand.Ui(new FakeTerminal(), state, gate);
    var result = CompletableFuture.supplyAsync(() -> gate.request(tool("output")));
    while (gate.current() == null) java.lang.Thread.onSpinWait();
    ui.render();
    assertThat(gate.request(tool("second")))
        .isEqualTo(new PermissionPort.Decision(false, false));
    assertThat(ui.key(character('x'), new FakeAgent(state))).isTrue();
    assertThat(result.isDone()).isFalse();
    assertThat(gate.resolve(false, false)).isTrue();
    assertThat(result.get(2, TimeUnit.SECONDS)).isEqualTo(new PermissionPort.Decision(false, false));
    assertThat(gate.resolve(true, false)).isFalse();
    assertThat(changes.get()).isPositive();
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

  @Test void settledAssistantBeforeUserIsNotTreatedAsLiveTail() {
    AgentState state = AgentState.initial(thread(List.of(
        new Message(Role.ASSISTANT, "complete", List.of(), List.of()),
        new Message(Role.USER, "next", List.of(), List.of()))));
    var terminal = new FakeTerminal();
    new InteractiveCommand.Ui(terminal, new AtomicReference<>(state),
        new InteractiveCommand.PermissionGate()).render();
    assertThat(terminal.bytes.toString()).contains("complete").contains("next");

    AgentState live = withPhase(AgentState.initial(thread(List.of(
        new Message(Role.ASSISTANT, "live", List.of(), List.of())))),
        new SessionPhase.Streaming(ActiveTurn.start(new CancellationSignal(), 1)));
    new InteractiveCommand.Ui(new FakeTerminal(), new AtomicReference<>(live),
        new InteractiveCommand.PermissionGate()).render();
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
    ui.key(special(TerminalKey.SpecialKey.TAB), agent);
    ui.key(character('j'), agent);
    ui.key(character('k'), agent);
    ui.key(character('l'), agent);
    ui.key(character('h'), agent);
    ui.key(character('x'), agent);
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

  @Test void emptyToolNameAndClosedStreamingBlockRenderSafely() {
    ToolUse unnamed = new ToolUse(new ToolCallId("empty"), new ToolName(""), Map.of(),
        new ToolStatus.Done(0, 1, "output"));
    Message assistant = new Message(com.github.skanga.ajent.domain.MessageId.random(),
        Role.ASSISTANT, "closed", List.of(), List.of(), "", "", List.of(unnamed),
        Instant.now(), Optional.empty(), Optional.empty(), true, false);
    AgentState streaming = withPhase(AgentState.initial(thread(List.of(assistant))),
        new SessionPhase.Streaming(ActiveTurn.start(new CancellationSignal(), 1)));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, new AtomicReference<>(streaming),
        new InteractiveCommand.PermissionGate());
    ui.render();
    ui.key(character('o', true), new FakeAgent(new AtomicReference<>(streaming)));
    assertThat(terminal.bytes.toString()).contains("closed").contains("Tool outputs");
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
    private String model = "alpha";
    private List<String> favorites = List.of();
    private String provider = "anthropic";
    private boolean rejectProvider;
    private boolean deferModels;
    private String anthropicKey = "";
    private String providerKey = "";
    private URI browser;
    private String oauthCode = "";
    private boolean failAnthropicKey;
    private boolean failProviderKey;
    private boolean failCustomHost;
    private boolean deferOAuth;
    private java.util.function.Consumer<String> pendingOAuth;
    private List<com.github.skanga.ajent.terminal.ui.DiffReview.File> changes = List.of();
    private List<com.github.skanga.ajent.terminal.ui.ThreadPicker.Entry> threads = List.of();
    private final List<ThreadId> loadedThreads = new ArrayList<>();
    private String threadFailure = "";
    private final List<com.github.skanga.ajent.terminal.ui.CodeBlockPicker.Block> ranBlocks =
        new ArrayList<>();
    private String runOutput = "captured";
    private int runExit;
    private boolean runTimedOut;
    private boolean checkpointsAvailable;
    private String checkpointFailure = "";
    private CheckpointId restoredCheckpoint;
    private boolean checkpointClean;
    private boolean deferCheckpointRestore;
    private java.util.function.Consumer<com.github.skanga.ajent.terminal.ui.CheckpointPicker.Restore>
        pendingCheckpointRestore;
    private java.util.function.Consumer<List<com.github.skanga.ajent.terminal.ui.ModelPicker.Model>>
        pendingModels;
    FakeAgent(AtomicReference<AgentState> state) { this.state = state; }
    @Override public AgentState state() { return state.get(); }
    @Override public void dispatch(RuntimeMessage message) { messages.add(message); }
    @Override public void newThread() { newThreads++; }
    @Override public ThreadId threadId() { return state.get().thread().id(); }
    @Override public void loadThreads(
        java.util.function.Consumer<List<com.github.skanga.ajent.terminal.ui.ThreadPicker.Entry>> receiver) {
      receiver.accept(threads);
    }
    @Override public void loadThread(ThreadId id, java.util.function.Consumer<String> completed) {
      loadedThreads.add(id);
      if (threadFailure.isEmpty()) {
        state.set(AgentState.initial(new com.github.skanga.ajent.domain.Thread(id, "", List.of())));
      }
      completed.accept(threadFailure);
    }
    @Override public boolean windows() { return true; }
    @Override public void runCodeBlock(
        com.github.skanga.ajent.terminal.ui.CodeBlockPicker.Block block,
        java.util.function.Consumer<com.github.skanga.ajent.terminal.ui.CodeBlockPicker.Result> completed) {
      ranBlocks.add(block);
      completed.accept(new com.github.skanga.ajent.terminal.ui.CodeBlockPicker.Result(
          block.body(), runOutput, runExit, runTimedOut));
    }
    @Override public boolean checkpointsAvailable() { return checkpointsAvailable; }
    @Override public void loadCheckpointDiff(CheckpointId id,
        java.util.function.Consumer<Optional<int[]>> completed) {
      completed.accept(id.value().equals("cp1") ? Optional.empty()
          : Optional.of(checkpointClean ? new int[] {0, 0, 0} : new int[] {2, 3, 4}));
    }
    @Override public void restoreCheckpoint(CheckpointId id,
        java.util.function.Consumer<com.github.skanga.ajent.terminal.ui.CheckpointPicker.Restore> completed) {
      restoredCheckpoint = id;
      var result = checkpointFailure.isEmpty()
          ? new com.github.skanga.ajent.terminal.ui.CheckpointPicker.Restore(
              true, state.get().thread().messages().stream()
                  .filter(message -> message.checkpointId().filter(id::equals).isPresent())
                  .findFirst().orElseThrow().text(), "")
          : new com.github.skanga.ajent.terminal.ui.CheckpointPicker.Restore(
              false, "", checkpointFailure);
      if (deferCheckpointRestore) pendingCheckpointRestore = completed;
      else completed.accept(result);
    }
    private void completeCheckpointRestore() {
      deferCheckpointRestore = false;
      var receiver = pendingCheckpointRestore;
      pendingCheckpointRestore = null;
      receiver.accept(new com.github.skanga.ajent.terminal.ui.CheckpointPicker.Restore(
          true, "prompt", ""));
    }
    @Override public Profile cycleProfile() {
      profile = switch (profile) {
        case WRITE -> Profile.ASK;
        case ASK -> Profile.MINIMAL;
        case MINIMAL -> Profile.WRITE;
      };
      return profile;
    }
    @Override public String model() { return model; }
    @Override public void loadModels(
        java.util.function.Consumer<List<com.github.skanga.ajent.terminal.ui.ModelPicker.Model>> receiver) {
      if (deferModels) { pendingModels = receiver; return; }
      receiver.accept(modelRows());
    }
    private List<com.github.skanga.ajent.terminal.ui.ModelPicker.Model> modelRows() {
      return List.of(
          new com.github.skanga.ajent.terminal.ui.ModelPicker.Model("alpha", "Alpha", false),
          new com.github.skanga.ajent.terminal.ui.ModelPicker.Model("beta", "Beta", false));
    }
    private void completeModels() {
      deferModels = false;
      java.util.function.Consumer<List<com.github.skanga.ajent.terminal.ui.ModelPicker.Model>> next =
          pendingModels;
      pendingModels = null;
      next.accept(modelRows());
    }
    @Override public void selectModel(String value) { model = value; }
    @Override public void saveFavorites(List<String> values) { favorites = List.copyOf(values); }
    @Override public String provider() { return provider; }
    @Override public List<com.github.skanga.ajent.terminal.ui.ProviderPicker.Provider> providers() {
      return List.of(
          new com.github.skanga.ajent.terminal.ui.ProviderPicker.Provider("anthropic", "Anthropic"),
          new com.github.skanga.ajent.terminal.ui.ProviderPicker.Provider("ollama", "Ollama"));
    }
    @Override public boolean selectProvider(String value) {
      if (rejectProvider) return false;
      provider = value;
      return true;
    }
    @Override public LoginModal.OAuthAttempt newOAuthAttempt() {
      return new LoginModal.OAuthAttempt("verifier", "state",
          URI.create("https://example.test/authorize"));
    }
    @Override public void openBrowser(URI value) { browser = value; }
    @Override public boolean installAnthropicKey(String key) {
      if (failAnthropicKey) return false;
      anthropicKey = key;
      return true;
    }
    @Override public boolean installProviderKey(String provider, String key) {
      if (failProviderKey) return false;
      providerKey = key;
      this.provider = provider;
      return true;
    }
    @Override public boolean switchCustomHost(String specification) {
      if (failCustomHost) return false;
      provider = specification;
      return true;
    }
    @Override public void exchangeOAuth(LoginModal.ExchangeOAuth exchange,
        java.util.function.Consumer<String> completed) {
      oauthCode = exchange.code();
      if (deferOAuth) pendingOAuth = completed;
      else completed.accept("");
    }
    private void completeOAuth(String failure) {
      deferOAuth = false;
      java.util.function.Consumer<String> next = pendingOAuth;
      pendingOAuth = null;
      next.accept(failure);
    }
    @Override public List<com.github.skanga.ajent.terminal.ui.DiffReview.File> pendingChanges() {
      return changes;
    }
    @Override public void clearPendingChanges() { changes = List.of(); }
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
