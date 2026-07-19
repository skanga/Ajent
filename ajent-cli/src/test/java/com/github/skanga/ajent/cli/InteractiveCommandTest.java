package com.github.skanga.ajent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.ActiveTurn;
import com.github.skanga.ajent.domain.Attachment;
import com.github.skanga.ajent.domain.AttachmentText;
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
import com.github.skanga.ajent.core.persistence.SettingsStore;
import com.github.skanga.ajent.core.persistence.Settings;
import com.github.skanga.ajent.domain.ModelId;
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
import java.util.Set;
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
    assertThat(defaults.profile()).isEqualTo(Profile.WRITE);
    assertThat(defaults.model()).isEqualTo("local");
    assertThat(defaults.providerConfiguration().provider()).isEqualTo("ollama");

    var invalidDocs = command(Map.of("AGENTTY_DOCS_DIR", "\0"));
    assertThat(invalidDocs.configure(CliArguments.parse(new String[] {
        "--sandbox", "off", "--provider", "ollama"}), stream(error))).isNotNull();
    Path knowledgeWorkspace = directory.resolve("knowledge-workspace");
    java.nio.file.Files.createDirectories(knowledgeWorkspace.resolve(".agentty/knowledge"));
    assertThat(command(Map.of()).configure(CliArguments.parse(new String[] {
        "--sandbox", "off", "--provider", "ollama", "--workspace",
        knowledgeWorkspace.toString()}), stream(error))).isNotNull();
    new CredentialStore(directory.resolve("credentials.json"), "seed").save(
        new com.github.skanga.ajent.provider.auth.Credential.OAuth(
            "access", "refresh", System.currentTimeMillis() + 60_000));
    assertThat(command(Map.of()).configure(CliArguments.parse(new String[] {
        "--sandbox", "off"}), stream(error))).isNotNull();
  }

  @Test void interactiveStartupRehydratesProfileAndAlwaysAllowedTools() {
    var store = new SettingsStore(directory.resolve(".agentty"));
    assertThat(store.save(new Settings(new ModelId("local"), Profile.MINIMAL, List.of(),
        "ollama", Map.of(), Map.of("ollama", "local"), "", List.of("bash"))))
        .isTrue();

    var configured = command(Map.of()).configure(CliArguments.parse(new String[] {
        "--sandbox", "off"}), stream(new ByteArrayOutputStream()));

    assertThat(configured).isNotNull();
    assertThat(configured.profile()).isEqualTo(Profile.MINIMAL);
    var thread = new com.github.skanga.ajent.domain.Thread(
        new ThreadId("saved-grants"), "", List.of());
    try (var loop = configured.sessions().create(thread, configured.profile(), configured.model(),
        call -> new PermissionPort.Decision(true, false), (message, state) -> {})) {
      assertThat(loop.state().sessionGrants()).containsExactly("bash");
    }

    var granted = AgentState.initial(thread, Set.of("write", "bash"));
    InteractiveCommand.persistPermissionGrant(
        new RuntimeMessage.ProfileChanged(Profile.MINIMAL), granted, store);
    InteractiveCommand.persistPermissionGrant(
        new RuntimeMessage.PermissionResolved("call", false, true), granted, store);
    InteractiveCommand.persistPermissionGrant(
        new RuntimeMessage.PermissionResolved("call", true, false), granted, store);
    assertThat(store.load().alwaysAllowTools()).containsExactly("bash");

    InteractiveCommand.persistPermissionGrant(
        new RuntimeMessage.PermissionResolved("call", true, true), granted, store);
    assertThat(store.load().alwaysAllowTools()).containsExactly("bash", "write");
    InteractiveCommand.persistAlwaysAllowGrants(store, Set.of("write", "bash"));
    assertThat(store.load().alwaysAllowTools()).containsExactly("bash", "write");

    InteractiveCommand.persistProfile(store, Profile.ASK);
    assertThat(store.load()).satisfies(saved -> {
      assertThat(saved.profile()).isEqualTo(Profile.ASK);
      assertThat(saved.alwaysAllowTools()).isEmpty();
    });
  }

  @Test void interactiveStartupPersistsCliModelAndProviderWithPerProviderRecall() {
    var store = new SettingsStore(directory.resolve(".agentty"));
    assertThat(store.save(store.load()
        .withProviderModel("anthropic", new ModelId("claude-opus-4-5"))
        .withProviderModel("ollama", new ModelId("qwen2.5-coder:7b"))
        .withProvider("anthropic"))).isTrue();
    var error = new ByteArrayOutputStream();
    var command = command(Map.of());

    var recalled = command.configure(CliArguments.parse(new String[] {
        "--sandbox", "off", "--provider", "ollama"}), stream(error));
    assertThat(recalled).isNotNull();
    assertThat(recalled.providerConfiguration().provider()).isEqualTo("ollama");
    assertThat(recalled.model()).isEqualTo("qwen2.5-coder:7b");
    assertThat(store.load()).satisfies(saved -> {
      assertThat(saved.provider()).isEqualTo("ollama");
      assertThat(saved.modelId().value()).isEqualTo("qwen2.5-coder:7b");
    });

    var explicit = command.configure(CliArguments.parse(new String[] {
        "--sandbox", "off", "--provider", "groq", "--model", "llama-3.3-70b"}),
        stream(error));
    assertThat(explicit).isNotNull();
    assertThat(store.load()).satisfies(saved -> {
      assertThat(saved.provider()).isEqualTo("groq");
      assertThat(saved.modelId().value()).isEqualTo("llama-3.3-70b");
      assertThat(saved.providerModels()).containsEntry("groq", "llama-3.3-70b");
    });

    command.configure(CliArguments.parse(new String[] {
        "--sandbox", "off", "--model", "llama-3.1-8b"}), stream(error));
    assertThat(store.load()).satisfies(saved -> {
      assertThat(saved.provider()).isEqualTo("groq");
      assertThat(saved.modelId().value()).isEqualTo("llama-3.1-8b");
    });
  }

  @Test void composerRoutesEditingSubmissionCancellationAndQuit() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var gate = new InteractiveCommand.PermissionGate();
    var ui = new InteractiveCommand.Ui(terminal, state, gate);
    var agent = new FakeAgent(state);
    ui.render();
    assertThat(terminal.bytes.toString()).contains(
        "a calm middleware between you and the mo", "^C", "Ready", "Anthropic");

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
    assertThat(ui.key(character('d', true), agent)).isTrue();

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

  @Test void liveAppChromeShowsProviderContextAndPendingChanges() {
    AgentState base = AgentState.initial(thread(List.of()));
    AgentState state = new AgentState(base.thread(), base.phase(), base.activeTurnId(),
        base.turnCounter(), 37_500, 900, base.lastTickNanos(), "", base.toolDraft(),
        base.queued(), base.compaction(), base.oauthRefreshInFlight(), base.truncatedToolIds(),
        base.sessionGrants());
    var terminal = new FakeTerminal();
    terminal.size = new JLineTerminalSession.Size(100, 40);
    var changes = List.of(new com.github.skanga.ajent.tools.runtime.FileChange(
        "src/Main.java", 12, 3, "before", "after"));
    var ui = new InteractiveCommand.Ui(terminal, new AtomicReference<>(state),
        new InteractiveCommand.PermissionGate(), new ManualAnimation(), Map.of(), Profile.WRITE,
        "claude-opus-4-6", "anthropic", 200_000, () -> changes);

    ui.render();

    assertThat(terminal.bytes.toString()).contains(
        "NEW HERE? TRY ONE OF THESE", "Changes (1 files)", "M src/Main.java  +12 -3",
        "Anthropic", "ctx", "19%");
  }

  @Test void bracketedTextPasteAlwaysBecomesOneNormalizedAttachment() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);

    ui.key(character('a'), agent);
    ui.key(character('b'), agent);
    ui.key(special(TerminalKey.SpecialKey.LEFT), agent);
    ui.paste("one\r\ntwo\rthree\n");
    assertThat(terminal.bytes.toString())
        .contains("[Pasted text \u00b7 3 lines \u00b7 14 B]");
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);

    RuntimeMessage.Submit submit = (RuntimeMessage.Submit) agent.messages.getLast();
    assertThat(submit.text()).isEqualTo("a\u0001ATT:0\u0001b");
    assertThat(submit.attachments()).singleElement().satisfies(attachment -> {
      assertThat(attachment.kind()).isEqualTo(
          com.github.skanga.ajent.domain.Attachment.Kind.PASTE);
      assertThat(attachment.body()).asString(java.nio.charset.StandardCharsets.UTF_8)
          .isEqualTo("one\ntwo\nthree\n");
      assertThat(attachment.lineCount()).isEqualTo(3);
      assertThat(attachment.byteCount()).isEqualTo(14);
    });

    ui.paste("hello");
    assertThat(terminal.bytes.toString()).contains("[Pasted: hello]");
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(((RuntimeMessage.Submit) agent.messages.getLast()).attachments())
        .singleElement().satisfies(attachment ->
            assertThat(attachment.kind()).isEqualTo(
                com.github.skanga.ajent.domain.Attachment.Kind.PASTE));
  }

  @Test void rawAndPathImagePastesBecomeBinaryImageAttachments() throws Exception {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1};

    ui.paste(png);
    assertThat(terminal.bytes.toString())
        .contains("[Image \u00b7 <paste> \u00b7 image/png \u00b7 9 B]");
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    RuntimeMessage.Submit raw = (RuntimeMessage.Submit) agent.messages.getLast();
    assertThat(raw.attachments()).singleElement().satisfies(image -> {
      assertThat(image.kind()).isEqualTo(
          com.github.skanga.ajent.domain.Attachment.Kind.IMAGE);
      assertThat(image.body()).containsExactly(png);
      assertThat(image.path()).isEqualTo("<paste>");
      assertThat(image.mediaType()).isEqualTo("image/png");
    });

    Path file = directory.resolve("screen.png");
    java.nio.file.Files.write(file, png);
    ui.paste("\"" + file.toString().replace('\\', '/') + "\"");
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    RuntimeMessage.Submit path = (RuntimeMessage.Submit) agent.messages.getLast();
    assertThat(path.attachments()).singleElement().satisfies(image -> {
      assertThat(image.kind()).isEqualTo(
          com.github.skanga.ajent.domain.Attachment.Kind.IMAGE);
      assertThat(image.body()).containsExactly(png);
      assertThat(image.path()).endsWith("screen.png");
    });
  }

  @Test void smartPasteShortcutsPreferClipboardImageThenFallBackToText() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
    var image = new com.github.skanga.ajent.domain.Attachment(
        com.github.skanga.ajent.domain.Attachment.Kind.IMAGE, png, "<clipboard>",
        "image/png", "", 0, 0, png.length);
    var imageReads = new java.util.concurrent.atomic.AtomicInteger();
    var textReads = new java.util.concurrent.atomic.AtomicInteger();
    var imageClipboard = new com.github.skanga.ajent.tools.attachment.ClipboardReader() {
      @Override public Optional<com.github.skanga.ajent.domain.Attachment> image() {
        imageReads.incrementAndGet();
        return Optional.of(image);
      }
      @Override public Optional<String> text() { textReads.incrementAndGet(); return Optional.of("ignored"); }
    };
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate(), new ManualAnimation(), Map.of(), imageClipboard);
    var agent = new FakeAgent(state);

    ui.key(character('v', true), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(imageReads).hasValue(1);
    assertThat(textReads).hasValue(0);
    assertThat(((RuntimeMessage.Submit) agent.messages.getLast()).attachments())
        .singleElement().isEqualTo(image);

    var textClipboard = new com.github.skanga.ajent.tools.attachment.ClipboardReader() {
      @Override public Optional<com.github.skanga.ajent.domain.Attachment> image() {
        return Optional.empty();
      }
      @Override public Optional<String> text() { return Optional.of("one\r\ntwo"); }
    };
    var textUi = new InteractiveCommand.Ui(new FakeTerminal(), state,
        new InteractiveCommand.PermissionGate(), new ManualAnimation(), Map.of(), textClipboard);
    var textAgent = new FakeAgent(state);
    TerminalKey altV = new TerminalKey(new TerminalKey.CharacterKey('V'),
        new TerminalKey.Modifiers(false, true, false));
    textUi.key(altV, textAgent);
    textUi.key(special(TerminalKey.SpecialKey.ENTER), textAgent);
    RuntimeMessage.Submit text = (RuntimeMessage.Submit) textAgent.messages.getLast();
    assertThat(text.attachments()).singleElement().satisfies(paste ->
        assertThat(paste.body()).asString(StandardCharsets.UTF_8).isEqualTo("one\ntwo"));

    var emptyUi = new InteractiveCommand.Ui(new FakeTerminal(), state,
        new InteractiveCommand.PermissionGate(), new ManualAnimation(), Map.of(), textClipboard);
    var emptyAgent = new FakeAgent(state);
    emptyUi.paste(new byte[0]);
    emptyUi.key(special(TerminalKey.SpecialKey.ENTER), emptyAgent);
    assertThat(((RuntimeMessage.Submit) emptyAgent.messages.getLast()).attachments())
        .hasSize(1);

    var unavailable = new com.github.skanga.ajent.tools.attachment.ClipboardReader() {
      @Override public Optional<com.github.skanga.ajent.domain.Attachment> image() {
        return Optional.empty();
      }
      @Override public Optional<String> text() { return Optional.empty(); }
    };
    var queryTerminal = new FakeTerminal();
    var queryUi = new InteractiveCommand.Ui(queryTerminal, state,
        new InteractiveCommand.PermissionGate(), new ManualAnimation(),
        Map.of("TERM", "xterm-kitty"), unavailable);
    var queryAgent = new FakeAgent(state);
    queryUi.key(character(22, true), queryAgent);
    assertThat(queryTerminal.bytes.toString())
        .contains(com.github.skanga.ajent.terminal.input.TerminalClipboardQuery.OSC_5522_KITTY)
        .contains("reading clipboard from your terminal");
    assertThat(queryAgent.messages).isEmpty();
    queryUi.paste(png); // decoded OSC 5522 reply re-enters the ordinary paste path
    queryUi.key(special(TerminalKey.SpecialKey.ENTER), queryAgent);
    assertThat(((RuntimeMessage.Submit) queryAgent.messages.getLast()).attachments())
        .singleElement().satisfies(attachment ->
            assertThat(attachment.kind()).isEqualTo(
                com.github.skanga.ajent.domain.Attachment.Kind.IMAGE));
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
    assertThat(ui.key(character('d', true), agent)).isTrue();
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
    try (var local = new InteractiveCommand.FrameScheduler(Map.of("MAYA_FORCE_SYNC", "1"));
         var ssh = new InteractiveCommand.FrameScheduler(Map.of(
             "MAYA_FORCE_SYNC", "1", "SSH_CONNECTION", "remote"))) {
      assertThat(local.delayMillis()).isEqualTo(33);
      assertThat(ssh.delayMillis()).isEqualTo(80);
    }
  }

  @Test void synchronizedTerminalsReceiveAtomicInteractiveFrames() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate(), new InteractiveCommand.AnimationPort() {
          @Override public long nowNanos() { return 1; }
          @Override public void request(Runnable frame) { }
        }, Map.of("MAYA_FORCE_SYNC", "1"));

    ui.render();

    assertThat(terminal.bytes.toString()).contains("\u001b[?2026h", "\u001b[?2026l");
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

  @Test void nativeGlobalShortcutsOpenTheirProductionSurfaces() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);

    ui.key(character('/', true), agent);
    assertThat(terminal.bytes.toString()).contains("Models");
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);

    ui.key(character('p', true), agent);
    assertThat(terminal.bytes.toString()).contains("Providers");
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);

    ui.key(character('r', true), agent);
    assertThat(terminal.bytes.toString()).contains("no pending changes to review");

    ui.key(character('n', true), agent);
    assertThat(agent.newThreads).isEqualTo(1);

    ui.key(special(TerminalKey.SpecialKey.BACK_TAB, false, false, true), agent);
    assertThat(agent.profile).isEqualTo(Profile.MINIMAL);
    assertThat(terminal.bytes.toString()).contains("profile: minimal");

    ui.key(character('l', true), agent);
    assertThat(terminal.bytes.toString()).contains("\u001b[2J\u001b[3J\u001b[H");
  }

  @Test void slashOpensCommandsOnlyFromAnEmptyComposer() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);

    ui.key(character('/'), agent);
    assertThat(terminal.bytes.toString()).contains("Commands");
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);

    ui.insert("https:");
    ui.key(character('/'), agent);
    ui.key(character('/'), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(agent.messages.getLast()).isEqualTo(
        new RuntimeMessage.Submit("https://", List.of()));
  }

  @Test void nativeComposerWordEditingAndUndoRedoAreLive() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var ui = new InteractiveCommand.Ui(new FakeTerminal(), state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    ui.insert("one two three");

    ui.key(character('w', true), agent);
    ui.key(character('z', true), agent);
    ui.key(character('y', true), agent);
    ui.key(character('z', true), agent);
    ui.key(special(TerminalKey.SpecialKey.HOME), agent);
    ui.key(new TerminalKey(new TerminalKey.CharacterKey('d'),
        new TerminalKey.Modifiers(false, true, false)), agent);
    ui.key(character('z', true), agent);
    ui.key(special(TerminalKey.SpecialKey.END), agent);
    ui.key(special(TerminalKey.SpecialKey.LEFT, true, false, false), agent);
    ui.key(character('w', true), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);

    assertThat(((RuntimeMessage.Submit) agent.messages.getLast()).text())
        .isEqualTo("one three");
  }

  @Test void lineKillAndControlShiftZMatchNativeMultilineEditing() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var ui = new InteractiveCommand.Ui(new FakeTerminal(), state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    ui.insert("one\ntwo");

    ui.key(character('u', true), agent);
    ui.key(character('x'), agent);
    ui.key(character('z', true), agent);
    ui.key(new TerminalKey(new TerminalKey.CharacterKey('z'),
        new TerminalKey.Modifiers(true, false, true)), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);

    assertThat(agent.messages.getLast()).isEqualTo(
        new RuntimeMessage.Submit("one\nx", List.of()));
  }

  @Test void upRecallsTheWholeQueuedTurnSetAndRemapsAttachmentChips() {
    var firstAttachment = attachment("first body");
    var secondAttachment = attachment("second body");
    var first = new RuntimeMessage.Submit(AttachmentText.placeholder(0), List.of(),
        List.of(firstAttachment));
    var second = new RuntimeMessage.Submit("second " + AttachmentText.placeholder(0), List.of(),
        List.of(secondAttachment));
    AgentState initial = withQueued(AgentState.initial(thread(List.of())), List.of(first, second));
    var state = new AtomicReference<>(withPhase(initial, new SessionPhase.Streaming(
        ActiveTurn.start(new CancellationSignal(), 1))));
    var ui = new InteractiveCommand.Ui(new FakeTerminal(), state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);

    ui.key(special(TerminalKey.SpecialKey.UP), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);

    assertThat(agent.messages.getFirst())
        .isEqualTo(new RuntimeMessage.ReplaceQueued(List.of()));
    assertThat(agent.messages.getLast()).isInstanceOfSatisfying(
        RuntimeMessage.Submit.class, submit -> {
          assertThat(submit.text()).isEqualTo(
              AttachmentText.placeholder(0) + "\nsecond " + AttachmentText.placeholder(1));
          assertThat(submit.attachments()).containsExactly(firstAttachment, secondAttachment);
        });

    var popState = new AtomicReference<>(withQueued(AgentState.initial(thread(List.of())),
        List.of(first, second)));
    var popUi = new InteractiveCommand.Ui(new FakeTerminal(), popState,
        new InteractiveCommand.PermissionGate());
    var popAgent = new FakeAgent(popState);
    popUi.key(special(TerminalKey.SpecialKey.BACKSPACE, false, true, false), popAgent);
    assertThat(popAgent.messages).containsExactly(
        new RuntimeMessage.ReplaceQueued(List.of(first)));
  }

  @Test void arrowsWalkCurrentThreadUserHistoryWithAttachmentsAndEditingEndsTheWalk() {
    Attachment oldAttachment = attachment("old body");
    Attachment recentAttachment = attachment("recent body");
    Message old = userMessage("old " + AttachmentText.placeholder(0), oldAttachment);
    Message recent = userMessage("recent " + AttachmentText.placeholder(0), recentAttachment);
    var state = new AtomicReference<>(AgentState.initial(thread(List.of(
        old, new Message(Role.ASSISTANT, "reply", List.of(), List.of()), recent))));
    var ui = new InteractiveCommand.Ui(new FakeTerminal(), state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);

    ui.key(special(TerminalKey.SpecialKey.UP), agent);
    ui.key(special(TerminalKey.SpecialKey.UP), agent);
    ui.key(special(TerminalKey.SpecialKey.UP), agent); // clamps at the oldest turn
    ui.key(special(TerminalKey.SpecialKey.DOWN), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);

    assertThat(agent.messages.getLast()).isInstanceOfSatisfying(
        RuntimeMessage.Submit.class, submit -> {
          assertThat(submit.text()).isEqualTo(recent.text());
          assertThat(submit.attachments()).containsExactly(recentAttachment);
        });

    var editingUi = new InteractiveCommand.Ui(new FakeTerminal(), state,
        new InteractiveCommand.PermissionGate());
    var editingAgent = new FakeAgent(state);
    editingUi.key(special(TerminalKey.SpecialKey.UP), editingAgent);
    editingUi.key(character('!'), editingAgent);
    editingUi.key(special(TerminalKey.SpecialKey.DOWN), editingAgent);
    editingUi.key(special(TerminalKey.SpecialKey.ENTER), editingAgent);
    assertThat(editingAgent.messages.getLast()).isInstanceOfSatisfying(
        RuntimeMessage.Submit.class, submit -> assertThat(submit.text())
            .isEqualTo(recent.text() + "!"));

    var roundTripUi = new InteractiveCommand.Ui(new FakeTerminal(), state,
        new InteractiveCommand.PermissionGate());
    var roundTripAgent = new FakeAgent(state);
    roundTripUi.key(special(TerminalKey.SpecialKey.UP), roundTripAgent);
    roundTripUi.key(special(TerminalKey.SpecialKey.DOWN), roundTripAgent);
    roundTripUi.key(special(TerminalKey.SpecialKey.ENTER), roundTripAgent);
    assertThat(roundTripAgent.messages).isEmpty();
  }

  @Test void altArrowsEditQueuedSlotsAndRestoreTheLiveDraft() {
    RuntimeMessage.Submit first = new RuntimeMessage.Submit("first", List.of());
    RuntimeMessage.Submit second = new RuntimeMessage.Submit("second", List.of());
    var state = new AtomicReference<>(withQueued(AgentState.initial(thread(List.of())),
        List.of(first, second)));
    var ui = new InteractiveCommand.Ui(new FakeTerminal(), state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    ui.insert("draft");

    ui.key(special(TerminalKey.SpecialKey.UP, false, true, false), agent);
    ui.key(character('!'), agent);
    ui.key(special(TerminalKey.SpecialKey.UP, false, true, false), agent);
    ui.key(character('?'), agent);
    ui.key(special(TerminalKey.SpecialKey.DOWN, false, true, false), agent);
    ui.key(special(TerminalKey.SpecialKey.DOWN, false, true, false), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);

    assertThat(agent.messages).contains(
        new RuntimeMessage.ReplaceQueued(List.of(first,
            new RuntimeMessage.Submit("second!", List.of()))),
        new RuntimeMessage.ReplaceQueued(List.of(
            new RuntimeMessage.Submit("first?", List.of()),
            new RuntimeMessage.Submit("second!", List.of()))));
    assertThat(agent.messages.getLast()).isEqualTo(
        new RuntimeMessage.Submit("draft", List.of()));
  }

  @Test void submittingAQueuePeekRemovesItsOriginalSlot() {
    RuntimeMessage.Submit first = new RuntimeMessage.Submit("first", List.of());
    Attachment secondAttachment = attachment("second body");
    RuntimeMessage.Submit second = new RuntimeMessage.Submit(
        "second " + AttachmentText.placeholder(0), List.of(), List.of(secondAttachment));
    var state = new AtomicReference<>(withQueued(AgentState.initial(thread(List.of())),
        List.of(first, second)));
    var ui = new InteractiveCommand.Ui(new FakeTerminal(), state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);

    ui.key(special(TerminalKey.SpecialKey.UP, false, true, false), agent);
    ui.key(character('!'), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);

    assertThat(agent.messages).containsExactly(
        new RuntimeMessage.ReplaceQueued(List.of(first)),
        new RuntimeMessage.Submit("second " + AttachmentText.placeholder(0) + "!", List.of(),
            List.of(secondAttachment)));
  }

  @Test void queuedTurnsRenderAsUserPreviewsAndMarkThePeekedSlot() {
    Attachment attachment = attachment("queued body");
    RuntimeMessage.Submit first = new RuntimeMessage.Submit(
        "inspect " + AttachmentText.placeholder(0), List.of(), List.of(attachment));
    RuntimeMessage.Submit second = new RuntimeMessage.Submit("then test", List.of());
    var state = new AtomicReference<>(withQueued(AgentState.initial(thread(List.of())),
        List.of(first, second)));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);

    ui.render();
    assertThat(terminal.bytes.toString()).contains(
        "queued #1 / 2", "inspect [Pasted: queued body]", "queued #2 / 2", "then test");

    ui.key(special(TerminalKey.SpecialKey.UP, false, true, false), agent);
    assertThat(terminal.bytes.toString()).contains("✎ editing — queued #2 / 2");
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

  @Test void modelPickerCyclesAndRendersNativeReasoningEffortLadder() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    agent.model = "claude-opus-4-7";
    agent.availableModels = List.of(
        new com.github.skanga.ajent.terminal.ui.ModelPicker.Model(
            "claude-opus-4-7", "Claude Opus 4.7", false),
        new com.github.skanga.ajent.terminal.ui.ModelPicker.Model("gpt-5", "GPT 5", false));

    ui.key(character('k', true), agent);
    for (int codePoint : "open model".codePoints().toArray()) ui.key(character(codePoint), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(terminal.bytes.toString()).contains("reasoning effort: off", "\u2190/\u2192 change");
    ui.key(special(TerminalKey.SpecialKey.RIGHT), agent);
    assertThat(agent.effort).isEqualTo(com.github.skanga.ajent.domain.Effort.LOW);
    assertThat(terminal.bytes.toString()).contains("\u25c7 low", "reasoning effort: low");
    ui.key(special(TerminalKey.SpecialKey.LEFT), agent);
    assertThat(agent.effort).isEqualTo(com.github.skanga.ajent.domain.Effort.NONE);
    ui.key(special(TerminalKey.SpecialKey.DOWN), agent);
    ui.key(special(TerminalKey.SpecialKey.RIGHT), agent);
    assertThat(agent.effort).isEqualTo(com.github.skanga.ajent.domain.Effort.NONE);
  }

  @Test void mentionAndSymbolPickersInsertCompactTypedAttachmentsAtWordBoundaries() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    agent.workspaceFiles = List.of("README.md", "src/A.java", "src/B.java");
    agent.workspaceSymbols = List.of(
        new com.github.skanga.ajent.core.workspace.WorkspaceSymbol("Alpha", "src/A.java", 7),
        new com.github.skanga.ajent.core.workspace.WorkspaceSymbol("work", "src/B.java", 12));

    ui.key(character('@'), agent);
    assertThat(terminal.bytes.toString()).contains("Mention File", "type to filter files");
    ui.key(character('a'), agent);
    ui.key(special(TerminalKey.SpecialKey.UP), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(terminal.bytes.toString()).contains("[@A.java]");
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    RuntimeMessage.Submit fileSubmit = (RuntimeMessage.Submit) agent.messages.getLast();
    assertThat(fileSubmit.text()).isEqualTo("\u0001ATT:0\u0001");
    assertThat(fileSubmit.attachments()).singleElement().satisfies(attachment -> {
      assertThat(attachment.kind()).isEqualTo(com.github.skanga.ajent.domain.Attachment.Kind.FILE_REF);
      assertThat(attachment.path()).isEqualTo("src/A.java");
      assertThat(attachment.body()).isEmpty();
    });

    ui.key(character('#'), agent);
    ui.key(character('w'), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(terminal.bytes.toString()).contains("[#work \u00b7 B.java:12]");
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    RuntimeMessage.Submit symbolSubmit = (RuntimeMessage.Submit) agent.messages.getLast();
    assertThat(symbolSubmit.attachments()).singleElement().satisfies(attachment -> {
      assertThat(attachment.kind()).isEqualTo(com.github.skanga.ajent.domain.Attachment.Kind.SYMBOL);
      assertThat(attachment.name()).isEqualTo("work");
      assertThat(attachment.lineNumber()).isEqualTo(12);
    });

    ui.key(character('x'), agent);
    ui.key(character('@'), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(((RuntimeMessage.Submit) agent.messages.getLast()).text()).isEqualTo("x@");
  }

  @Test void workspacePickersRenderEmptyAndNoMatchStates() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);

    ui.key(character('#'), agent);
    assertThat(terminal.bytes.toString()).contains("no symbols indexed");
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);

    agent.workspaceFiles = List.of("README.md", "src/Main.java");
    ui.key(character('@'), agent);
    ui.key(character('z'), agent);
    assertThat(terminal.bytes.toString()).contains("no matches");
  }

  @Test void emptyMentionBackspaceClosesWithoutLeakingTheTrigger() {
    var state = new AtomicReference<>(AgentState.initial(thread(List.of())));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    var agent = new FakeAgent(state);
    ui.key(character('@'), agent);
    ui.key(special(TerminalKey.SpecialKey.BACKSPACE), agent);
    ui.key(character('x'), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(((RuntimeMessage.Submit) agent.messages.getLast()).text()).isEqualTo("x");
  }

  @Test void liveDiffReviewRoutesExactNativeHunkAndBulkKeys() {
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
    ui.key(character('y'), agent);
    ui.key(special(TerminalKey.SpecialKey.RIGHT), agent);
    ui.key(character('n'), agent);
    assertThat(agent.changes.get(0).hunks().getFirst().status())
        .isEqualTo(com.github.skanga.ajent.terminal.ui.DiffReview.Status.ACCEPTED);
    assertThat(agent.changes.get(1).hunks().getFirst().status())
        .isEqualTo(com.github.skanga.ajent.terminal.ui.DiffReview.Status.REJECTED);
    ui.key(special(TerminalKey.SpecialKey.LEFT), agent);
    ui.key(special(TerminalKey.SpecialKey.ESCAPE), agent);
    assertThat(terminal.bytes.toString()).contains(
        "hanges  a.txt", "[y] accept", "[n] reject", "@@ -1,0 +1,0 @@");

    selectCommand(ui, agent, "review changes");
    ui.key(character('a'), agent);
    assertThat(terminal.bytes.toString()).contains("accepted 2 hunks");
    assertThat(agent.changes).allSatisfy(file -> assertThat(file.hunks())
        .allSatisfy(hunk -> assertThat(hunk.status())
            .isEqualTo(com.github.skanga.ajent.terminal.ui.DiffReview.Status.ACCEPTED)));
    ui.key(character('x'), agent);
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
    var notifications = new java.util.concurrent.atomic.AtomicInteger();
    ledger.onChange(items -> { notifications.incrementAndGet(); observed.set(items); });
    assertThat(observed.get()).isEmpty();
    var source = new ArrayList<com.github.skanga.ajent.tools.host.HostServices.TodoItem>();
    source.add(new com.github.skanga.ajent.tools.host.HostServices.TodoItem("one", "completed"));
    source.add(new com.github.skanga.ajent.tools.host.HostServices.TodoItem("two", "future"));
    ledger.set(source);
    ledger.set(List.copyOf(source));
    source.clear();
    assertThat(observed.get()).extracting(
        com.github.skanga.ajent.terminal.ui.PlanModal.Item::status).containsExactly(
            com.github.skanga.ajent.terminal.ui.PlanModal.Status.COMPLETED,
            com.github.skanga.ajent.terminal.ui.PlanModal.Status.PENDING);
    assertThat(notifications).hasValue(2);

    var todo = new ToolUse(new ToolCallId("live"), new ToolName("todo"), Map.of(
        "todos", List.of("not an object", Map.of("status", "completed"),
            Map.of("content", "streaming", "status", "in_progress"),
            Map.of("content", "queued"))), new ToolStatus.Pending(1));
    var liveThread = thread(List.of(new Message(Role.ASSISTANT, "", List.of(), List.of(todo))));
    AgentState base = AgentState.initial(liveThread);
    var live = new AgentState(base.thread(), base.phase(), base.activeTurnId(), base.turnCounter(),
        base.tokensIn(), base.tokensOut(), base.lastTickNanos(), base.status(),
        Optional.of(new AgentState.ToolDraft("live", "{}")), base.queued(), base.compaction(),
        base.oauthRefreshInFlight(), base.truncatedToolIds(), base.sessionGrants());
    assertThat(InteractiveCommand.liveTodoItems(live)).contains(List.of(
        new com.github.skanga.ajent.tools.host.HostServices.TodoItem(
            "streaming", "in_progress"),
        new com.github.skanga.ajent.tools.host.HostServices.TodoItem("queued", "pending")));
    assertThat(InteractiveCommand.liveTodoItems(base)).isEmpty();
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
        "Run Code Block", "1  echo one", "python · 1 line");
    ui.key(special(TerminalKey.SpecialKey.DOWN), agent);
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(terminal.bytes.toString()).contains(
        "isn't runnable here — press e to edit or y to copy");
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
    assertThat(terminal.bytes.toString())
        .contains("[Output: echo one", "1 lines", "8 B]");
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    RuntimeMessage.Submit submit = (RuntimeMessage.Submit) agent.messages.getLast();
    assertThat(submit.text()).isEqualTo("\u0001ATT:0\u0001");
    assertThat(submit.attachments()).singleElement().satisfies(attachment -> {
      assertThat(attachment.kind()).isEqualTo(com.github.skanga.ajent.domain.Attachment.Kind.OUTPUT);
      assertThat(attachment.name()).isEqualTo("echo one");
      assertThat(new String(attachment.body(), java.nio.charset.StandardCharsets.UTF_8))
          .isEqualTo("captured");
    });

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
    assertThat(terminal.bytes.toString()).contains("cannot rewind while the agent is wor");
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
    assertThat(terminal.bytes.toString()).contains("cannot rewind while the agent is wor");
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
    assertThat(terminal.bytes.toString()).contains("sh · 2 lines");
    ui.key(special(TerminalKey.SpecialKey.ENTER), agent);
    assertThat(terminal.bytes.toString()).contains("echo a …", "exit 1");
    ui.key(character('a'), agent);
  }

  private static Message checkpointMessage(String id, String text) {
    return new Message(new com.github.skanga.ajent.domain.MessageId(id), Role.USER, text,
        List.of(), List.of(), "", "", List.of(), Instant.EPOCH,
        Optional.of(new CheckpointId(id)), Optional.empty(), false);
  }

  @Test void messagesUseNativeTurnHeadersMetadataAndLeftRail() {
    Instant userAt = Instant.parse("2026-07-19T12:34:00Z");
    Message user = new Message(new com.github.skanga.ajent.domain.MessageId("user"),
        Role.USER, "hello", List.of(), List.of(), "", "", List.of(), userAt,
        Optional.of(new CheckpointId("checkpoint")), Optional.empty(), false);
    Message assistant = new Message(new com.github.skanga.ajent.domain.MessageId("assistant"),
        Role.ASSISTANT, "response", List.of(), List.of(), "", "", List.of(),
        userAt.plusMillis(4_240), Optional.empty(), Optional.empty(), false);
    var state = new AtomicReference<>(AgentState.initial(thread(List.of(user, assistant))));
    var terminal = new FakeTerminal();
    terminal.size = new JLineTerminalSession.Size(100, 40);
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate(), new ManualAnimation(), Map.of(),
        Profile.WRITE, "claude-opus-4-6");

    ui.render();

    assertThat(ui.renderedText()).contains(
        "\u2503  \u276f You", "\u21ba checkpoint", "\u2503  hello",
        "\u2503  \u2726 Opus 4.6", "4.2s  \u00b7  turn 1", "\u2503  response");
  }

  @Test void turnBodySeparatesSlotsAndUsesNativeInlineErrorShape() {
    Message assistant = new Message(new com.github.skanga.ajent.domain.MessageId("assistant"),
        Role.ASSISTANT, "response", List.of(), List.of(), "", "", List.of(tool("failed")),
        Instant.now(), Optional.empty(), Optional.of("stream cut off unexpectedly"), false, false);
    var state = new AtomicReference<>(AgentState.initial(thread(List.of(assistant))));
    var terminal = new FakeTerminal();
    terminal.size = new JLineTerminalSession.Size(20, 40);
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate(), new ManualAnimation(), Map.of(),
        Profile.WRITE, "claude-opus-4-6");

    ui.render();

    assertThat(ui.renderedText()).contains(
        "\u2503  response\n\u2503  \n\u2503  \u256d A C T I O N S ",
        "\u2503  \n\u2503  \u26a0  stream cut off\n\u2503     unexpectedly");
  }

  @Test void toolBatchRendersAsNativeActionsTimelineInsideTurnRail() {
    ToolUse bash = new ToolUse(new ToolCallId("bash"), new ToolName("bash"),
        Map.of("command", "mvn test"),
        new ToolStatus.Failed(1_000_000_000, 1_500_000_000,
            "failed with exit code 1"));
    Message assistant = new Message(Role.ASSISTANT, "", List.of(), List.of(bash));
    var state = new AtomicReference<>(AgentState.initial(thread(List.of(assistant))));
    var terminal = new FakeTerminal();
    terminal.size = new JLineTerminalSession.Size(60, 40);
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate(), new ManualAnimation(), Map.of(),
        Profile.WRITE, "claude-opus-4-6");

    ui.render();

    assertThat(ui.renderedText()).contains(
        "\u2503  \u256d A C T I O N S ",
        "\u2503  \u2502 E X E C U T E 1",
        "\u2503  \u2502 \u2500\u2500 \u2717  Bash  mvn test",
        "\u2503  \u2502    \u2717 1   F A I L E D   1/1 action   500ms",
        "\u2503  \u2570");
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
    assertThat(terminal.bytes.toString()).contains("Threads", "● (untitled)", "2/3");
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
    ui.key(special(TerminalKey.SpecialKey.RIGHT, true, false, false), agent);
    assertThat(agent.loadedThreads).containsExactly(
        new ThreadId("old"), new ThreadId("thread"), new ThreadId("old"));
    ui.key(special(TerminalKey.SpecialKey.LEFT, true, false, false), agent);
    assertThat(agent.loadedThreads).containsExactly(
        new ThreadId("old"), new ThreadId("thread"), new ThreadId("old"),
        new ThreadId("thread"));
    ui.insert("one two");
    ui.key(special(TerminalKey.SpecialKey.LEFT, true, false, false), agent);
    assertThat(agent.loadedThreads).hasSize(4);
    assertThat(terminal.bytes.toString()).contains("thread 2/3 · (untitled)");
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
    String before = java.util.stream.IntStream.rangeClosed(1, 14)
        .mapToObj(index -> "line-" + index).collect(java.util.stream.Collectors.joining("\n"));
    String after = before.replace("line-2", "new-2").replace("line-12", "new-12");
    var change = com.github.skanga.ajent.tools.runtime.UnifiedDiff.compute(
        "file.txt", before, after);
    InteractiveCommand.recordChange(new RuntimeMessage.ToolCompleted(1, "call",
        new com.github.skanga.ajent.runtime.ToolCompletion.Success(
            "ok", Optional.of(change))), changes);
    assertThat(changes.get()).containsExactly(change);
    var review = InteractiveCommand.reviewFile(change);
    assertThat(review.hunks()).hasSize(2);
    assertThat(review.hunks().getFirst().patch()).contains("-line-2", "+new-2");
    assertThat(review.hunks().getFirst().header()).startsWith("@@ -1,");
    var accepted = review.withHunks(review.hunks().stream()
        .map(hunk -> hunk.withStatus(
            com.github.skanga.ajent.terminal.ui.DiffReview.Status.ACCEPTED)).toList());
    assertThat(InteractiveCommand.mergeReview(List.of(change), List.of(accepted)).getFirst()
        .hunks()).allSatisfy(hunk -> assertThat(hunk.status())
            .isEqualTo(com.github.skanga.ajent.tools.runtime.DiffHunk.Status.ACCEPTED));

    var mixedHunks = new ArrayList<>(change.hunks());
    mixedHunks.set(0, mixedHunks.getFirst().withStatus(
        com.github.skanga.ajent.tools.runtime.DiffHunk.Status.ACCEPTED));
    mixedHunks.set(1, mixedHunks.get(1).withStatus(
        com.github.skanga.ajent.tools.runtime.DiffHunk.Status.REJECTED));
    var mixed = change.withHunks(mixedHunks);
    assertThat(InteractiveCommand.reviewFile(mixed).hunks())
        .extracting(com.github.skanga.ajent.terminal.ui.DiffReview.Hunk::status)
        .containsExactly(
            com.github.skanga.ajent.terminal.ui.DiffReview.Status.ACCEPTED,
            com.github.skanga.ajent.terminal.ui.DiffReview.Status.REJECTED);

    var reviewed = InteractiveCommand.reviewFile(change);
    var pendingAndRejected = reviewed.withHunks(List.of(
        reviewed.hunks().getFirst().withStatus(
            com.github.skanga.ajent.terminal.ui.DiffReview.Status.PENDING),
        reviewed.hunks().get(1).withStatus(
            com.github.skanga.ajent.terminal.ui.DiffReview.Status.REJECTED)));
    assertThat(InteractiveCommand.mergeReview(List.of(change), List.of(pendingAndRejected))
        .getFirst().hunks()).extracting(com.github.skanga.ajent.tools.runtime.DiffHunk::status)
        .containsExactly(com.github.skanga.ajent.tools.runtime.DiffHunk.Status.PENDING,
            com.github.skanga.ajent.tools.runtime.DiffHunk.Status.REJECTED);
    assertThat(InteractiveCommand.mergeReview(List.of(change), List.of()))
        .containsExactly(change);
    assertThat(InteractiveCommand.mergeReview(List.of(change), List.of(
        reviewed.withHunks(List.of(reviewed.hunks().getFirst())))).getFirst().hunks())
        .hasSize(2);
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
    assertThat(terminal.bytes.toString()).contains("\u2726 Op", "erro");
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
    assertThat(terminal.bytes.toString()).contains("Read").contains("Ready");
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

  @Test void settledAssistantMarkdownRendersAsStyledTerminalContent() {
    String markdown = """
        # Heading

        **bold** value

        | Name | State |
        |------|-------|
        | Ajent | ready |
        """;
    AgentState state = AgentState.initial(thread(List.of(
        new Message(Role.ASSISTANT, markdown, List.of(), List.of()))));
    var terminal = new FakeTerminal();

    new InteractiveCommand.Ui(terminal, new AtomicReference<>(state),
        new InteractiveCommand.PermissionGate()).render();

    String wire = terminal.bytes.toString();
    assertThat(wire).contains("Heading", "bold", "Ajent", "ready", "\u250c", "\u252c")
        .doesNotContain("# Heading", "**bold**", "|------|");
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
    assertThat(terminal.bytes.toString()).contains("38;2;");
    animation.now = 20_000_000;
    animation.runFrame();
    assertThat(animation.requested).isEqualTo(2);

    state.set(withPhase(state.get(), new SessionPhase.Idle()));
    for (int index = 0; index < 50 && animation.frame != null; index++) {
      animation.now += 16_000_000;
      animation.runFrame();
    }
    // The inline renderer emits only changed cells, so the final suffix proves catch-up.
    assertThat(terminal.bytes.toString()).contains("ef");
    assertThat(animation.frame).isNull();
  }

  @Test void toolPanelWaitsForThePrecedingRevealBoundary() {
    String body = "A deliberately long assistant explanation keeps the reveal cursor behind "
        .repeat(30);
    Message assistant = new Message(Role.ASSISTANT, body, List.of(), List.of());
    SessionPhase phase = new SessionPhase.Streaming(
        ActiveTurn.start(new CancellationSignal(), 1));
    var state = new AtomicReference<>(withPhase(
        AgentState.initial(thread(List.of(assistant))), phase));
    var terminal = new FakeTerminal();
    var animation = new ManualAnimation();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate(), animation);

    ui.render();
    animation.now += 16_000_000;
    animation.runFrame();
    ToolUse boundary = new ToolUse(new ToolCallId("boundary"),
        new ToolName("boundary-tool"), Map.of(), new ToolStatus.Pending());
    state.set(withPhase(AgentState.initial(thread(List.of(
        assistant.withToolCalls(List.of(boundary))))),
        new SessionPhase.ExecutingTool(ActiveTurn.start(new CancellationSignal(), 1))));

    animation.now += 16_000_000;
    animation.runFrame();
    assertThat(terminal.bytes.toString()).doesNotContain("boundary-tool");

    for (int index = 0; index < 110
        && !terminal.bytes.toString().contains("boundary-tool"); index++) {
      animation.now += 16_000_000;
      animation.runFrame();
    }
    assertThat(terminal.bytes.toString()).contains("boundary-tool");
  }

  @Test void streamingAssistantMarkdownNeverExposesSourcePunctuation() {
    String markdown = """
        ## Live heading

        | Key | Value |
        |-----|-------|
        | one | two |
        """;
    Message assistant = new Message(Role.ASSISTANT, markdown, List.of(), List.of());
    AgentState streaming = withPhase(AgentState.initial(thread(List.of(assistant))),
        new SessionPhase.Streaming(ActiveTurn.start(new CancellationSignal(), 1)));
    var terminal = new FakeTerminal();
    var animation = new ManualAnimation();

    new InteractiveCommand.Ui(terminal, new AtomicReference<>(streaming),
        new InteractiveCommand.PermissionGate(), animation).render();

    assertThat(terminal.bytes.toString()).contains("┌", "┬")
        .doesNotContain("## Live heading", "|-----|", "| Key | Value |");
    assertThat(animation.requested).isOne();
  }

  @Test void deepRunLiveEdgeStaysArmedAcrossAQuietToolRoundTrip() {
    var messages = new ArrayList<Message>();
    messages.add(new Message(Role.USER, "do a long series of edits", List.of(), List.of()));
    for (int index = 0; index < 40; index++) {
      messages.add(new Message(Role.ASSISTANT, "settled sub-turn " + index,
          List.of(), List.of()));
    }
    messages.add(new Message(Role.ASSISTANT, "Now I will summarize the edits I performed",
        List.of(), List.of()));
    AgentState streaming = withPhase(AgentState.initial(thread(messages)),
        new SessionPhase.ExecutingTool(ActiveTurn.start(new CancellationSignal(), 1)));
    var terminal = new FakeTerminal();
    var animation = new ManualAnimation();
    var ui = new InteractiveCommand.Ui(terminal, new AtomicReference<>(streaming),
        new InteractiveCommand.PermissionGate(), animation);

    ui.render();
    animation.now = 1_000_000_000;
    animation.runFrame();
    animation.now = 6_000_000_000L;
    animation.runFrame();

    assertThat(animation.frame).isNotNull();
    assertThat(animation.requested).isGreaterThanOrEqualTo(3);
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

  @Test void liveTranscriptRendersStructuredToolBodyPreviews() {
    ToolUse todo = new ToolUse(new ToolCallId("plan"), new ToolName("todo"), Map.of(
        "todos", List.of(Map.of("content", "ship it", "status", "completed"))),
        new ToolStatus.Pending());
    ToolUse write = new ToolUse(new ToolCallId("write"), new ToolName("write"), Map.of(
        "content", "first\nsecond"), new ToolStatus.Done("wrote file"));
    var state = new AtomicReference<>(AgentState.initial(thread(List.of(
        new Message(Role.ASSISTANT, "", List.of(), List.of(todo, write))))));
    var terminal = new FakeTerminal();

    new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate()).render();

    assertThat(terminal.bytes.toString()).contains("✓", "ship it", "first", "second",
        "2 lines");
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

  @Test void settledTranscriptFreezesAndTrimsOnlyAfterItsRowsWerePainted() {
    var messages = new ArrayList<Message>();
    for (int index = 0; index < 40; index++) {
      messages.add(new Message(Role.USER, "settled request " + index, List.of(), List.of()));
    }
    var state = new AtomicReference<>(AgentState.initial(thread(messages)));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());

    ui.render();
    long firstPaintRows = ui.frozenRows();
    ui.render();

    assertThat(ui.frozenThrough()).isEqualTo(messages.size());
    assertThat(firstPaintRows).isGreaterThan(48);
    assertThat(ui.frozenRows()).isLessThanOrEqualTo(48);
    assertThat(ui.frozenBlocks()).isPositive();
  }

  @Test void activeStreamingBackRemainsMutableAndOutsideTheFrozenPrefix() {
    var messages = new ArrayList<Message>();
    for (int index = 0; index < 9; index++) {
      messages.add(new Message(Role.USER, "history " + index, List.of(), List.of()));
    }
    Message live = new Message(Role.ASSISTANT, "first delta", List.of(), List.of());
    messages.add(live);
    AgentState streaming = withPhase(AgentState.initial(thread(messages)),
        new SessionPhase.Streaming(ActiveTurn.start(new CancellationSignal(), 1)));
    var state = new AtomicReference<>(streaming);
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    ui.render();

    Message revised = new Message(live.id(), live.role(), "first delta and second delta",
        live.images(), live.attachments(), live.thinking(), live.thinkingSignature(),
        live.toolCalls(), live.timestamp(), live.checkpointId(), live.error(),
        live.textBlockClosed(), live.isCompactSummary());
    messages.set(messages.size() - 1, revised);
    state.set(withPhase(AgentState.initial(thread(messages)),
        new SessionPhase.Streaming(ActiveTurn.start(new CancellationSignal(), 2))));
    ui.render();

    assertThat(ui.frozenThrough()).isEqualTo(messages.size() - 1);
    assertThat(ui.liveRevealContent()).isEqualTo("first delta and second delta");
  }

  @Test void widthChangeRehydratesTheFrozenPrefixThroughAWholeSurfaceReset() {
    List<Message> messages = List.of(
        new Message(Role.USER, "one", List.of(), List.of()),
        new Message(Role.USER, "two", List.of(), List.of()));
    var state = new AtomicReference<>(AgentState.initial(thread(messages)));
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate());
    ui.render();
    int before = terminal.bytes.length();

    terminal.size = new JLineTerminalSession.Size(50, 12);
    ui.render();

    assertThat(terminal.bytes.substring(before)).contains("\u001b[2J\u001b[3J\u001b[H");
    assertThat(ui.frozenThrough()).isEqualTo(messages.size());
  }

  @Test void activeAssistantRunStaysWholeAndSettlesAsOneHeaderBearingBlock() {
    var messages = new ArrayList<Message>();
    messages.add(new Message(Role.USER, "please do many edits", List.of(), List.of()));
    for (int index = 0; index < 20; index++) {
      String path = "src/s" + index + ".java";
      ToolUse edit = new ToolUse(new ToolCallId("edit-" + index), new ToolName("edit"),
          Map.of("path", path), new ToolStatus.Done(
              "```diff\n--- a/" + path + "\n+++ b/" + path
                  + "\n@@ -1 +1 @@\n-old\n+new\n```"));
      messages.add(new Message(Role.ASSISTANT, "completed subturn " + index,
          List.of(), List.of(edit)));
    }
    Message tail = new Message(Role.ASSISTANT, "continuing to work", List.of(), List.of());
    messages.add(tail);
    AgentState active = withPhase(AgentState.initial(thread(messages)),
        new SessionPhase.Streaming(ActiveTurn.start(new CancellationSignal(), 1)));
    var state = new AtomicReference<>(active);
    var animation = new ManualAnimation();
    var terminal = new FakeTerminal();
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate(), animation);

    ui.render();
    assertThat(ui.frozenThrough()).isOne();
    assertThat(terminal.bytes.toString()).contains("completed subturn 0",
        "completed subturn 19", "src/s0.java", "src/s19.java")
        .doesNotContain("earlier action");

    state.set(AgentState.initial(thread(messages)));
    for (int index = 0; index < 50 && animation.frame != null; index++) {
      animation.now += 16_000_000;
      animation.runFrame();
    }

    assertThat(ui.frozenThrough()).isEqualTo(messages.size());
    assertThat(ui.frozenText()).contains("completed subturn 0", "completed subturn 19",
        "src/s0.java", "src/s19.java")
        .doesNotContain("earlier action");
    assertThat(count(ui.frozenText(), "\u2726 Opus 4.5")).isOne();
  }

  @Test void coldRehydrateCutsInsideAGiantRunButStartsOnARealHeader() {
    var messages = new ArrayList<Message>();
    for (int turn = 0; turn < 4; turn++) {
      messages.add(new Message(Role.USER, "request " + turn, List.of(), List.of()));
      messages.add(new Message(Role.ASSISTANT, "reply " + turn, List.of(), List.of()));
    }
    messages.add(new Message(Role.USER, "do a huge refactor", List.of(), List.of()));
    for (int index = 0; index < 60; index++) {
      messages.add(new Message(Role.ASSISTANT,
          "giant-" + index + "\nline two\nline three\nline four\nline five",
          List.of(), List.of()));
    }
    messages.add(new Message(Role.ASSISTANT, "all done", List.of(), List.of()));
    var state = new AtomicReference<>(AgentState.initial(thread(messages)));
    var animation = new ManualAnimation();
    var terminal = new FakeTerminal();
    terminal.size = new JLineTerminalSession.Size(80, 12);
    var ui = new InteractiveCommand.Ui(terminal, state,
        new InteractiveCommand.PermissionGate(), animation);

    ui.render();
    for (int index = 0; index < 50 && animation.frame != null; index++) {
      animation.now += 16_000_000;
      animation.runFrame();
    }

    assertThat(ui.frozenThrough()).isEqualTo(messages.size());
    assertThat(firstNonblank(ui.frozenText())).startsWith("\u2503  \u2726 Opus 4.5");
    assertThat(ui.frozenText()).contains("giant-59", "all done")
        .doesNotContain("giant-0");
  }

  @Test void trimNeverStrandsASeparatorOrHeaderlessAssistantBody() {
    var messages = new ArrayList<Message>();
    for (int turn = 0; turn < 80; turn++) {
      messages.add(new Message(Role.USER, "q" + turn, List.of(), List.of()));
      messages.add(new Message(Role.ASSISTANT, "answer-" + turn, List.of(), List.of()));
    }
    var ui = new InteractiveCommand.Ui(new FakeTerminal(),
        new AtomicReference<>(AgentState.initial(thread(messages))),
        new InteractiveCommand.PermissionGate());

    ui.render();
    ui.render();
    ui.render();

    assertThat(firstNonblank(ui.frozenText()))
        .matches("\\u2503  [\\u276f\\u2726] .+");
  }

  @Test void frozenCollapseRemainsOptInAndKeepsTheTrailingEntryWhole() {
    String giant = java.util.stream.IntStream.range(0, 100)
        .mapToObj(index -> "giant-loaded-row-" + index).collect(
            java.util.stream.Collectors.joining("\n"));
    List<Message> messages = List.of(
        new Message(Role.USER, giant, List.of(), List.of()),
        new Message(Role.ASSISTANT, "older answer", List.of(), List.of()),
        new Message(Role.USER, "recent question", List.of(), List.of()),
        new Message(Role.ASSISTANT, "recent answer", List.of(), List.of()));
    var state = new AtomicReference<>(AgentState.initial(thread(messages)));
    var expanded = new InteractiveCommand.Ui(new FakeTerminal(), state,
        new InteractiveCommand.PermissionGate(), new ManualAnimation(), Map.of());
    var collapsed = new InteractiveCommand.Ui(new FakeTerminal(), state,
        new InteractiveCommand.PermissionGate(), new ManualAnimation(),
        Map.of("AGENTTY_FROZEN_COLLAPSE", "true"));

    expanded.render();
    collapsed.render();

    assertThat(expanded.frozenText()).contains("giant-loaded-row-0", "recent question")
        .doesNotContain("rows collapsed");
    assertThat(collapsed.frozenText()).contains("rows collapsed", "recent question")
        .doesNotContain("giant-loaded-row-0");
  }

  @Test void hugeSettledBashOutputUsesItsElidedPaintHeightInTheFrozenLedger() {
    StringBuilder output = new StringBuilder();
    for (int index = 0; index < 200; index++) {
      output.append("src/file").append(index).append(".cpp:").append(index)
          .append(": some matching line of text\n");
    }
    ToolUse bash = new ToolUse(new ToolCallId("bash-noisy"), new ToolName("bash"),
        Map.of("command", "grep -rn pattern src"), new ToolStatus.Done(output.toString()));
    List<Message> messages = List.of(
        new Message(Role.USER, "run a noisy command", List.of(), List.of()),
        new Message(Role.ASSISTANT, "results", List.of(), List.of(bash)));
    var ui = new InteractiveCommand.Ui(new FakeTerminal(),
        new AtomicReference<>(AgentState.initial(thread(messages))),
        new InteractiveCommand.PermissionGate());

    ui.render();
    ui.render();

    assertThat(ui.frozenText()).contains("file199.cpp").doesNotContain("file0.cpp");
    assertThat(ui.frozenRows()).isLessThan(32);
  }

  @Test void nonterminalToolPreventsIdleRunFromFreezingUntilItSettles() {
    Message user = new Message(Role.USER, "write a file", List.of(), List.of());
    ToolUse running = new ToolUse(new ToolCallId("edit"), new ToolName("edit"), Map.of(),
        new ToolStatus.Running("working"));
    Message assistant = new Message(Role.ASSISTANT, "editing", List.of(), List.of(running));
    var messages = new ArrayList<>(List.of(user, assistant));
    var state = new AtomicReference<>(AgentState.initial(thread(messages)));
    var ui = new InteractiveCommand.Ui(new FakeTerminal(), state,
        new InteractiveCommand.PermissionGate());

    ui.render();
    ui.render();
    assertThat(ui.frozenThrough()).isOne();

    ToolUse done = new ToolUse(running.id(), running.name(), running.arguments(),
        new ToolStatus.Done("edited"));
    messages.set(1, assistant.withToolCalls(List.of(done)));
    state.set(AgentState.initial(thread(messages)));
    ui.render();
    ui.render();

    assertThat(ui.frozenThrough()).isEqualTo(2);
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

  private static AgentState withQueued(
      AgentState state, List<RuntimeMessage.Submit> queued) {
    return new AgentState(state.thread(), state.phase(), state.activeTurnId(), state.turnCounter(),
        state.tokensIn(), state.tokensOut(), state.lastTickNanos(), state.status(),
        state.toolDraft(), queued, state.compaction(), state.oauthRefreshInFlight(),
        state.truncatedToolIds(), state.sessionGrants());
  }

  private static Attachment attachment(String body) {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    return new Attachment(Attachment.Kind.PASTE, bytes, "", "", "", 0, 1, bytes.length);
  }

  private static Message userMessage(String text, Attachment attachment) {
    return new Message(com.github.skanga.ajent.domain.MessageId.random(), Role.USER, text,
        List.of(), List.of(attachment), "", "", List.of(), Instant.now(), Optional.empty(),
        Optional.empty(), false, false);
  }

  private static ToolUse tool(String output) {
    return new ToolUse(new ToolCallId("call"), new ToolName("bash"), Map.of(),
        new ToolStatus.Failed(0, 100_000_000, output));
  }

  private static int count(String source, String needle) {
    int matches = 0;
    for (int offset = source.indexOf(needle); offset >= 0;
        offset = source.indexOf(needle, offset + needle.length())) {
      matches++;
    }
    return matches;
  }

  private static String firstNonblank(String source) {
    return source.lines().filter(line -> !line.isBlank()).findFirst().orElse("");
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
    private com.github.skanga.ajent.domain.Effort effort =
        com.github.skanga.ajent.domain.Effort.NONE;
    private List<String> favorites = List.of();
    private String provider = "anthropic";
    private boolean rejectProvider;
    private boolean deferModels;
    private List<com.github.skanga.ajent.terminal.ui.ModelPicker.Model> availableModels;
    private List<String> workspaceFiles = List.of();
    private List<com.github.skanga.ajent.core.workspace.WorkspaceSymbol> workspaceSymbols = List.of();
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
    @Override public com.github.skanga.ajent.domain.Effort effort() { return effort; }
    @Override public void setEffort(com.github.skanga.ajent.domain.Effort value) { effort = value; }
    @Override public void loadModels(
        java.util.function.Consumer<List<com.github.skanga.ajent.terminal.ui.ModelPicker.Model>> receiver) {
      if (deferModels) { pendingModels = receiver; return; }
      receiver.accept(modelRows());
    }
    private List<com.github.skanga.ajent.terminal.ui.ModelPicker.Model> modelRows() {
      return availableModels == null ? List.of(
          new com.github.skanga.ajent.terminal.ui.ModelPicker.Model("alpha", "Alpha", false),
          new com.github.skanga.ajent.terminal.ui.ModelPicker.Model("beta", "Beta", false))
          : availableModels;
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
    @Override public List<String> workspaceFiles() { return workspaceFiles; }
    @Override public List<com.github.skanga.ajent.core.workspace.WorkspaceSymbol> workspaceSymbols() {
      return workspaceSymbols;
    }
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
    @Override public void updatePendingChanges(
        List<com.github.skanga.ajent.terminal.ui.DiffReview.File> reviewed) {
      changes = List.copyOf(reviewed);
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
