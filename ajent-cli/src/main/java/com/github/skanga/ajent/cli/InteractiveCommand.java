package com.github.skanga.ajent.cli;

import com.github.skanga.ajent.core.persistence.Settings;
import com.github.skanga.ajent.core.persistence.SettingsStore;
import com.github.skanga.ajent.core.persistence.ThreadStore;
import com.github.skanga.ajent.core.persistence.ThreadLoadResult;
import com.github.skanga.ajent.core.workspace.WorkspaceSymbol;
import com.github.skanga.ajent.domain.Attachment;
import com.github.skanga.ajent.domain.AttachmentText;
import com.github.skanga.ajent.domain.Effort;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.CheckpointId;
import com.github.skanga.ajent.domain.ModelId;
import com.github.skanga.ajent.domain.ModelCapabilities;
import com.github.skanga.ajent.domain.Profile;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.SessionPhase;
import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.provider.auth.Credential;
import com.github.skanga.ajent.provider.ProviderModelCatalog;
import com.github.skanga.ajent.provider.auth.CredentialResolver;
import com.github.skanga.ajent.provider.auth.CredentialStore;
import com.github.skanga.ajent.provider.auth.AnthropicOAuthLogin;
import com.github.skanga.ajent.provider.auth.OAuthTokenClient;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import com.github.skanga.ajent.provider.openai.ProviderAuthResolver;
import com.github.skanga.ajent.provider.openai.ProviderRegistry;
import com.github.skanga.ajent.runtime.AgentLoop;
import com.github.skanga.ajent.runtime.AgentSessionFactory;
import com.github.skanga.ajent.runtime.AgentState;
import com.github.skanga.ajent.runtime.CheckpointPort;
import com.github.skanga.ajent.runtime.LiveProviderFactory;
import com.github.skanga.ajent.runtime.ProviderBackedSubagentRunner;
import com.github.skanga.ajent.runtime.DispatcherToolPort;
import com.github.skanga.ajent.runtime.PermissionPort;
import com.github.skanga.ajent.runtime.RuntimeMessage;
import com.github.skanga.ajent.runtime.ToolCompletion;
import com.github.skanga.ajent.terminal.JLineTerminalSession;
import com.github.skanga.ajent.terminal.input.TerminalEvent;
import com.github.skanga.ajent.terminal.input.TerminalClipboardQuery;
import com.github.skanga.ajent.terminal.input.TerminalKey;
import com.github.skanga.ajent.terminal.render.CanvasSerializer;
import com.github.skanga.ajent.terminal.render.ColumnTextWrapper;
import com.github.skanga.ajent.terminal.render.FrozenScrollbackTrimPolicy;
import com.github.skanga.ajent.terminal.render.InlineFrameRenderer;
import com.github.skanga.ajent.terminal.render.MarkdownTerminalRenderer;
import com.github.skanga.ajent.terminal.render.StreamingMarkdown;
import com.github.skanga.ajent.terminal.render.ScrollbackLedger;
import com.github.skanga.ajent.terminal.render.TerminalCanvas;
import com.github.skanga.ajent.terminal.render.TerminalColor;
import com.github.skanga.ajent.terminal.render.TerminalStyle;
import com.github.skanga.ajent.terminal.render.TerminalStylePool;
import com.github.skanga.ajent.terminal.ui.CommandPalette;
import com.github.skanga.ajent.terminal.ui.CodeBlockPicker;
import com.github.skanga.ajent.terminal.ui.CheckpointPicker;
import com.github.skanga.ajent.terminal.ui.DiffReview;
import com.github.skanga.ajent.terminal.ui.ModelPicker;
import com.github.skanga.ajent.terminal.ui.LoginModal;
import com.github.skanga.ajent.terminal.ui.MentionPicker;
import com.github.skanga.ajent.terminal.ui.PickerState;
import com.github.skanga.ajent.terminal.ui.PlanModal;
import com.github.skanga.ajent.terminal.ui.ProviderPicker;
import com.github.skanga.ajent.terminal.ui.SymbolPicker;
import com.github.skanga.ajent.terminal.ui.ToolBodyPreview;
import com.github.skanga.ajent.terminal.ui.ToolOutputViewer;
import com.github.skanga.ajent.terminal.ui.ThreadPicker;
import com.github.skanga.ajent.tools.process.ProcessSandbox;
import com.github.skanga.ajent.tools.process.ProcessRunner;
import com.github.skanga.ajent.tools.runtime.ToolRuntimeFactory;
import com.github.skanga.ajent.tools.runtime.FileChange;
import com.github.skanga.ajent.tools.attachment.ClipboardReader;
import com.github.skanga.ajent.tools.attachment.ImagePaste;
import com.github.skanga.ajent.tools.attachment.SystemClipboardReader;
import com.github.skanga.ajent.tools.web.JdkWebTransport;
import com.github.skanga.ajent.tools.host.HostServices;
import com.github.skanga.ajent.tools.workspace.GitCheckpointStore;
import com.github.skanga.ajent.tools.workspace.WorkspaceIndex;
import java.io.IOException;
import java.io.PrintStream;
import java.awt.Desktop;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Interactive terminal composition root. */
final class InteractiveCommand {
  private static final int USAGE_ERROR = 2;
  private static final int SOFTWARE_ERROR = 70;
  private static final String DEFAULT_MODEL = "claude-opus-4-5";

  private final Path currentDirectory;
  private final Path home;
  private final Map<String, String> environment;
  private final CredentialStore credentials;
  private final HttpClient client;

  InteractiveCommand(Path currentDirectory, Path home, Map<String, String> environment,
      CredentialStore credentials, HttpClient client) {
    this.currentDirectory = Objects.requireNonNull(currentDirectory, "currentDirectory")
        .toAbsolutePath().normalize();
    this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
    this.environment = Map.copyOf(environment);
    this.credentials = Objects.requireNonNull(credentials, "credentials");
    this.client = Objects.requireNonNull(client, "client");
  }

  static InteractiveCommand systemDefault() {
    Path home = Path.of(System.getProperty("user.home", "."));
    return new InteractiveCommand(Path.of(""), home, System.getenv(),
        CredentialStore.systemDefault(), HttpClient.newHttpClient());
  }

  int run(CliArguments arguments, PrintStream error) {
    Configuration configured = configure(arguments, error);
    if (configured == null) return USAGE_ERROR;
    try (var terminal = JLineTerminalSession.open()) {
      return runSession(configured, terminal);
    } catch (IOException | RuntimeException exception) {
      error.print("ajent: interactive mode failed: " + detail(exception) + "\n");
      return SOFTWARE_ERROR;
    }
  }

  private int runSession(Configuration configured, JLineTerminalSession terminal) throws IOException {
    var state = new AtomicReference<AgentState>();
    var activeLoop = new AtomicReference<AgentLoop>();
    var activeProfile = new AtomicReference<>(configured.profile());
    var activeProvider = new AtomicReference<>(configured.providerConfiguration());
    configured.subagents().install(activeProvider::get);
    var activeUi = new AtomicReference<Ui>();
    var pendingChanges = new AtomicReference<List<FileChange>>(List.of());
    var permission = new PermissionGate();
    try (var animations = new FrameScheduler()) {
      var ui = new Ui(new TerminalPort() {
        @Override public JLineTerminalSession.Size size() { return terminal.size(); }
        @Override public void write(String value) { terminal.write(value); }
      }, state, permission, animations, environment);
      activeUi.set(ui);
      configured.todos().onChange(ui::updatePlan);
      permission.onChange(ui::render);
      var threadStore = new ThreadStore(configured.dataDirectory());
      var conversation = new com.github.skanga.ajent.domain.Thread(
          threadStore.newId(), "", List.of(), Instant.now(), Instant.now(), List.of());
      BiConsumer<RuntimeMessage, AgentState> observe = (message, next) -> {
        recordChange(message, pendingChanges);
        state.set(next);
        liveTodoItems(next).ifPresent(configured.todos()::set);
        Ui current = activeUi.get();
        if (current != null) current.render();
      };
      AgentLoop initialLoop = configured.sessions().create(conversation, activeProfile::get,
          (AgentSessionFactory.ConfigurationSource) activeProvider::get,
          permission, observe);
      activeLoop.set(initialLoop);
      state.set(initialLoop.state());
      AgentControl control = new AgentControl() {
        private final AnthropicOAuthLogin oauth = new AnthropicOAuthLogin();
        @Override public AgentState state() { return activeLoop.get().state(); }
        @Override public void dispatch(RuntimeMessage message) {
          activeLoop.get().dispatch(message);
        }
        @Override public void newThread() {
          permission.cancel();
          AgentLoop previous = activeLoop.get();
          if (!previous.state().thread().messages().isEmpty()) {
            threadStore.save(previous.state().thread());
          }
          previous.close();
          var fresh = new com.github.skanga.ajent.domain.Thread(
              threadStore.newId(), "", List.of(), Instant.now(), Instant.now(), List.of());
          AgentLoop replacement = configured.sessions().create(fresh, activeProfile::get,
              (AgentSessionFactory.ConfigurationSource) activeProvider::get,
              permission, observe);
          activeLoop.set(replacement);
          state.set(replacement.state());
          pendingChanges.set(List.of());
        }
        @Override public ThreadId threadId() { return activeLoop.get().state().thread().id(); }
        @Override public void loadThreads(Consumer<List<ThreadPicker.Entry>> receiver) {
          Thread.startVirtualThread(() -> receiver.accept(threadStore.loadAllMetadata().stream()
              .map(thread -> new ThreadPicker.Entry(
                  thread.id(), thread.title(), thread.updatedAt())).toList()));
        }
        @Override public void loadThread(ThreadId id, Consumer<String> completed) {
          Thread.startVirtualThread(() -> {
            ThreadLoadResult result = threadStore.load(id);
            if (result instanceof ThreadLoadResult.Failure failure) {
              completed.accept(failure.error().detail());
              return;
            }
            com.github.skanga.ajent.domain.Thread loaded =
                ((ThreadLoadResult.Success) result).thread();
            permission.cancel();
            AgentLoop previous = activeLoop.get();
            if (!previous.state().thread().messages().isEmpty()) {
              threadStore.save(previous.state().thread());
            }
            previous.close();
            AgentLoop replacement = configured.sessions().create(loaded, activeProfile::get,
                (AgentSessionFactory.ConfigurationSource) activeProvider::get,
                permission, observe);
            activeLoop.set(replacement);
            state.set(replacement.state());
            pendingChanges.set(List.of());
            completed.accept("");
          });
        }
        @Override public boolean windows() {
          return System.getProperty("os.name", "").startsWith("Windows");
        }
        @Override public void runCodeBlock(
            CodeBlockPicker.Block block, Consumer<CodeBlockPicker.Result> completed) {
          boolean windows = windows();
          CodeBlockPicker.Shell shell = CodeBlockPicker.shell(block.language(), windows);
          String command = CodeBlockPicker.commandFor(shell, block.body());
          ProcessRunner.Result result = terminal.suspend(() -> {
            terminal.write(codeBlockHeader(windows, block.body()));
            int rows = terminal.size().rows();
            if (!windows && rows >= 3) terminal.write(codeBlockStatusBegin(rows));
            String label = codeBlockLabel(block.body());
            var spinner = new AtomicInteger();
            long started = System.nanoTime();
            ProcessRunner.Result executed;
            try {
              executed = executeCodeBlock(windows, configured.codeRunner(), command,
                  configured.workspace(), terminal::write,
                  () -> {
                    JLineTerminalSession.SignalGuard guard = terminal.ignoreInterrupts();
                    return guard::close;
                  }, elapsed -> terminal.write(codeBlockHeartbeat(
                      windows, rows, label, elapsed, spinner.getAndIncrement())));
            } finally {
              if (!windows) terminal.write(codeBlockStatusEnd(rows));
            }
            terminal.write(codeBlockFooter(windows, executed,
                Duration.ofNanos(System.nanoTime() - started).toSeconds()));
            if (!windows && terminal.interactive()) {
              terminal.write("\u001b[2m   press any key to return to ajent…\u001b[0m");
              terminal.readSingleKey();
              terminal.write("\r\u001b[2K");
            }
            return executed;
          });
          String output = result.started() ? result.output()
              : "[failed to start: " + result.startError() + "]";
          if (windows && result.truncated()) output += "\n[output truncated]";
          completed.accept(new CodeBlockPicker.Result(block.body(), output,
              result.started() ? result.exitCode() : -1, result.timedOut()));
        }
        @Override public boolean checkpointsAvailable() {
          return configured.checkpoints().inGitRepo();
        }
        @Override public void loadCheckpointDiff(
            CheckpointId id, Consumer<Optional<int[]>> completed) {
          Thread.startVirtualThread(() -> {
            GitCheckpointStore.Diff diff = configured.checkpoints().summary(id);
            completed.accept(diff.valid() ? Optional.of(new int[] {
                diff.filesChanged(), diff.insertions(), diff.deletions()}) : Optional.empty());
          });
        }
        @Override public void restoreCheckpoint(
            CheckpointId id, Consumer<CheckpointPicker.Restore> completed) {
          Thread.startVirtualThread(() -> {
            GitCheckpointStore.Restore restored = configured.checkpoints().restore(id);
            if (!restored.restored()) {
              completed.accept(new CheckpointPicker.Restore(false, "", restored.error()));
              return;
            }
            AgentLoop previous = activeLoop.get();
            com.github.skanga.ajent.domain.Thread current = previous.state().thread();
            int cut = -1;
            String prompt = "";
            for (int index = 0; index < current.messages().size(); index++) {
              Message candidate = current.messages().get(index);
              if (candidate.checkpointId().filter(id::equals).isPresent()) {
                cut = index;
                prompt = candidate.text();
                break;
              }
            }
            if (cut < 0) {
              completed.accept(new CheckpointPicker.Restore(
                  false, "", "files rewound (turn already gone)"));
              return;
            }
            int cutoff = cut;
            var truncated = new com.github.skanga.ajent.domain.Thread(current.id(), current.title(),
                current.messages().subList(0, cutoff), current.createdAt(), Instant.now(),
                current.compactions().stream()
                    .filter(record -> record.upToIndex() <= cutoff).toList());
            permission.cancel();
            previous.close();
            if (truncated.messages().isEmpty()) threadStore.delete(truncated.id());
            else threadStore.save(truncated);
            AgentLoop replacement = configured.sessions().create(truncated, activeProfile::get,
                (AgentSessionFactory.ConfigurationSource) activeProvider::get,
                permission, observe);
            activeLoop.set(replacement);
            state.set(replacement.state());
            pendingChanges.set(List.of());
            completed.accept(new CheckpointPicker.Restore(true, prompt, ""));
          });
        }
        @Override public Profile cycleProfile() {
          Profile next = switch (activeProfile.get()) {
            case WRITE -> Profile.ASK;
            case ASK -> Profile.MINIMAL;
            case MINIMAL -> Profile.WRITE;
          };
          activeProfile.set(next);
          activeLoop.get().dispatch(new RuntimeMessage.ProfileChanged(next));
          configured.settings().save(configured.settings().load().withProfile(next));
          return next;
        }
        @Override public String model() { return activeProvider.get().model(); }
        @Override public Effort effort() {
          return Effort.fromWire(activeProvider.get().effort());
        }
        @Override public void setEffort(Effort effort) {
          activeProvider.updateAndGet(current -> new LiveProviderFactory.Configuration(
              current.provider(), current.model(), current.auth(), effort.wire(),
              current.systemPrompt(), current.contextWindow(), current.environment()));
          configured.settings().save(configured.settings().load().withEffort(effort.wire()));
        }
        @Override public void loadModels(Consumer<List<ModelPicker.Model>> receiver) {
          Thread.startVirtualThread(() -> {
            List<com.github.skanga.ajent.provider.ProviderModel> discovered =
                activeProvider.get().provider().equals("anthropic")
                    ? configured.models().listAnthropicModels(activeProvider.get().auth())
                    : configured.models().listModels(activeProvider.get().auth(),
                        com.github.skanga.ajent.provider.openai.Endpoint.fromSpec(
                            activeProvider.get().provider()));
            Settings saved = configured.settings().load();
            List<ModelPicker.Model> result = discovered.stream().map(model ->
                new ModelPicker.Model(model.id(), model.displayName(),
                    saved.favoriteModels().contains(new ModelId(model.id())))).toList();
            if (result.isEmpty()) {
              result = List.of(new ModelPicker.Model(model(), model(), false));
            }
            if (activeUi.get() != null) receiver.accept(result);
          });
        }
        @Override public void selectModel(String model) {
          activeProvider.updateAndGet(current -> {
            Effort effort = Effort.fromWire(current.effort()).clamp(
                ModelCapabilities.fromId(model));
            return new LiveProviderFactory.Configuration(
                current.provider(), model, current.auth(), effort.wire(), current.systemPrompt(),
                current.contextWindow(), current.environment());
          });
          LiveProviderFactory.Configuration selected = activeProvider.get();
          configured.settings().save(configured.settings().load().withProviderModel(
              selected.provider(), new ModelId(model)).withEffort(selected.effort()));
        }
        @Override public void saveFavorites(List<String> models) {
          configured.settings().save(configured.settings().load().withFavoriteModels(
              models.stream().map(ModelId::new).toList()));
        }
        @Override public List<String> workspaceFiles() { return configured.workspaceIndex().files(); }
        @Override public List<WorkspaceSymbol> workspaceSymbols() {
          return configured.workspaceIndex().symbols();
        }
        @Override public String provider() { return activeProvider.get().provider(); }
        @Override public List<ProviderPicker.Provider> providers() {
          return ProviderRegistry.presets().stream()
              .map(preset -> new ProviderPicker.Provider(preset.id(), preset.label())).toList();
        }
        @Override public boolean selectProvider(String provider) {
          Settings saved = configured.settings().load();
          CredentialResolver.Resolution anthropic = CredentialResolver.resolve(
              "", environment, credentials.load(), System.currentTimeMillis());
          ProviderAuth auth = ProviderAuthResolver.resolve(provider,
              providerAuth(anthropic.credential()), "",
              saved.providerKeys().getOrDefault(provider, ""), environment);
          var preset = ProviderRegistry.presetFor(provider);
          boolean needsKey = preset.isPresent()
              && preset.orElseThrow().kind() == ProviderRegistry.Kind.OPENAI
              && !preset.orElseThrow().local()
              && preset.orElseThrow().authStyle() != ProviderRegistry.AuthStyle.NONE;
          if (needsKey && auth.isEmpty()) return false;
          String recalled = saved.providerModels().getOrDefault(provider, "");
          if (recalled.isBlank()) recalled = activeProvider.get().model();
          String selectedModel = recalled;
          activeProvider.updateAndGet(current -> new LiveProviderFactory.Configuration(
              provider, selectedModel, auth, Effort.fromWire(current.effort()).clamp(
                  ModelCapabilities.fromId(selectedModel)).wire(),
              current.systemPrompt(),
              current.contextWindow(), current.environment()));
          configured.settings().save(saved.withProviderModel(provider, new ModelId(selectedModel))
              .withEffort(activeProvider.get().effort()));
          return true;
        }
        @Override public LoginModal.OAuthAttempt newOAuthAttempt() {
          AnthropicOAuthLogin.Attempt attempt = oauth.newAttempt();
          return new LoginModal.OAuthAttempt(
              attempt.verifier(), attempt.state(), attempt.authorizationUri());
        }
        @Override public void openBrowser(URI uri) {
          if (!Desktop.isDesktopSupported()) return;
          try { Desktop.getDesktop().browse(uri); }
          catch (IOException | UnsupportedOperationException ignored) { }
        }
        @Override public boolean installAnthropicKey(String key) {
          if (!credentials.save(new Credential.ApiKey(key))) return false;
          activeProvider.updateAndGet(current -> current.provider().equals("anthropic")
              ? new LiveProviderFactory.Configuration(current.provider(), current.model(),
                  new ProviderAuth.ApiKey(key), current.effort(), current.systemPrompt(),
                  current.contextWindow(), current.environment()) : current);
          return true;
        }
        @Override public boolean installProviderKey(String provider, String key) {
          Settings saved = configured.settings().load().withProviderKey(provider, key);
          if (!configured.settings().save(saved)) return false;
          return selectProvider(provider);
        }
        @Override public boolean switchCustomHost(String specification) {
          String currentModel = activeProvider.get().model();
          activeProvider.updateAndGet(current -> new LiveProviderFactory.Configuration(
              specification, currentModel, new ProviderAuth.Empty(), current.effort(),
              current.systemPrompt(), current.contextWindow(), current.environment()));
          return configured.settings().save(configured.settings().load()
              .withProviderModel(specification, new ModelId(currentModel)));
        }
        @Override public void exchangeOAuth(LoginModal.ExchangeOAuth exchange,
            Consumer<String> completed) {
          Thread.startVirtualThread(() -> {
            OAuthTokenClient.Result result = oauth.exchange(
                exchange.code(), exchange.verifier(), exchange.state());
            if (result instanceof OAuthTokenClient.Result.Failure failure) {
              completed.accept(failure.error().detail());
              return;
            }
            OAuthTokenClient.Token token = ((OAuthTokenClient.Result.Success) result).token();
            long expiresAt = token.expiresInSeconds() == 0 ? 0
                : System.currentTimeMillis() + token.expiresInSeconds() * 1000;
            boolean saved = credentials.save(new Credential.OAuth(
                token.accessToken(), token.refreshToken(), expiresAt));
            if (saved) {
              activeProvider.updateAndGet(current -> current.provider().equals("anthropic")
                  ? new LiveProviderFactory.Configuration(current.provider(), current.model(),
                      new ProviderAuth.Bearer(token.accessToken()), current.effort(),
                      current.systemPrompt(), current.contextWindow(), current.environment())
                  : current);
            }
            completed.accept(saved ? "" : "failed to save credentials");
          });
        }
        @Override public List<DiffReview.File> pendingChanges() {
          return pendingChanges.get().stream().map(InteractiveCommand::reviewFile).toList();
        }
        @Override public void updatePendingChanges(List<DiffReview.File> reviewed) {
          pendingChanges.updateAndGet(existing -> mergeReview(existing, reviewed));
        }
        @Override public void clearPendingChanges() { pendingChanges.set(List.of()); }
      };
      try {
        terminal.onResize(ignored -> ui.render());
        ui.render();
        boolean running = true;
        while (running) {
          List<TerminalEvent> events = terminal.read();
          if (events.isEmpty()) events = terminal.flushEscape();
          for (TerminalEvent event : events) {
            if (event instanceof TerminalEvent.Key key) {
              running = ui.key(key.value(), control);
            } else if (event instanceof TerminalEvent.Paste paste) {
              ui.paste(paste.content());
            }
            if (!running) break;
          }
        }
        return 0;
      } finally {
        permission.cancel();
        activeUi.set(null);
        AgentLoop loop = activeLoop.get();
        if (loop != null) loop.close();
      }
    }
  }

  Configuration configure(CliArguments arguments, PrintStream error) {
    Path workspace;
    try {
      workspace = arguments.workspace().isBlank() ? currentDirectory
          : resolve(arguments.workspace());
    } catch (InvalidPathException exception) {
      workspace = null;
    }
    if (workspace == null || !Files.isDirectory(workspace)) {
      error.print("ajent: --workspace path is not a directory: "
          + arguments.workspace() + "\n");
      return null;
    }
    ProcessSandbox.Initialization sandbox;
    try {
      sandbox = ProcessSandbox.initialize(arguments.sandbox(), workspace);
    } catch (IllegalArgumentException exception) {
      error.print("ajent: " + exception.getMessage() + "\n");
      return null;
    }
    if (!sandbox.valid()) {
      error.print("ajent: --sandbox=on but no backend available. "
          + sandbox.description() + "\n");
      return null;
    }
    Profile profile;
    try {
      profile = profile(arguments.profile());
    } catch (IllegalArgumentException exception) {
      error.print("ajent: " + exception.getMessage() + "\n");
      return null;
    }
    Path dataDirectory = home.resolve(".agentty");
    var settingsStore = new SettingsStore(dataDirectory);
    Settings settings = settingsStore.load();
    String provider = arguments.provider().isBlank() ? settings.provider() : arguments.provider();
    if (provider.isBlank()) provider = "anthropic";
    String model = arguments.model().isBlank() ? settings.modelId().value() : arguments.model();
    if (model.isBlank()) model = DEFAULT_MODEL;
    CredentialResolver.Resolution anthropic = CredentialResolver.resolve(
        arguments.key(), environment, credentials.load(), System.currentTimeMillis());
    ProviderAuth auth = ProviderAuthResolver.resolve(provider, providerAuth(anthropic.credential()),
        arguments.key(), settings.providerKeys().getOrDefault(provider, ""), environment);
    Path docs = resolveDocs(workspace);
    var todos = new TodoLedger();
    var checkpoints = new GitCheckpointStore(workspace, sandbox.runner());
    var workspaceIndex = new WorkspaceIndex(workspace);
    var subagents = new ProviderBackedSubagentRunner(client);
    var tools = ToolRuntimeFactory.compose(new ToolRuntimeFactory.Configuration(
        workspace, workspace, home, docs, new JdkWebTransport(), todos, subagents,
        sandbox.runner()));
    subagents.bind(new DispatcherToolPort(tools.dispatcher()));
    String effort = Effort.fromWire(settings.effort()).clamp(ModelCapabilities.fromId(model)).wire();
    var providers = new LiveProviderFactory.Configuration(provider, model, auth, effort,
        tools.systemPrompt(), 0, environment);
    CheckpointPort checkpointPort = new CheckpointPort() {
      @Override public boolean enabled() { return checkpoints.inGitRepo(); }
      @Override public boolean create(CheckpointId id) { return checkpoints.create(id); }
    };
    return new Configuration(new AgentSessionFactory(
        tools, providers, client, dataDirectory, checkpointPort, workspaceIndex::attachmentBody),
        dataDirectory, profile, model, settingsStore, providers,
        new ProviderModelCatalog(client), todos, workspace, sandbox.runner(), checkpoints,
        workspaceIndex, subagents);
  }

  static void recordChange(
      RuntimeMessage message, AtomicReference<List<FileChange>> changes) {
    if (message instanceof RuntimeMessage.ToolCompleted completed
        && completed.result() instanceof ToolCompletion.Success success
        && success.change().isPresent()) {
      changes.updateAndGet(existing -> {
        var revised = new ArrayList<>(existing);
        revised.add(success.change().orElseThrow());
        return List.copyOf(revised);
      });
    }
  }

  static Optional<List<HostServices.TodoItem>> liveTodoItems(AgentState state) {
    if (state.toolDraft().isEmpty()) return Optional.empty();
    String callId = state.toolDraft().orElseThrow().callId();
    for (int messageIndex = state.thread().messages().size() - 1;
        messageIndex >= 0; messageIndex--) {
      List<ToolUse> calls = state.thread().messages().get(messageIndex).toolCalls();
      for (int callIndex = calls.size() - 1; callIndex >= 0; callIndex--) {
        ToolUse call = calls.get(callIndex);
        if (!call.id().value().equals(callId) || !call.name().value().equals("todo")) continue;
        Object raw = call.arguments().get("todos");
        if (!(raw instanceof List<?> values) || values.isEmpty()) return Optional.empty();
        var items = new ArrayList<HostServices.TodoItem>();
        for (Object value : values) {
          if (!(value instanceof Map<?, ?> item) || !(item.get("content") instanceof String content)) {
            continue;
          }
          String status = item.get("status") instanceof String text ? text : "pending";
          items.add(new HostServices.TodoItem(content, status));
        }
        return items.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(items));
      }
    }
    return Optional.empty();
  }

  static DiffReview.File reviewFile(FileChange change) {
    return new DiffReview.File(change.path(), change.added(), change.removed(),
        change.hunks().stream().map(hunk -> new DiffReview.Hunk(
            hunk.oldStart(), hunk.oldLength(), hunk.newStart(), hunk.newLength(), hunk.patch(),
            switch (hunk.status()) {
              case PENDING -> DiffReview.Status.PENDING;
              case ACCEPTED -> DiffReview.Status.ACCEPTED;
              case REJECTED -> DiffReview.Status.REJECTED;
            })).toList());
  }

  static List<FileChange> mergeReview(
      List<FileChange> changes, List<DiffReview.File> reviewed) {
    var result = new ArrayList<FileChange>(changes.size());
    for (int fileIndex = 0; fileIndex < changes.size(); fileIndex++) {
      FileChange change = changes.get(fileIndex);
      if (fileIndex >= reviewed.size()) {
        result.add(change);
        continue;
      }
      DiffReview.File file = reviewed.get(fileIndex);
      var hunks = new ArrayList<>(change.hunks());
      for (int hunkIndex = 0;
          hunkIndex < hunks.size() && hunkIndex < file.hunks().size(); hunkIndex++) {
        com.github.skanga.ajent.tools.runtime.DiffHunk hunk = hunks.get(hunkIndex);
        hunks.set(hunkIndex, hunk.withStatus(switch (file.hunks().get(hunkIndex).status()) {
          case PENDING -> com.github.skanga.ajent.tools.runtime.DiffHunk.Status.PENDING;
          case ACCEPTED -> com.github.skanga.ajent.tools.runtime.DiffHunk.Status.ACCEPTED;
          case REJECTED -> com.github.skanga.ajent.tools.runtime.DiffHunk.Status.REJECTED;
        }));
      }
      result.add(change.withHunks(hunks));
    }
    return List.copyOf(result);
  }

  private Path resolveDocs(Path workspace) {
    String configured = environment.getOrDefault("AGENTTY_DOCS_DIR", "");
    if (!configured.isBlank()) {
      try { return resolve(configured); } catch (InvalidPathException ignored) { return null; }
    }
    Path docs = workspace.resolve("docs");
    if (Files.isDirectory(docs)) return docs;
    Path knowledge = workspace.resolve(".agentty/knowledge");
    return Files.isDirectory(knowledge) ? knowledge : null;
  }

  private Path resolve(String value) {
    Path path = Path.of(value);
    return (path.isAbsolute() ? path : currentDirectory.resolve(path)).toAbsolutePath().normalize();
  }

  private static Profile profile(String value) {
    return switch (value) {
      case "", "ask" -> Profile.ASK;
      case "write" -> Profile.WRITE;
      case "minimal" -> Profile.MINIMAL;
      default -> throw new IllegalArgumentException(
          "--profile must be write, ask, or minimal (got '" + value + "')");
    };
  }

  private static ProviderAuth providerAuth(Credential credential) {
    return switch (credential) {
      case Credential.None ignored -> new ProviderAuth.Empty();
      case Credential.ApiKey key -> new ProviderAuth.ApiKey(key.key());
      case Credential.OAuth oauth -> new ProviderAuth.Bearer(oauth.accessToken());
    };
  }

  private static String detail(Exception exception) {
    return exception.getMessage() == null ? exception.getClass().getSimpleName()
        : exception.getMessage();
  }

  static ProcessRunner.Result executeCodeBlock(boolean windows, ProcessRunner runner,
      String command, Path workspace, ProcessRunner.LiveOutput liveOutput,
      java.util.function.Supplier<ProcessRunner.SignalGuard> signalGuard) {
    return executeCodeBlock(
        windows, runner, command, workspace, liveOutput, signalGuard, ignored -> {});
  }

  static ProcessRunner.Result executeCodeBlock(boolean windows, ProcessRunner runner,
      String command, Path workspace, ProcessRunner.LiveOutput liveOutput,
      java.util.function.Supplier<ProcessRunner.SignalGuard> signalGuard,
      ProcessRunner.Heartbeat heartbeat) {
    return windows
        ? runner.shell(command, workspace, 30_000, Duration.ofSeconds(120), heartbeat)
        : runner.interactivePosixShell(command, workspace, liveOutput, signalGuard, heartbeat);
  }

  static String codeBlockHeader(boolean windows, String command) {
    String banner = windows
        ? "╭─ running ─ (output shown when it finishes) ─────"
        : "╭─ running ─ Ctrl-C to stop ─────────────────────────────────";
    return "\u001b[2m\n" + banner + "\u001b[0m\n\u001b[36m$ \u001b[0m\u001b[1m"
        + command + "\u001b[0m\n";
  }

  static String codeBlockFooter(
      boolean windows, ProcessRunner.Result result, long elapsedSeconds) {
    boolean interrupted = !windows && (result.exitCode() == 130 || result.exitCode() == 131);
    String status = interrupted ? "\u001b[33m╰─ ■ stopped"
        : result.timedOut() ? "\u001b[33m╰─ ■ timed out"
        : result.started() && result.exitCode() == 0 ? "\u001b[32m╰─ ✓ done"
        : "\u001b[31m╰─ ✕ failed";
    String prefix = windows ? "\r\u001b[2K\u001b]2;\u0007" : "\n";
    return prefix + status + "\u001b[0m\u001b[2m  exit "
        + (result.started() ? result.exitCode() : -1) + "  ·  " + elapsedSeconds
        + "s\u001b[0m\n";
  }

  static String codeBlockLabel(String command) {
    int end = 0;
    while (end < command.length() && !Character.isWhitespace(command.charAt(end))) end++;
    return command.substring(0, Math.min(end, 24));
  }

  static String codeBlockHeartbeat(
      boolean windows, int rows, String label, long elapsedSeconds, int spinnerIndex) {
    String[] frames = {"⣷", "⣯", "⣟", "⡿", "⣾", "⣽", "⣻", "⣷"};
    String spinner = frames[Math.floorMod(spinnerIndex, frames.length)];
    String title = "\u001b]2;● " + elapsedSeconds + "s — " + label + " — ajent\u0007";
    if (windows) {
      return title + "\r\u001b[2K\u001b[2m" + spinner + " running… "
          + elapsedSeconds + "s\u001b[0m";
    }
    if (rows < 3) return title;
    return title + "\u001b[s\u001b[" + rows + ";1H\u001b[2K\u001b[2m" + spinner
        + " running… " + elapsedSeconds + "s · " + label
        + " · Ctrl-C to stop\u001b[0m\u001b[u";
  }

  static String codeBlockStatusBegin(int rows) {
    return rows < 3 ? "" : "\u001b[1;" + (rows - 1) + "r\u001b[" + (rows - 1) + ";1H";
  }

  static String codeBlockStatusEnd(int rows) {
    String title = "\u001b]2;\u0007";
    if (rows < 3) return title;
    return "\u001b[r\u001b[" + rows + ";1H\u001b[2K\u001b[" + (rows - 1)
        + ";1H" + title;
  }

  record Configuration(
      AgentSessionFactory sessions, Path dataDirectory, Profile profile, String model,
      SettingsStore settings, LiveProviderFactory.Configuration providerConfiguration,
      ProviderModelCatalog models, TodoLedger todos, Path workspace, ProcessRunner codeRunner,
      GitCheckpointStore checkpoints, WorkspaceIndex workspaceIndex,
      ProviderBackedSubagentRunner subagents) {}

  static final class TodoLedger implements HostServices.TodoSink {
    private final AtomicReference<List<PlanModal.Item>> items =
        new AtomicReference<>(List.of());
    private volatile Consumer<List<PlanModal.Item>> changed = ignored -> {};

    @Override public void set(List<HostServices.TodoItem> values) {
      List<PlanModal.Item> next = values.stream()
          .map(item -> PlanModal.Item.fromTool(item.content(), item.status())).toList();
      List<PlanModal.Item> previous = items.getAndSet(next);
      if (previous.equals(next)) return;
      changed.accept(next);
    }

    void onChange(Consumer<List<PlanModal.Item>> listener) {
      changed = Objects.requireNonNull(listener, "listener");
      listener.accept(items.get());
    }
  }

  interface TerminalPort {
    JLineTerminalSession.Size size();
    void write(String value);
  }

  interface AgentControl {
    AgentState state();
    void dispatch(RuntimeMessage message);
    void newThread();
    ThreadId threadId();
    void loadThreads(Consumer<List<ThreadPicker.Entry>> receiver);
    void loadThread(ThreadId id, Consumer<String> completed);
    boolean windows();
    void runCodeBlock(CodeBlockPicker.Block block, Consumer<CodeBlockPicker.Result> completed);
    boolean checkpointsAvailable();
    void loadCheckpointDiff(CheckpointId id, Consumer<Optional<int[]>> completed);
    void restoreCheckpoint(CheckpointId id, Consumer<CheckpointPicker.Restore> completed);
    Profile cycleProfile();
    String model();
    Effort effort();
    void setEffort(Effort effort);
    void loadModels(Consumer<List<ModelPicker.Model>> receiver);
    void selectModel(String model);
    void saveFavorites(List<String> models);
    List<String> workspaceFiles();
    List<WorkspaceSymbol> workspaceSymbols();
    String provider();
    List<ProviderPicker.Provider> providers();
    boolean selectProvider(String provider);
    LoginModal.OAuthAttempt newOAuthAttempt();
    void openBrowser(URI uri);
    boolean installAnthropicKey(String key);
    boolean installProviderKey(String provider, String key);
    boolean switchCustomHost(String specification);
    void exchangeOAuth(LoginModal.ExchangeOAuth exchange, Consumer<String> completed);
    List<DiffReview.File> pendingChanges();
    void updatePendingChanges(List<DiffReview.File> reviewed);
    void clearPendingChanges();
  }

  interface AnimationPort {
    long nowNanos();
    void request(Runnable frame);
  }

  static final class FrameScheduler implements AnimationPort, AutoCloseable {
    private final AtomicBoolean pending = new AtomicBoolean();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
        runnable -> Thread.ofPlatform().daemon().name("ajent-frame").unstarted(runnable));

    @Override public long nowNanos() { return System.nanoTime(); }

    @Override public void request(Runnable frame) {
      if (!pending.compareAndSet(false, true)) return;
      executor.schedule(() -> {
        pending.set(false);
        frame.run();
      }, 16, TimeUnit.MILLISECONDS);
    }

    @Override public void close() { executor.shutdownNow(); }
  }

  static final class PermissionGate implements PermissionPort {
    private final AtomicReference<Pending> pending = new AtomicReference<>();
    private volatile Runnable changed = () -> {};

    void onChange(Runnable listener) { changed = Objects.requireNonNull(listener, "listener"); }
    ToolUse current() { Pending value = pending.get(); return value == null ? null : value.call(); }

    @Override public Decision request(ToolUse call) {
      var future = new CompletableFuture<Decision>();
      var value = new Pending(call, future);
      if (!pending.compareAndSet(null, value)) return new Decision(false, false);
      changed.run();
      try { return future.join(); }
      finally { pending.compareAndSet(value, null); changed.run(); }
    }

    boolean resolve(boolean approved, boolean always) {
      Pending value = pending.get();
      return value != null && value.decision().complete(new Decision(approved, always));
    }

    void cancel() { resolve(false, false); }
    private record Pending(ToolUse call, CompletableFuture<Decision> decision) {}
  }

  static final class Ui {
    private final Object lock = new Object();
    private final TerminalPort terminal;
    private final AtomicReference<AgentState> agent;
    private final PermissionGate permission;
    private final AnimationPort animations;
    private final Map<String, String> environment;
    private final ClipboardReader clipboard;
    private final TerminalStylePool styles = new TerminalStylePool();
    private InlineFrameRenderer.Frame frame = new InlineFrameRenderer.Empty();
    private CommandPalette.State palette = new CommandPalette.Closed();
    private ToolOutputViewer.State toolViewer = new ToolOutputViewer.Closed();
    private PickerState.OneAxis modelPicker = new PickerState.Closed();
    private PickerState.OneAxis providerPicker = new PickerState.Closed();
    private PickerState.OneAxis threadPicker = new PickerState.Closed();
    private PickerState.Modal plan = new PickerState.ModalClosed();
    private CodeBlockPicker.State codeBlocks = new CodeBlockPicker.Closed();
    private CheckpointPicker.State checkpoints = new CheckpointPicker.Closed();
    private MentionPicker.State mentions = new MentionPicker.Closed();
    private SymbolPicker.State symbols = new SymbolPicker.Closed();
    private boolean checkpointRestoring;
    private List<PlanModal.Item> planItems = List.of();
    private List<ThreadPicker.Entry> threadRows = List.of();
    private boolean threadsLoading;
    private boolean threadLoading;
    private List<ProviderPicker.Provider> providerRows = List.of();
    private LoginModal.State login = new LoginModal.Closed();
    private PickerState.TwoAxis diffReview = new PickerState.CellClosed();
    private List<DiffReview.File> diffFiles = List.of();
    private List<ModelPicker.Model> models = List.of();
    private boolean modelsLoading;
    private Effort effort = Effort.NONE;
    private String uiStatus = "";
    private String composer = "";
    private List<Attachment> composerAttachments = List.of();
    private int cursor;
    private com.github.skanga.ajent.domain.MessageId revealMessage;
    private StreamingMarkdown reveal;
    private final ScrollbackLedger<List<StyledLine>> frozen = new ScrollbackLedger<>();
    private int frozenThrough;
    private int frozenWidth = -1;
    private ThreadId frozenThread;
    private final List<com.github.skanga.ajent.domain.MessageId> frozenIds = new ArrayList<>();

    Ui(TerminalPort terminal, AtomicReference<AgentState> agent, PermissionGate permission) {
      this(terminal, agent, permission, new AnimationPort() {
        @Override public long nowNanos() { return System.nanoTime(); }
        @Override public void request(Runnable frame) { }
      }, System.getenv());
    }

    Ui(TerminalPort terminal, AtomicReference<AgentState> agent, PermissionGate permission,
        AnimationPort animations) {
      this(terminal, agent, permission, animations, System.getenv());
    }

    Ui(TerminalPort terminal, AtomicReference<AgentState> agent, PermissionGate permission,
        AnimationPort animations, Map<String, String> environment) {
      this(terminal, agent, permission, animations, environment,
          new SystemClipboardReader(environment));
    }

    Ui(TerminalPort terminal, AtomicReference<AgentState> agent, PermissionGate permission,
        AnimationPort animations, Map<String, String> environment, ClipboardReader clipboard) {
      this.terminal = terminal;
      this.agent = agent;
      this.permission = permission;
      this.animations = animations;
      this.environment = Map.copyOf(environment);
      this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
    }

    boolean key(TerminalKey key, AgentControl loop) {
      ToolUse pending = permission.current();
      if (pending != null) {
        if (key.key() == TerminalKey.SpecialKey.ESCAPE) permission.resolve(false, false);
        if (key.key() instanceof TerminalKey.CharacterKey character) {
          switch (Character.toLowerCase(character.codePoint())) {
            case 'y' -> permission.resolve(true, false);
            case 'a' -> permission.resolve(true, true);
            case 'n' -> permission.resolve(false, false);
            default -> { }
          }
        }
        return true;
      }
      if (plan instanceof PickerState.OpenModal) {
        if (key.key() == TerminalKey.SpecialKey.ESCAPE) plan = PlanModal.close(plan);
        render();
        return true;
      }
      if (mentions instanceof MentionPicker.Open) return mentionKey(key);
      if (symbols instanceof SymbolPicker.Open) return symbolKey(key);
      if (codeBlocks instanceof CodeBlockPicker.Open) return codeBlockKey(key, loop);
      if (codeBlocks instanceof CodeBlockPicker.Result) return codeResultKey(key);
      if (checkpoints instanceof CheckpointPicker.Open) return checkpointKey(key, loop);
      if (LoginModal.isOpen(login)) return loginKey(key, loop);
      if (diffReview instanceof PickerState.OpenAtCell) return diffReviewKey(key, loop);
      if (palette instanceof CommandPalette.Open) return paletteKey(key, loop);
      if (modelPicker instanceof PickerState.OpenAt) return modelPickerKey(key, loop);
      if (providerPicker instanceof PickerState.OpenAt) return providerPickerKey(key, loop);
      if (threadPicker instanceof PickerState.OpenAt) return threadPickerKey(key, loop);
      if (toolViewer instanceof ToolOutputViewer.Open) return toolViewerKey(key);
      if (key.key() instanceof TerminalKey.CharacterKey character
          && isSmartPasteKey(character.codePoint(), key.modifiers())) {
        smartPaste();
        return true;
      }
      if (key.key() instanceof TerminalKey.CharacterKey character && key.modifiers().ctrl()) {
        int codePoint = Character.toLowerCase(character.codePoint());
        if (codePoint == 'c') return false;
        if (codePoint == 'k') { palette = CommandPalette.open(); render(); return true; }
        if (codePoint == 'j') { openThreadPicker(loop); render(); return true; }
        if (codePoint == 't') { plan = PlanModal.open(); render(); return true; }
        if (codePoint == 'g') { openCodeBlocks(loop); render(); return true; }
        if (codePoint == 'o') { openToolViewer(loop.state()); render(); return true; }
        if (codePoint == 'd' && composer.isEmpty()) return false;
        if (codePoint == 'u') { composer = composer.substring(cursor); cursor = 0; render(); return true; }
      }
      if (key.key() instanceof TerminalKey.SpecialKey special) {
        if (key.modifiers().alt()
            && (special == TerminalKey.SpecialKey.LEFT
                || special == TerminalKey.SpecialKey.RIGHT)) {
          cycleThread(loop, special == TerminalKey.SpecialKey.LEFT ? -1 : 1);
          return true;
        }
        switch (special) {
          case ENTER -> {
            if (key.modifiers().shift() || key.modifiers().alt()) insert("\n");
            else if (!composer.isEmpty()) {
              String submitted = composer;
              List<Attachment> attachments = composerAttachments;
              clearComposer();
              loop.dispatch(new RuntimeMessage.Submit(submitted, List.of(), attachments));
            }
          }
          case BACKSPACE -> {
            if (cursor > 0) {
              int chipLength = AttachmentText.placeholderLengthEndingAt(composer, cursor);
              int previous = chipLength > 0 ? cursor - chipLength
                  : composer.offsetByCodePoints(cursor, -1);
              composer = composer.substring(0, previous) + composer.substring(cursor);
              cursor = previous;
              render();
            }
          }
          case LEFT -> {
            if (cursor > 0) {
              int chipLength = AttachmentText.placeholderLengthEndingAt(composer, cursor);
              cursor = chipLength > 0 ? cursor - chipLength
                  : composer.offsetByCodePoints(cursor, -1);
            }
            render();
          }
          case RIGHT -> {
            if (cursor < composer.length()) {
              int chipLength = AttachmentText.placeholderLengthAt(composer, cursor);
              cursor = chipLength > 0 ? cursor + chipLength
                  : composer.offsetByCodePoints(cursor, 1);
            }
            render();
          }
          case HOME -> { cursor = 0; render(); }
          case END -> { cursor = composer.length(); render(); }
          case ESCAPE -> {
            if (!(loop.state().phase() instanceof SessionPhase.Idle)) loop.dispatch(new RuntimeMessage.Cancel());
          }
          default -> { }
        }
        return true;
      }
      if (key.key() instanceof TerminalKey.CharacterKey character && !key.modifiers().alt()) {
        int codePoint = character.codePoint();
        if ((codePoint == '@' || codePoint == '#') && atWordBoundary()) {
          if (codePoint == '@') mentions = MentionPicker.open(loop.workspaceFiles());
          else symbols = SymbolPicker.open(loop.workspaceSymbols());
          render();
        } else {
          insert(new String(Character.toChars(codePoint)));
        }
      }
      return true;
    }

    private boolean atWordBoundary() {
      if (cursor == 0) return true;
      char previous = composer.charAt(cursor - 1);
      return previous == ' ' || previous == '\t' || previous == '\n';
    }

    private static boolean isSmartPasteKey(
        int codePoint, TerminalKey.Modifiers modifiers) {
      int normalized = codePoint >= 1 && codePoint <= 26 ? 'a' + codePoint - 1
          : Character.toLowerCase(codePoint);
      return normalized == 'v' && (modifiers.ctrl() && !modifiers.alt()
          || modifiers.alt() && !modifiers.ctrl());
    }

    private void smartPaste() {
      Optional<Attachment> image = clipboard.image();
      if (image.isPresent()) {
        insertAttachment(image.orElseThrow());
        return;
      }
      Optional<String> text = clipboard.text();
      if (text.isPresent() && !text.orElseThrow().isEmpty()) {
        paste(text.orElseThrow());
        return;
      }
      uiStatus = "reading clipboard from your terminal\u2026";
      terminal.write(TerminalClipboardQuery.forEnvironment(environment));
      render();
    }

    private boolean mentionKey(TerminalKey key) {
      if (key.key() instanceof TerminalKey.SpecialKey special) {
        switch (special) {
          case ESCAPE -> mentions = MentionPicker.close(mentions);
          case ENTER -> {
            MentionPicker.Selection selected = MentionPicker.select(mentions);
            mentions = selected.state();
            selected.attachment().ifPresent(this::insertAttachment);
          }
          case UP -> mentions = MentionPicker.move(mentions, -1);
          case DOWN -> mentions = MentionPicker.move(mentions, 1);
          case BACKSPACE -> mentions = MentionPicker.backspace(mentions);
          default -> { }
        }
      } else if (key.key() instanceof TerminalKey.CharacterKey character) {
        mentions = MentionPicker.input(mentions, character.codePoint());
      }
      render();
      return true;
    }

    private boolean symbolKey(TerminalKey key) {
      if (key.key() instanceof TerminalKey.SpecialKey special) {
        switch (special) {
          case ESCAPE -> symbols = SymbolPicker.close(symbols);
          case ENTER -> {
            SymbolPicker.Selection selected = SymbolPicker.select(symbols);
            symbols = selected.state();
            selected.attachment().ifPresent(this::insertAttachment);
          }
          case UP -> symbols = SymbolPicker.move(symbols, -1);
          case DOWN -> symbols = SymbolPicker.move(symbols, 1);
          case BACKSPACE -> symbols = SymbolPicker.backspace(symbols);
          default -> { }
        }
      } else if (key.key() instanceof TerminalKey.CharacterKey character) {
        symbols = SymbolPicker.input(symbols, character.codePoint());
      }
      render();
      return true;
    }

    private void insertAttachment(Attachment attachment) {
      int index = composerAttachments.size();
      var revised = new ArrayList<>(composerAttachments);
      revised.add(attachment);
      composerAttachments = List.copyOf(revised);
      insert(AttachmentText.placeholder(index));
    }

    private void openToolViewer(AgentState state) {
      List<ToolOutputViewer.Entry> entries = ToolOutputViewer.collect(state.thread().messages(),
          call -> new ToolOutputViewer.Metadata(displayTool(call.name().value()),
              call.name().value()));
      ToolOutputViewer.Transition transition = ToolOutputViewer.open(entries);
      toolViewer = transition.state();
      uiStatus = transition.status();
    }

    private boolean toolViewerKey(TerminalKey key) {
      if (key.key() instanceof TerminalKey.SpecialKey special) {
        toolViewer = switch (special) {
          case ESCAPE -> ToolOutputViewer.close(toolViewer);
          case ENTER -> ToolOutputViewer.select(toolViewer);
          case UP -> ToolOutputViewer.move(toolViewer, -1);
          case DOWN -> ToolOutputViewer.move(toolViewer, 1);
          case PAGE_UP -> ToolOutputViewer.move(toolViewer, -10);
          case PAGE_DOWN -> ToolOutputViewer.move(toolViewer, 10);
          case HOME -> ToolOutputViewer.move(toolViewer, -1_000_000);
          case END -> ToolOutputViewer.move(toolViewer, 1_000_000);
          case LEFT -> ToolOutputViewer.step(toolViewer, -1);
          case RIGHT -> ToolOutputViewer.step(toolViewer, 1);
          default -> toolViewer;
        };
      } else if (key.key() instanceof TerminalKey.CharacterKey character) {
        switch (Character.toLowerCase(character.codePoint())) {
          case 'k' -> toolViewer = ToolOutputViewer.move(toolViewer, -1);
          case 'j' -> toolViewer = ToolOutputViewer.move(toolViewer, 1);
          case 'h' -> toolViewer = ToolOutputViewer.step(toolViewer, -1);
          case 'l' -> toolViewer = ToolOutputViewer.step(toolViewer, 1);
          case 'q' -> toolViewer = ToolOutputViewer.close(toolViewer);
          case 'y' -> {
            ToolOutputViewer.Transition copied = ToolOutputViewer.copy(toolViewer);
            uiStatus = copied.status();
            copied.clipboard().ifPresent(this::writeClipboard);
          }
          default -> { }
        }
      }
      render();
      return true;
    }

    private void writeClipboard(String value) {
      String encoded = Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      terminal.write("\u001b]52;c;" + encoded + '\u0007');
    }

    private boolean paletteKey(TerminalKey key, AgentControl loop) {
      if (key.key() instanceof TerminalKey.SpecialKey special) {
        switch (special) {
          case ESCAPE -> palette = CommandPalette.close(palette);
          case UP -> palette = CommandPalette.move(palette, -1);
          case DOWN -> palette = CommandPalette.move(palette, 1);
          case BACKSPACE -> palette = CommandPalette.backspace(palette);
          case ENTER -> {
            CommandPalette.Transition transition = CommandPalette.select(palette);
            palette = transition.state();
            if (transition.selected().isPresent()) {
              CommandPalette.Command command = transition.selected().orElseThrow();
              if (command == CommandPalette.Command.QUIT) { render(); return false; }
              if (command == CommandPalette.Command.COMPACT_CONTEXT) {
                loop.dispatch(new RuntimeMessage.CompactContext());
              } else if (command == CommandPalette.Command.NEW_THREAD) {
                loop.newThread();
                resetForThreadSwap();
              } else if (command == CommandPalette.Command.OPEN_THREADS) {
                openThreadPicker(loop);
              } else if (command == CommandPalette.Command.OPEN_PLAN) {
                plan = PlanModal.open();
              } else if (command == CommandPalette.Command.RUN_CODE_BLOCK) {
                openCodeBlocks(loop);
              } else if (command == CommandPalette.Command.REWIND_CHECKPOINT) {
                openCheckpoints(loop);
              } else if (command == CommandPalette.Command.CYCLE_PROFILE) {
                Profile profile = loop.cycleProfile();
                uiStatus = "profile: " + profile.name().toLowerCase(java.util.Locale.ROOT);
              } else if (command == CommandPalette.Command.INSPECT_TOOL_OUTPUTS) {
                openToolViewer(loop.state());
              } else if (command == CommandPalette.Command.OPEN_MODELS) {
                openModelPicker(loop);
              } else if (command == CommandPalette.Command.OPEN_PROVIDERS) {
                providerRows = loop.providers();
                providerPicker = ProviderPicker.open(providerRows, loop.provider());
              } else if (command == CommandPalette.Command.OPEN_LOGIN) {
                login = LoginModal.open();
              } else if (command == CommandPalette.Command.REVIEW_CHANGES) {
                DiffReview.Result opened = DiffReview.open(loop.pendingChanges());
                diffReview = opened.state();
                diffFiles = opened.files();
                uiStatus = opened.status();
              } else if (command == CommandPalette.Command.ACCEPT_ALL) {
                if (diffFiles.isEmpty()) diffFiles = loop.pendingChanges();
                DiffReview.Result accepted = DiffReview.acceptAll(diffReview, diffFiles);
                diffReview = accepted.state();
                diffFiles = accepted.files();
                uiStatus = accepted.status();
                loop.updatePendingChanges(diffFiles);
              } else if (command == CommandPalette.Command.REJECT_ALL) {
                if (diffFiles.isEmpty()) diffFiles = loop.pendingChanges();
                DiffReview.Result rejected = DiffReview.rejectAll(diffReview, diffFiles);
                diffReview = rejected.state();
                diffFiles = rejected.files();
                uiStatus = rejected.status();
                loop.clearPendingChanges();
              }
            }
          }
          default -> { }
        }
      } else if (key.key() instanceof TerminalKey.CharacterKey character
          && !key.modifiers().ctrl() && !key.modifiers().alt()) {
        palette = CommandPalette.input(palette, character.codePoint());
      }
      render();
      return true;
    }

    private void openModelPicker(AgentControl loop) {
      modelsLoading = true;
      effort = loop.effort();
      if (models.isEmpty()) {
        models = List.of(new ModelPicker.Model(loop.model(), loop.model(), false));
      }
      modelPicker = ModelPicker.open(models, loop.model());
      loop.loadModels(loaded -> {
        synchronized (lock) {
          models = List.copyOf(loaded);
          modelsLoading = false;
          modelPicker = ModelPicker.open(models, loop.model());
        }
        render();
      });
    }

    private void openCodeBlocks(AgentControl loop) {
      if (!(loop.state().phase() instanceof SessionPhase.Idle)) {
        uiStatus = "wait for the reply to finish before grabbing blocks";
        return;
      }
      Optional<List<CodeBlockPicker.Block>> blocks = CodeBlockPicker.latestAssistantBlocks(
          loop.state().thread().messages());
      if (blocks.isEmpty()) {
        uiStatus = "no code blocks in the last reply";
        return;
      }
      codeBlocks = CodeBlockPicker.open(blocks.orElseThrow());
    }

    private void openCheckpoints(AgentControl loop) {
      if (!(loop.state().phase() instanceof SessionPhase.Idle) || checkpointRestoring) {
        uiStatus = "cannot rewind while the agent is working";
        return;
      }
      if (!loop.checkpointsAvailable()) {
        uiStatus = "checkpoints need a git repo";
        return;
      }
      List<CheckpointPicker.Entry> entries = CheckpointPicker.entries(
          loop.state().thread().messages());
      if (entries.isEmpty()) {
        uiStatus = "no checkpoints in this thread yet";
        return;
      }
      checkpoints = CheckpointPicker.open(entries);
      for (int index = 0; index < entries.size(); index++) {
        int target = index;
        loop.loadCheckpointDiff(entries.get(index).id(), diff -> {
          synchronized (lock) { checkpoints = CheckpointPicker.diff(checkpoints, target, diff); }
          render();
        });
      }
    }

    private boolean checkpointKey(TerminalKey key, AgentControl loop) {
      if (key.key() instanceof TerminalKey.SpecialKey special) {
        switch (special) {
          case ESCAPE -> checkpoints = CheckpointPicker.close(checkpoints);
          case UP -> checkpoints = CheckpointPicker.move(checkpoints, -1);
          case DOWN -> checkpoints = CheckpointPicker.move(checkpoints, 1);
          case HOME -> {
            if (checkpoints instanceof CheckpointPicker.Open open) {
              checkpoints = new CheckpointPicker.Open(open.entries(), 0);
            }
          }
          case END -> {
            if (checkpoints instanceof CheckpointPicker.Open open) {
              checkpoints = new CheckpointPicker.Open(open.entries(), open.entries().size() - 1);
            }
          }
          case ENTER -> rewindSelected(loop);
          default -> { }
        }
      } else if (key.key() instanceof TerminalKey.CharacterKey character) {
        switch (Character.toLowerCase(character.codePoint())) {
          case 'j' -> checkpoints = CheckpointPicker.move(checkpoints, 1);
          case 'k' -> checkpoints = CheckpointPicker.move(checkpoints, -1);
          case 'q' -> checkpoints = CheckpointPicker.close(checkpoints);
          default -> { }
        }
      }
      render();
      return true;
    }

    private void rewindSelected(AgentControl loop) {
      if (checkpointRestoring) return;
      CheckpointPicker.selected(checkpoints).ifPresent(entry -> {
        checkpoints = CheckpointPicker.close(checkpoints);
        checkpointRestoring = true;
        uiStatus = "rewinding to checkpointâ€¦";
        loop.restoreCheckpoint(entry.id(), result -> {
          synchronized (lock) { checkpointRestoring = false; }
          if (!result.restored()) {
            synchronized (lock) { uiStatus = "rewind failed: " + result.error(); }
            render();
            return;
          }
          resetForThreadSwap();
          insert(result.prompt());
        });
      });
    }

    private boolean codeBlockKey(TerminalKey key, AgentControl loop) {
      if (key.key() instanceof TerminalKey.SpecialKey special) {
        switch (special) {
          case ESCAPE -> codeBlocks = CodeBlockPicker.close(codeBlocks);
          case UP -> codeBlocks = CodeBlockPicker.move(codeBlocks, -1);
          case DOWN -> codeBlocks = CodeBlockPicker.move(codeBlocks, 1);
          case ENTER -> runSelectedBlock(loop, -1);
          default -> { }
        }
      } else if (key.key() instanceof TerminalKey.CharacterKey character) {
        int value = Character.toLowerCase(character.codePoint());
        if (value >= '1' && value <= '9') runSelectedBlock(loop, value - '1');
        else if (value == 'e') CodeBlockPicker.selected(codeBlocks, -1).ifPresent(block -> {
          codeBlocks = CodeBlockPicker.close(codeBlocks);
          insert(block.body());
        });
        else if (value == 'y') CodeBlockPicker.selected(codeBlocks, -1).ifPresent(block -> {
          codeBlocks = CodeBlockPicker.close(codeBlocks);
          writeClipboard(block.body());
          uiStatus = "copied clean block to clipboard";
        });
        else if (value == 'q') codeBlocks = CodeBlockPicker.close(codeBlocks);
      }
      render();
      return true;
    }

    private void runSelectedBlock(AgentControl loop, int index) {
      CodeBlockPicker.selected(codeBlocks, index).ifPresent(block -> {
        CodeBlockPicker.Shell shell = CodeBlockPicker.shell(block.language(), loop.windows());
        if (shell == CodeBlockPicker.Shell.NONE) {
          String tag = block.language().isEmpty() ? "this" : "'" + block.language() + "'";
          uiStatus = tag + " block isn't runnable here â€” press e to edit or y to copy";
          return;
        }
        codeBlocks = CodeBlockPicker.close(codeBlocks);
        loop.runCodeBlock(block, result -> {
          synchronized (lock) { codeBlocks = result; frame = new InlineFrameRenderer.Empty(); }
          render();
        });
      });
    }

    private boolean codeResultKey(TerminalKey key) {
      if (!(codeBlocks instanceof CodeBlockPicker.Result result)) return true;
      if (key.key() instanceof TerminalKey.SpecialKey special) {
        switch (special) {
          case ESCAPE, ENTER -> codeBlocks = CodeBlockPicker.close(codeBlocks);
          case UP -> codeBlocks = CodeBlockPicker.move(codeBlocks, -1);
          case DOWN -> codeBlocks = CodeBlockPicker.move(codeBlocks, 1);
          case PAGE_UP -> codeBlocks = CodeBlockPicker.move(codeBlocks, -10);
          case PAGE_DOWN -> codeBlocks = CodeBlockPicker.move(codeBlocks, 10);
          default -> { }
        }
      } else if (key.key() instanceof TerminalKey.CharacterKey character) {
        switch (Character.toLowerCase(character.codePoint())) {
          case 'a' -> {
            codeBlocks = CodeBlockPicker.close(codeBlocks);
            byte[] body = result.output().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            long lines = result.output().isEmpty() ? 0
                : 1 + result.output().chars().filter(value -> value == '\n').count();
            var attachment = new Attachment(Attachment.Kind.OUTPUT, body, "", "",
                result.command(), 0, Math.toIntExact(lines), body.length);
            int index = composerAttachments.size();
            var revised = new ArrayList<>(composerAttachments);
            revised.add(attachment);
            composerAttachments = List.copyOf(revised);
            insert(AttachmentText.placeholder(index));
            uiStatus = "output attached to composer";
          }
          case 'y' -> {
            writeClipboard(result.output());
            codeBlocks = CodeBlockPicker.close(codeBlocks);
            uiStatus = "output copied to clipboard";
          }
          case 'q', 'd' -> codeBlocks = CodeBlockPicker.close(codeBlocks);
          default -> { }
        }
      }
      render();
      return true;
    }

    private void openThreadPicker(AgentControl loop) {
      threadsLoading = true;
      boolean openedFromEmptyCache = threadRows.isEmpty();
      threadPicker = ThreadPicker.open(threadRows, loop.threadId());
      loop.loadThreads(loaded -> {
        synchronized (lock) {
          threadRows = List.copyOf(loaded);
          threadsLoading = false;
          if (threadPicker instanceof PickerState.OpenAt open) {
            threadPicker = openedFromEmptyCache
                ? ThreadPicker.open(threadRows, loop.threadId())
                : new PickerState.OpenAt(threadRows.isEmpty() ? 0
                    : Math.max(0, Math.min(open.index(), threadRows.size() - 1)), "");
          }
        }
        render();
      });
    }

    private boolean threadPickerKey(TerminalKey key, AgentControl loop) {
      if (key.key() instanceof TerminalKey.SpecialKey special) {
        switch (special) {
          case ESCAPE -> threadPicker = ThreadPicker.close(threadPicker);
          case UP -> threadPicker = ThreadPicker.move(threadPicker, threadRows, -1);
          case DOWN -> threadPicker = ThreadPicker.move(threadPicker, threadRows, 1);
          case HOME -> threadPicker = ThreadPicker.jump(
              threadPicker, threadRows, ThreadPicker.Jump.HOME);
          case END -> threadPicker = ThreadPicker.jump(
              threadPicker, threadRows, ThreadPicker.Jump.END);
          case PAGE_UP -> threadPicker = ThreadPicker.jump(
              threadPicker, threadRows, ThreadPicker.Jump.PAGE_UP);
          case PAGE_DOWN -> threadPicker = ThreadPicker.jump(
              threadPicker, threadRows, ThreadPicker.Jump.PAGE_DOWN);
          case ENTER -> selectThread(loop, "");
          default -> { }
        }
      } else if (key.key() instanceof TerminalKey.CharacterKey character
          && Character.toLowerCase(character.codePoint()) == 'n') {
        loop.newThread();
        resetForThreadSwap();
        return true;
      }
      render();
      return true;
    }

    private void selectThread(AgentControl loop, String successStatus) {
      if (threadLoading) return;
      ThreadPicker.Selection selection = ThreadPicker.select(threadPicker, threadRows);
      threadPicker = selection.state();
      selection.entry().ifPresent(entry -> {
        if (entry.id().equals(loop.threadId())) return;
        threadLoading = true;
        uiStatus = "loading threadâ€¦";
        loop.loadThread(entry.id(), failure -> {
          synchronized (lock) {
            threadLoading = false;
            if (!failure.isEmpty()) {
              uiStatus = "error: " + failure;
            }
          }
          if (failure.isEmpty()) {
            resetForThreadSwap(successStatus);
          }
          else render();
        });
      });
    }

    private void cycleThread(AgentControl loop, int delta) {
      if (!(loop.state().phase() instanceof SessionPhase.Idle)) {
        uiStatus = "wait for the reply to finish before switching threads";
        render();
        return;
      }
      if (threadLoading) return;
      if (threadRows.isEmpty()) {
        uiStatus = "no other threads yet";
        loop.loadThreads(loaded -> { synchronized (lock) { threadRows = List.copyOf(loaded); } });
        render();
        return;
      }
      int current = -1;
      for (int index = 0; index < threadRows.size(); index++) {
        if (threadRows.get(index).id().equals(loop.threadId())) current = index;
      }
      if (current >= 0 && threadRows.size() == 1) {
        uiStatus = "only one thread";
        render();
        return;
      }
      int target = current < 0 ? (delta >= 0 ? 0 : threadRows.size() - 1)
          : Math.floorMod(current + delta, threadRows.size());
      ThreadPicker.Entry entry = threadRows.get(target);
      threadPicker = new PickerState.OpenAt(target, "");
      uiStatus = "thread " + (target + 1) + "/" + threadRows.size()
          + " Â· " + entry.displayTitle();
      selectThread(loop, uiStatus);
    }

    private boolean modelPickerKey(TerminalKey key, AgentControl loop) {
      if (key.key() instanceof TerminalKey.SpecialKey special) {
        switch (special) {
          case ESCAPE -> modelPicker = ModelPicker.close(modelPicker);
          case ENTER -> {
            ModelPicker.Selection selected = ModelPicker.select(modelPicker, models);
            modelPicker = selected.state();
            selected.model().ifPresent(model -> {
              loop.selectModel(model.id());
              effort = effort.clamp(ModelCapabilities.fromId(model.id()));
              uiStatus = "model: " + model.displayName();
            });
          }
          case LEFT, RIGHT -> {
            effort = ModelPicker.cycleEffort(modelPicker, models, effort,
                special == TerminalKey.SpecialKey.LEFT ? -1 : 1);
            loop.setEffort(effort);
            uiStatus = "reasoning effort: " + effort.label();
          }
          case UP -> modelPicker = ModelPicker.move(modelPicker, models, -1);
          case DOWN -> modelPicker = ModelPicker.move(modelPicker, models, 1);
          case HOME -> modelPicker = ModelPicker.jump(modelPicker, models, ModelPicker.Jump.HOME);
          case END -> modelPicker = ModelPicker.jump(modelPicker, models, ModelPicker.Jump.END);
          case PAGE_UP -> modelPicker = ModelPicker.jump(
              modelPicker, models, ModelPicker.Jump.PAGE_UP);
          case PAGE_DOWN -> modelPicker = ModelPicker.jump(
              modelPicker, models, ModelPicker.Jump.PAGE_DOWN);
          case BACKSPACE -> modelPicker = ModelPicker.backspace(modelPicker, models);
          default -> { }
        }
      } else if (key.key() instanceof TerminalKey.CharacterKey character
          && !key.modifiers().ctrl() && !key.modifiers().alt()) {
        if (Character.toLowerCase(character.codePoint()) == 'f') {
          ModelPicker.FavoriteResult changed = ModelPicker.toggleFavorite(modelPicker, models);
          modelPicker = changed.state();
          models = changed.models();
          loop.saveFavorites(models.stream().filter(ModelPicker.Model::favorite)
              .map(ModelPicker.Model::id).toList());
        } else {
          modelPicker = ModelPicker.input(modelPicker, models, character.codePoint());
        }
      }
      render();
      return true;
    }

    private boolean providerPickerKey(TerminalKey key, AgentControl loop) {
      List<ProviderPicker.Provider> providers = providerRows;
      if (key.key() instanceof TerminalKey.SpecialKey special) {
        switch (special) {
          case ESCAPE -> providerPicker = new PickerState.Closed();
          case UP -> providerPicker = ProviderPicker.move(providerPicker, providers, -1);
          case DOWN -> providerPicker = ProviderPicker.move(providerPicker, providers, 1);
          case HOME -> providerPicker = ProviderPicker.jump(
              providerPicker, providers, ProviderPicker.Jump.HOME);
          case END -> providerPicker = ProviderPicker.jump(
              providerPicker, providers, ProviderPicker.Jump.END);
          case PAGE_UP -> providerPicker = ProviderPicker.jump(
              providerPicker, providers, ProviderPicker.Jump.PAGE_UP);
          case PAGE_DOWN -> providerPicker = ProviderPicker.jump(
              providerPicker, providers, ProviderPicker.Jump.PAGE_DOWN);
          case ENTER -> {
            ProviderPicker.Selection selected = ProviderPicker.select(providerPicker, providers);
            providerPicker = selected.state();
            selected.action().ifPresent(action -> {
              if (action instanceof ProviderPicker.SelectProvider choice) {
                if (loop.selectProvider(choice.provider().id())) {
                  uiStatus = "provider: " + choice.provider().label();
                  models = List.of();
                  openModelPicker(loop);
                } else {
                  login = new LoginModal.ApiKeyInput(
                      new com.github.skanga.ajent.terminal.ui.Utf8Editor(),
                      choice.provider().id(), choice.provider().label());
                }
              } else {
                login = new LoginModal.CustomHostInput();
              }
            });
          }
          default -> { }
        }
      }
      render();
      return true;
    }

    private boolean loginKey(TerminalKey key, AgentControl loop) {
      if (key.key() == TerminalKey.SpecialKey.ESCAPE) {
        login = LoginModal.close(login);
      } else if (key.key() == TerminalKey.SpecialKey.ENTER) {
        applyLogin(LoginModal.submit(login), loop);
      } else if (key.key() == TerminalKey.SpecialKey.BACKSPACE) {
        login = LoginModal.backspace(login);
      } else if (key.key() == TerminalKey.SpecialKey.LEFT) {
        login = LoginModal.left(login);
      } else if (key.key() == TerminalKey.SpecialKey.RIGHT) {
        login = LoginModal.right(login);
      } else if (key.key() instanceof TerminalKey.CharacterKey character
          && !key.modifiers().ctrl() && !key.modifiers().alt()) {
        if (login instanceof LoginModal.Picking || login instanceof LoginModal.Failed) {
          applyLogin(LoginModal.pick(login, character.codePoint(), loop::newOAuthAttempt), loop);
        } else {
          login = LoginModal.input(login, character.codePoint());
        }
      }
      render();
      return true;
    }

    private boolean diffReviewKey(TerminalKey key, AgentControl loop) {
      if (key.key() instanceof TerminalKey.SpecialKey special) {
        diffReview = switch (special) {
          case ESCAPE -> DiffReview.close(diffReview);
          case UP -> DiffReview.move(diffReview, diffFiles, -1);
          case DOWN -> DiffReview.move(diffReview, diffFiles, 1);
          case LEFT -> DiffReview.previousFile(diffReview, diffFiles);
          case RIGHT -> DiffReview.nextFile(diffReview, diffFiles);
          default -> diffReview;
        };
      } else if (key.key() instanceof TerminalKey.CharacterKey character) {
        DiffReview.Result changed = switch (Character.toLowerCase(character.codePoint())) {
          case 'y' -> DiffReview.acceptHunk(diffReview, diffFiles);
          case 'n' -> DiffReview.rejectHunk(diffReview, diffFiles);
          case 'a' -> DiffReview.acceptAll(diffReview, diffFiles);
          case 'x' -> DiffReview.rejectAll(diffReview, diffFiles);
          default -> new DiffReview.Result(diffReview, diffFiles, "");
        };
        diffReview = changed.state();
        diffFiles = changed.files();
        uiStatus = changed.status();
        loop.updatePendingChanges(diffFiles);
        if (Character.toLowerCase(character.codePoint()) == 'x') loop.clearPendingChanges();
      }
      render();
      return true;
    }

    private void applyLogin(LoginModal.Transition transition, AgentControl loop) {
      login = transition.state();
      transition.action().ifPresent(action -> {
        switch (action) {
          case LoginModal.OpenBrowser browser -> loop.openBrowser(browser.uri());
          case LoginModal.InstallAnthropicKey key -> {
            if (!loop.installAnthropicKey(key.key())) {
              login = new LoginModal.Failed("save failed");
              uiStatus = "error: save failed";
            }
            else uiStatus = "logged in: Anthropic API key";
          }
          case LoginModal.InstallProviderKey key -> {
            if (!loop.installProviderKey(key.provider(), key.key())) {
              login = new LoginModal.Failed("save failed");
            } else {
              uiStatus = "provider: " + key.providerLabel();
              models = List.of();
              openModelPicker(loop);
            }
          }
          case LoginModal.SwitchCustomHost host -> {
            if (!loop.switchCustomHost(host.specification())) {
              login = new LoginModal.Failed("save failed");
            } else {
              uiStatus = "provider: " + host.specification();
              models = List.of();
              openModelPicker(loop);
            }
          }
          case LoginModal.ExchangeOAuth exchange -> loop.exchangeOAuth(exchange, failure -> {
            synchronized (lock) {
              login = failure.isEmpty() ? new LoginModal.Closed()
                  : new LoginModal.Failed(failure);
              if (failure.isEmpty()) uiStatus = "logged in: Anthropic OAuth";
            }
            render();
          });
        }
      });
    }

    private void resetForThreadSwap() {
      resetForThreadSwap("");
    }

    private void resetForThreadSwap(String status) {
      synchronized (lock) {
        composer = "";
        composerAttachments = List.of();
        cursor = 0;
        revealMessage = null;
        reveal = null;
        frozen.clear();
        frozenThrough = 0;
        frozenWidth = -1;
        frozenThread = null;
        frozenIds.clear();
        palette = new CommandPalette.Closed();
        toolViewer = new ToolOutputViewer.Closed();
        modelPicker = new PickerState.Closed();
        providerPicker = new PickerState.Closed();
        threadPicker = new PickerState.Closed();
        plan = new PickerState.ModalClosed();
        codeBlocks = new CodeBlockPicker.Closed();
        checkpoints = new CheckpointPicker.Closed();
        mentions = new MentionPicker.Closed();
        symbols = new SymbolPicker.Closed();
        checkpointRestoring = false;
        login = new LoginModal.Closed();
        diffReview = new PickerState.CellClosed();
        diffFiles = List.of();
        threadLoading = false;
        uiStatus = status;
        frame = new InlineFrameRenderer.Empty();
        terminal.write("\u001b[2J\u001b[3J\u001b[H");
      }
      render();
    }

    void insert(String value) {
      synchronized (lock) {
        composer = composer.substring(0, cursor) + value + composer.substring(cursor);
        cursor += value.length();
      }
      render();
    }

    void paste(String value) {
      paste(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    void paste(byte[] value) {
      if (LoginModal.isInputState(login)) {
        login = LoginModal.paste(login,
            new String(value, java.nio.charset.StandardCharsets.UTF_8));
        render();
        return;
      }
      if (value.length == 0) {
        smartPaste();
        return;
      }
      Optional<Attachment> rawImage = ImagePaste.raw(value, "<paste>");
      if (rawImage.isPresent()) {
        insertAttachment(rawImage.orElseThrow());
        return;
      }
      String pasted = new String(value, java.nio.charset.StandardCharsets.UTF_8);
      Optional<Attachment> pathImage = ImagePaste.path(pasted, environment);
      if (pathImage.isPresent()) {
        insertAttachment(pathImage.orElseThrow());
        return;
      }
      String normalized = normalizePaste(pasted);
      byte[] body = normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      long newlines = normalized.chars().filter(codePoint -> codePoint == '\n').count();
      int lines = Math.toIntExact(newlines + (normalized.endsWith("\n") ? 0 : 1));
      insertAttachment(new Attachment(
          Attachment.Kind.PASTE, body, "", "", "", 0, lines, body.length));
    }

    private static String normalizePaste(String value) {
      var normalized = new StringBuilder(value.length());
      for (int index = 0; index < value.length(); index++) {
        char current = value.charAt(index);
        if (current != '\r') {
          normalized.append(current);
          continue;
        }
        normalized.append('\n');
        if (index + 1 < value.length() && value.charAt(index + 1) == '\n') index++;
      }
      return normalized.toString();
    }

    private void clearComposer() {
      synchronized (lock) { composer = ""; composerAttachments = List.of(); cursor = 0; }
      render();
    }

    void render() {
      synchronized (lock) {
        AgentState state = agent.get();
        if (state == null) return;
        JLineTerminalSession.Size size = terminal.size();
        int width = Math.max(1, size.columns());
        RenderedLines rendered = lines(state, permission.current(), width, Math.max(1, size.rows()), composer,
            animations.nowNanos());
        List<StyledLine> lines = rendered.lines();
        if (palette instanceof CommandPalette.Open open) {
          lines = new ArrayList<>(lines);
          lines.add(new StyledLine("", Style.NORMAL));
          lines.add(new StyledLine("Commands  " + open.query(), Style.ACCENT));
          List<CommandPalette.Command> matches = CommandPalette.filtered(open.query());
          for (int index = 0; index < matches.size(); index++) {
            CommandPalette.Command command = matches.get(index);
            lines.add(new StyledLine((index == open.index() ? "› " : "  ") + command.label(),
                index == open.index() ? Style.ACCENT : Style.NORMAL));
          }
        }
        if (toolViewer instanceof ToolOutputViewer.Open open) {
          lines = new ArrayList<>(lines);
          lines.add(new StyledLine("", Style.NORMAL));
          if (open.viewing() && !open.entries().isEmpty()) {
            ToolOutputViewer.Entry entry = open.entries().get(open.index());
            lines.add(new StyledLine(entry.title() + "  " + entry.trailing(), Style.ACCENT));
            wrap(lines, entry.output(), width, entry.failed() ? Style.DANGER : Style.NORMAL);
          } else {
            lines.add(new StyledLine("Tool outputs", Style.ACCENT));
            for (int index = 0; index < open.entries().size(); index++) {
              ToolOutputViewer.Entry entry = open.entries().get(index);
              lines.add(new StyledLine((index == open.index() ? "› " : "  ")
                  + entry.title() + "  " + entry.trailing(),
                  entry.failed() ? Style.DANGER : index == open.index()
                      ? Style.ACCENT : Style.NORMAL));
            }
          }
        }
        if (mentions instanceof MentionPicker.Open open) {
          lines = new ArrayList<>(lines);
          lines.add(new StyledLine("", Style.NORMAL));
          lines.add(new StyledLine("Mention File", Style.ACCENT));
          lines.add(new StyledLine(open.query().isEmpty()
              ? "@ type to filter files\u2026" : "@ " + open.query(), Style.MUTED));
          List<Integer> matches = MentionPicker.matches(open);
          if (open.files().isEmpty()) {
            lines.add(new StyledLine("  workspace empty (or no readable files)", Style.MUTED));
          } else if (matches.isEmpty()) {
            lines.add(new StyledLine("  no matches", Style.MUTED));
          } else {
            int start = pickerStart(open.index(), matches.size());
            int end = Math.min(matches.size(), start + 14);
            for (int index = start; index < end; index++) {
              String path = open.files().get(matches.get(index));
              String parent = parentSegment(path);
              lines.add(new StyledLine((index == open.index() ? "\u203a " : "  ")
                  + filenameOnly(path) + (parent.isEmpty() ? "" : "  " + parent),
                  index == open.index() ? Style.ACCENT : Style.NORMAL));
            }
            if (matches.size() > 14) lines.add(new StyledLine(
                "  " + (open.index() + 1) + "/" + matches.size(), Style.MUTED));
          }
        }
        if (symbols instanceof SymbolPicker.Open open) {
          lines = new ArrayList<>(lines);
          lines.add(new StyledLine("", Style.NORMAL));
          lines.add(new StyledLine("Symbol", Style.ACCENT));
          lines.add(new StyledLine(open.query().isEmpty()
              ? "# type to filter symbols\u2026" : "# " + open.query(), Style.MUTED));
          List<Integer> matches = SymbolPicker.matches(open);
          if (open.symbols().isEmpty()) {
            lines.add(new StyledLine("  no symbols indexed", Style.MUTED));
          } else if (matches.isEmpty()) {
            lines.add(new StyledLine("  no matches", Style.MUTED));
          } else {
            int start = pickerStart(open.index(), matches.size());
            int end = Math.min(matches.size(), start + 14);
            for (int index = start; index < end; index++) {
              WorkspaceSymbol symbol = open.symbols().get(matches.get(index));
              String parent = parentSegment(symbol.path());
              lines.add(new StyledLine((index == open.index() ? "\u203a " : "  ")
                  + symbol.name() + "  " + filenameOnly(symbol.path()) + ":"
                  + symbol.lineNumber() + (parent.isEmpty() ? "" : "  " + parent),
                  index == open.index() ? Style.ACCENT : Style.NORMAL));
            }
            if (matches.size() > 14) lines.add(new StyledLine(
                "  " + (open.index() + 1) + "/" + matches.size(), Style.MUTED));
          }
        }
        if (modelPicker instanceof PickerState.OpenAt open) {
          lines = new ArrayList<>(lines);
          lines.add(new StyledLine("", Style.NORMAL));
          lines.add(new StyledLine("Models  " + open.query()
              + (modelsLoading ? "  loading…" : ""), Style.ACCENT));
          List<Integer> visible = ModelPicker.filteredIndices(models, open.query());
          for (int index = 0; index < visible.size(); index++) {
            ModelPicker.Model model = models.get(visible.get(index));
            String marker = index == open.index() ? "› " : "  ";
            String favorite = model.favorite() ? "★ " : "  ";
            boolean selected = index == open.index();
            boolean supportsEffort = ModelCapabilities.fromId(model.id()).supportsEffort();
            String trailing = selected && supportsEffort && effort != Effort.NONE
                ? "  \u25c7 " + effort.label() : "";
            lines.add(new StyledLine(marker + favorite + model.displayName() + trailing,
                index == open.index() ? Style.ACCENT : Style.NORMAL));
          }
          Optional<ModelPicker.Model> selected = ModelPicker.select(modelPicker, models).model();
          if (selected.isPresent() && ModelCapabilities
              .fromId(selected.orElseThrow().id()).supportsEffort()) {
            lines.add(new StyledLine("reasoning effort: " + effort.label()
                + "  \u2190/\u2192 change", Style.MUTED));
          }
        }
        if (providerPicker instanceof PickerState.OpenAt open) {
          lines = new ArrayList<>(lines);
          lines.add(new StyledLine("", Style.NORMAL));
          lines.add(new StyledLine("Providers", Style.ACCENT));
          List<ProviderPicker.Provider> providers = providerRows;
          for (int index = 0; index <= providers.size(); index++) {
            String label = index == providers.size()
                ? "Custom host…" : providers.get(index).label();
            lines.add(new StyledLine((index == open.index() ? "› " : "  ") + label,
                index == open.index() ? Style.ACCENT : Style.NORMAL));
          }
        }
        if (threadPicker instanceof PickerState.OpenAt open) {
          lines = new ArrayList<>(lines);
          lines.add(new StyledLine("", Style.NORMAL));
          lines.add(new StyledLine("Threads", Style.ACCENT));
          if (threadRows.isEmpty()) {
            lines.add(new StyledLine(threadsLoading
                ? "  Loading conversationsâ€¦" : "  No threads yet.", Style.MUTED));
          } else {
            ThreadId current = state.thread().id();
            for (int index = 0; index < threadRows.size(); index++) {
              ThreadPicker.Entry entry = threadRows.get(index);
              String selected = index == open.index() ? "â€º " : "  ";
              String active = entry.id().equals(current) ? "â— " : "  ";
              lines.add(new StyledLine(selected + active + entry.displayTitle()
                  + "  " + entry.updatedAt(), index == open.index() || entry.id().equals(current)
                      ? Style.ACCENT : Style.MUTED));
            }
            lines.add(new StyledLine("  " + (open.index() + 1) + "/" + threadRows.size(),
                Style.MUTED));
          }
          lines.add(new StyledLine(
              "â†‘â†“ move  PgUp/PgDn page  Enter open  N new  Esc close", Style.MUTED));
        }
        if (plan instanceof PickerState.OpenModal) {
          lines = new ArrayList<>(lines);
          lines.add(new StyledLine("", Style.NORMAL));
          lines.add(new StyledLine("Plan", Style.ACCENT));
          if (planItems.isEmpty()) {
            lines.add(new StyledLine("  No tasks yet.", Style.MUTED));
            lines.add(new StyledLine("  The agent will create tasks as it works.", Style.MUTED));
          } else {
            for (PlanModal.Item item : planItems) {
              String marker = switch (item.status()) {
                case PENDING -> "[ ] ";
                case IN_PROGRESS -> "[-] ";
                case COMPLETED -> "[x] ";
              };
              wrap(lines, marker + item.content(), width,
                  item.status() == PlanModal.Status.IN_PROGRESS ? Style.ACCENT : Style.NORMAL);
            }
            PlanModal.Progress progress = PlanModal.progress(planItems);
            lines.add(new StyledLine(progress.completed() + "/" + progress.total()
                + " completed", progress.completed() == progress.total()
                    ? Style.ACCENT : Style.MUTED));
          }
          lines.add(new StyledLine("Esc close", Style.MUTED));
        }
        if (codeBlocks instanceof CodeBlockPicker.Open open) {
          lines = new ArrayList<>(lines);
          lines.add(new StyledLine("", Style.NORMAL));
          lines.add(new StyledLine("Run Code Block", Style.ACCENT));
          for (int index = 0; index < open.blocks().size(); index++) {
            CodeBlockPicker.Block block = open.blocks().get(index);
            String language = block.language().isEmpty() ? "sh" : block.language();
            lines.add(new StyledLine((index == open.index() ? "â€º " : "  ")
                + (index + 1) + "  " + block.preview() + "  " + language + " Â· "
                + block.lineCount() + (block.lineCount() == 1 ? " line" : " lines"),
                index == open.index() ? Style.ACCENT : Style.NORMAL));
          }
          lines.add(new StyledLine(
              "â†‘â†“ move  Enter/1-9 run  e edit  y copy  Esc close", Style.MUTED));
        }
        if (codeBlocks instanceof CodeBlockPicker.Result result) {
          lines = new ArrayList<>(lines);
          lines.add(new StyledLine("", Style.NORMAL));
          lines.add(new StyledLine("Run Result", result.exitCode() == 0 && !result.timedOut()
              ? Style.ACCENT : Style.DANGER));
          String command = result.command().lines().findFirst().orElse("");
          if (result.command().contains("\n")) command += " â€¦";
          lines.add(new StyledLine("$ " + command, Style.NORMAL));
          int outputLines = result.output().isEmpty() ? 0
              : 1 + (int) result.output().chars().filter(value -> value == '\n').count();
          lines.add(new StyledLine((result.timedOut() ? "timed out" : "exit " + result.exitCode())
              + " Â· " + outputLines + " lines Â· " + result.output().getBytes(
                  java.nio.charset.StandardCharsets.UTF_8).length + " B", Style.MUTED));
          List<String> output = result.output().lines().toList();
          if (output.isEmpty()) lines.add(new StyledLine("  (no output captured)", Style.MUTED));
          else for (String line : output.stream().skip(result.scroll()).limit(14).toList()) {
            wrap(lines, "  " + line, width, Style.MUTED);
          }
          lines.add(new StyledLine("a attach to composer  y copy  Esc discard", Style.MUTED));
        }
        if (checkpoints instanceof CheckpointPicker.Open open) {
          lines = new ArrayList<>(lines);
          lines.add(new StyledLine("", Style.NORMAL));
          lines.add(new StyledLine("Rewind to Checkpoint", Style.ACCENT));
          for (int index = 0; index < open.entries().size(); index++) {
            CheckpointPicker.Entry entry = open.entries().get(index);
            String diff = switch (entry.diffState()) {
              case LOADING -> "\u2026";
              case FAILED -> "";
              case READY -> entry.clean() ? "no changes" : entry.filesChanged() + " files \u00b7 +"
                  + entry.insertions() + " \u2212" + entry.deletions();
            };
            lines.add(new StyledLine((index == open.index() ? "\u203a " : "  ") + "Turn "
                + entry.turn() + "  " + entry.preview() + (diff.isEmpty() ? "" : "  " + diff),
                index == open.index() ? Style.ACCENT : Style.NORMAL));
          }
          lines.add(new StyledLine("\u2191\u2193 move  Enter rewind  Esc close", Style.MUTED));
        }
        if (LoginModal.isOpen(login)) {
          lines = new ArrayList<>(lines);
          lines.add(new StyledLine("", Style.NORMAL));
          lines.add(new StyledLine("Login", Style.ACCENT));
        }
        switch (login) {
            case LoginModal.Picking ignored -> {
              lines.add(new StyledLine("1  OAuth via claude.ai", Style.NORMAL));
              lines.add(new StyledLine("2  Paste API key", Style.NORMAL));
            }
            case LoginModal.OAuthCode oauth -> {
              wrap(lines, oauth.authorizeUri().toString(), width, Style.MUTED);
              wrap(lines, "Code: " + oauth.code().text(), width, Style.NORMAL);
            }
            case LoginModal.OAuthExchanging ignored ->
                lines.add(new StyledLine("Exchanging OAuth code…", Style.MUTED));
            case LoginModal.ApiKeyInput key -> lines.add(new StyledLine(
                (key.providerLabel().isEmpty() ? "Anthropic" : key.providerLabel())
                    + " API key: " + "•".repeat(key.key().text().codePointCount(
                        0, key.key().text().length())), Style.NORMAL));
            case LoginModal.CustomHostInput host ->
                lines.add(new StyledLine("Host: " + host.host().text(), Style.NORMAL));
            case LoginModal.Failed failed ->
                lines.add(new StyledLine("error: " + failed.message(), Style.DANGER));
            case LoginModal.Closed ignored -> { }
        }
        if (diffReview instanceof PickerState.OpenAtCell cell && !diffFiles.isEmpty()) {
          DiffReview.File file = diffFiles.get(cell.fileIndex());
          DiffReview.Hunk hunk = file.hunks().get(cell.hunkIndex());
          lines = new ArrayList<>(lines);
          lines.add(new StyledLine("", Style.NORMAL));
          lines.add(new StyledLine("Changes  " + file.path() + "  +" + file.added()
              + " -" + file.removed() + "  file " + (cell.fileIndex() + 1) + "/"
              + diffFiles.size(), Style.ACCENT));
          lines.add(new StyledLine(
              "[y] accept  [n] reject  [a] all  [x] none  ←/→ file", Style.MUTED));
          lines.add(new StyledLine(hunk.header() + "  " + switch (hunk.status()) {
            case PENDING -> "[ pending ]";
            case ACCEPTED -> "[✓ accepted]";
            case REJECTED -> "[✗ rejected]";
          }, Style.MUTED));
          wrap(lines, hunk.patch(), width, switch (hunk.status()) {
            case PENDING -> Style.NORMAL;
            case ACCEPTED -> Style.ACCENT;
            case REJECTED -> Style.DANGER;
          });
        }
        var canvas = new TerminalCanvas(width, Math.max(1, lines.size()));
        int normal = 0;
        int accent = styles.intern(TerminalStyle.EMPTY.withForeground(TerminalColor.cyan()).withBold());
        int muted = styles.intern(TerminalStyle.EMPTY.withForeground(TerminalColor.brightBlack()));
        int danger = styles.intern(TerminalStyle.EMPTY.withForeground(TerminalColor.red()).withBold());
        int success = styles.intern(TerminalStyle.EMPTY.withForeground(TerminalColor.green()).withBold());
        for (int row = 0; row < lines.size(); row++) {
          StyledLine line = lines.get(row);
          int style = switch (line.style()) {
            case NORMAL -> normal; case ACCENT -> accent; case MUTED -> muted;
            case DANGER -> danger; case SUCCESS -> success;
          };
          if (line.spans().isEmpty()) {
            canvas.writeText(0, row, line.text(), style);
          } else {
            int column = 0;
            for (StyledSpan span : line.spans()) {
              canvas.writeText(column, row, span.text(), styles.intern(span.style()));
              column += com.github.skanga.ajent.terminal.render.UnicodeWidth.stringWidth(
                  span.text(), com.github.skanga.ajent.terminal.render.UnicodeWidth.Mode.MODERN);
            }
          }
        }
        var rows = CanvasSerializer.contentRows(canvas);
        recordFrozenPaint(width);
        if (rendered.scrollbackDebt().isPresent()) {
          frame = InlineFrameRenderer.commitScrollback(
              frame, rendered.scrollbackDebt().orElseThrow());
        }
        frame = render(frame, canvas, rows, Math.max(1, size.rows()), styles,
            value -> terminal.write(value));
        if (rendered.animating()) animations.request(this::render);
      }
    }

    private void recordFrozenPaint(int width) {
      frozen.recordPaintWidth(width);
      List<List<StyledLine>> blocks = frozen.elements();
      for (int index = 0; index < blocks.size(); index++) {
        frozen.recordPaint(index, blocks.get(index).size());
      }
    }

    int frozenThrough() {
      synchronized (lock) { return frozenThrough; }
    }

    long frozenRows() {
      synchronized (lock) { return frozen.rowTotal(); }
    }

    int frozenBlocks() {
      synchronized (lock) { return frozen.size(); }
    }

    String liveRevealContent() {
      synchronized (lock) { return reveal == null ? "" : reveal.content(); }
    }

    String frozenText() {
      synchronized (lock) {
        var text = new StringBuilder();
        for (List<StyledLine> block : frozen.elements()) {
          for (StyledLine line : block) text.append(line.text()).append('\n');
        }
        return text.toString();
      }
    }

    private static InlineFrameRenderer.Frame render(InlineFrameRenderer.Frame frame,
        TerminalCanvas canvas, CanvasSerializer.ContentRows rows, int terminalRows,
        TerminalStylePool styles, InlineFrameRenderer.FrameWriter writer) {
      return switch (frame) {
        case InlineFrameRenderer.Empty empty -> empty.seed().render(
            canvas, rows, terminalRows, styles, writer, false);
        case InlineFrameRenderer.Fresh fresh -> fresh.render(
            canvas, rows, terminalRows, styles, writer, false);
        case InlineFrameRenderer.Synced synced -> {
          var witness = synced.verify();
          var proof = synced.checkScrollback(canvas, terminalRows);
          if (witness.isPresent() && proof.isPresent()) {
            yield synced.render(canvas, rows, terminalRows, styles, writer,
                witness.orElseThrow(), proof.orElseThrow(), false);
          }
          if (witness.isPresent()) {
            yield synced.commitScrollbackOverflow(terminalRows).demoteToStale()
                .render(canvas, rows, terminalRows, styles, writer, false);
          }
          yield synced.demoteToHardReset()
              .render(canvas, rows, terminalRows, styles, writer, false);
        }
        case InlineFrameRenderer.Stale stale -> stale.render(
            canvas, rows, terminalRows, styles, writer, false);
        case InlineFrameRenderer.HardReset reset -> reset.render(
            canvas, rows, terminalRows, styles, writer, false);
        case InlineFrameRenderer.Sealed ignored -> throw new IllegalStateException("renderer sealed");
      };
    }

    private RenderedLines lines(AgentState state, ToolUse permission, int width, int terminalRows,
        String composer, long nowNanos) {
      var output = new ArrayList<StyledLine>();
      boolean animating = false;
      List<Message> messages = state.thread().messages();
      reconcileFrozenSurface(state, messages, width, terminalRows, nowNanos);
      if (messages.isEmpty()) {
        output.add(new StyledLine("Ajent", Style.ACCENT));
        output.add(new StyledLine("AI coding agent \u00b7 Ctrl-D to quit", Style.MUTED));
      }

      int freezeLimit = freezeLimit(state, messages);
      while (frozenThrough < freezeLimit) {
        int messageIndex = frozenThrough;
        int runEnd = messageIndex + 1;
        if (messages.get(messageIndex).role() == Role.ASSISTANT) {
          while (runEnd < freezeLimit && messages.get(runEnd).role() == Role.ASSISTANT) runEnd++;
        }
        if (!frozen.isEmpty()) {
          frozen.seal(List.of(new StyledLine("", Style.NORMAL)), 1, true);
        }
        var sealed = new ArrayList<StyledLine>();
        for (int index = messageIndex; index < runEnd; index++) {
          MessageRender rendered = renderMessage(state, messages.get(index), index,
              messages.size(), width, terminalRows, nowNanos, false, index == messageIndex);
          sealed.addAll(rendered.lines());
          frozenIds.add(messages.get(index).id());
        }
        frozen.seal(List.copyOf(sealed), Math.max(1, sealed.size()), false);
        frozenThrough = runEnd;
      }

      FrozenScrollbackTrimPolicy.TrimResult trim =
          FrozenScrollbackTrimPolicy.trim(frozen, terminalRows);
      for (List<StyledLine> block : frozen.elements()) output.addAll(block);
      for (int messageIndex = frozenThrough; messageIndex < messages.size(); messageIndex++) {
        if (!output.isEmpty()) output.add(new StyledLine("", Style.NORMAL));
        MessageRender rendered = renderMessage(state, messages.get(messageIndex), messageIndex,
            messages.size(), width, terminalRows, nowNanos, true,
            messageIndex == frozenThrough
                || messages.get(messageIndex - 1).role() != Role.ASSISTANT);
        output.addAll(rendered.lines());
        animating |= rendered.animating();
      }
      if (frozenThrough < messages.size() && trailingAssistantRunFreezable(state, messages)) {
        // The reveal can become settled while painting this frame. Request one final frame so
        // freezeLimit observes that transition and seals the whole assistant run exactly once.
        animating = true;
      }
      if (permission != null) {
        output.add(new StyledLine("", Style.NORMAL));
        output.add(new StyledLine("Allow tool: " + permission.name().value() + "?", Style.DANGER));
        output.add(new StyledLine("[y] allow  [a] always  [n/Esc] reject", Style.MUTED));
      }
      if (!state.status().isEmpty()) wrap(output, state.status(), width,
          state.status().startsWith("error:") ? Style.DANGER : Style.MUTED);
      if (!uiStatus.isEmpty()) wrap(output, uiStatus, width,
          uiStatus.startsWith("error:") ? Style.DANGER : Style.MUTED);
      output.add(new StyledLine("", Style.NORMAL));
      wrap(output, "> " + AttachmentText.display(composer, composerAttachments), width,
          Style.NORMAL);
      return new RenderedLines(List.copyOf(output), animating, trim.debt());
    }

    private void reconcileFrozenSurface(AgentState state, List<Message> messages, int width,
        int terminalRows, long nowNanos) {
      boolean uninitialized = frozenThread == null;
      boolean differentThread = frozenThread != null && !frozenThread.equals(state.thread().id());
      boolean invalidPrefix = frozenThrough > messages.size()
          || frozenThrough > 0 && (frozenIds.size() < frozenThrough
              || !frozenIds.get(frozenThrough - 1).equals(messages.get(frozenThrough - 1).id()));
      boolean resized = frozenWidth > 0 && frozenWidth != width;
      if (uninitialized || differentThread || invalidPrefix || resized) {
        frozen.clear();
        frozenIds.clear();
        frozenThrough = state.phase() instanceof SessionPhase.Idle
            ? rehydrateStart(state, messages, width, terminalRows, nowNanos) : 0;
        for (int index = 0; index < frozenThrough; index++) {
          frozenIds.add(messages.get(index).id());
        }
        if (!uninitialized) frame = new InlineFrameRenderer.HardReset();
      }
      frozenThread = state.thread().id();
      frozenWidth = width;
    }

    private int rehydrateStart(AgentState state, List<Message> messages, int width,
        int terminalRows, long nowNanos) {
      long rowLimit = Math.max(48L, terminalRows * 3L);
      int cursor = messages.size();
      int start = cursor;
      long keptRows = 0;
      int units = 0;
      while (cursor > 0) {
        int runStart = cursor - 1;
        if (messages.get(runStart).role() == Role.ASSISTANT) {
          while (runStart > 0 && messages.get(runStart - 1).role() == Role.ASSISTANT) {
            runStart--;
          }
        }
        long runRows = renderedRunRows(state, messages, runStart, cursor, width,
            terminalRows, nowNanos);
        if (units == 0 && runRows > rowLimit && cursor - runStart > 1) {
          long trailingRows = 1; // The retained continuation receives a fresh assistant header.
          int cut = cursor;
          for (int index = cursor - 1; index >= runStart; index--) {
            trailingRows += renderMessage(state, messages.get(index), index, messages.size(),
                width, terminalRows, nowNanos, false, false).lines().size();
            cut = index;
            if (trailingRows >= rowLimit) break;
          }
          return cut;
        }
        units++;
        start = runStart;
        keptRows += runRows;
        if (keptRows >= rowLimit) break;
        cursor = runStart;
      }
      return start;
    }

    private long renderedRunRows(AgentState state, List<Message> messages, int from, int to,
        int width, int terminalRows, long nowNanos) {
      long rows = 0;
      for (int index = from; index < to; index++) {
        rows += renderMessage(state, messages.get(index), index, messages.size(), width,
            terminalRows, nowNanos, false, index == from).lines().size();
      }
      return Math.max(1, rows);
    }

    private int freezeLimit(AgentState state, List<Message> messages) {
      if (messages.isEmpty()) return 0;
      Message last = messages.getLast();
      if (last.role() != Role.ASSISTANT) return messages.size();
      int runStart = messages.size() - 1;
      while (runStart > 0 && messages.get(runStart - 1).role() == Role.ASSISTANT) runStart--;
      boolean freezable = trailingAssistantRunFreezable(state, messages);
      return Math.max(frozenThrough, freezable ? messages.size() : runStart);
    }

    private boolean trailingAssistantRunFreezable(AgentState state, List<Message> messages) {
      if (messages.isEmpty() || !(state.phase() instanceof SessionPhase.Idle)) return false;
      Message last = messages.getLast();
      if (last.role() != Role.ASSISTANT || !last.id().equals(revealMessage)
          || reveal == null || !reveal.settled()) return false;
      int runStart = messages.size() - 1;
      while (runStart > 0 && messages.get(runStart - 1).role() == Role.ASSISTANT) runStart--;
      for (int index = runStart; index < messages.size(); index++) {
        if (messages.get(index).toolCalls().stream()
            .anyMatch(call -> !call.status().isTerminal())) return false;
      }
      return true;
    }

    private MessageRender renderMessage(AgentState state, Message message, int messageIndex,
        int messageCount, int width, int terminalRows, long nowNanos, boolean allowReveal,
        boolean showHeader) {
      var output = new ArrayList<StyledLine>();
      if (showHeader) {
        output.add(new StyledLine(message.role() == Role.USER ? "you" : "assistant", Style.ACCENT));
      }
      String text = AttachmentText.display(message.text(), message.attachments());
      List<MarkdownTerminalRenderer.Line> revealFrame = null;
      boolean animating = false;
      boolean revealable = allowReveal && message.role() == Role.ASSISTANT
          && messageIndex == messageCount - 1;
      if (revealable) {
        boolean streaming = !(state.phase() instanceof SessionPhase.Idle)
            && !message.textBlockClosed();
        if (!message.id().equals(revealMessage)) {
          revealMessage = message.id();
          reveal = new StreamingMarkdown();
        }
        reveal.setContent(text);
        if (streaming) reveal.setLive(true);
        else reveal.finish();
        revealFrame = reveal.render(width, nowNanos);
        animating = reveal.requiresAnimation();
      }
      if (revealFrame != null) appendMarkdown(output, revealFrame);
      else if (message.role() == Role.ASSISTANT) appendMarkdown(output, text, width);
      else wrap(output, text, width, Style.NORMAL);
      Map<String, java.util.Set<Integer>> grepHits =
          ToolBodyPreview.collectGrepHits(message.toolCalls());
      for (ToolUse call : message.toolCalls()) {
        output.add(new StyledLine("  " + call.name().value() + " \u00b7 "
            + call.status().getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT),
            call.status().isError() ? Style.DANGER : Style.MUTED));
        ToolBodyPreview.Preview preview = ToolBodyPreview.describe(call, terminalRows, grepHits);
        for (ToolBodyPreview.Row row : ToolBodyPreview.render(preview)) {
          output.add(new StyledLine(row.text(), switch (row.tone()) {
            case NORMAL -> Style.NORMAL;
            case MUTED -> Style.MUTED;
            case DANGER -> Style.DANGER;
            case SUCCESS -> Style.SUCCESS;
            case ACCENT -> Style.ACCENT;
          }));
        }
      }
      message.error().ifPresent(error -> wrap(output, "error: " + error, width, Style.DANGER));
      return new MessageRender(List.copyOf(output), animating);
    }

    private static void wrap(List<StyledLine> lines, String text, int width, Style style) {
      for (String line : ColumnTextWrapper.wrap(text, width)) {
        lines.add(new StyledLine(line, style));
      }
    }

    private static void appendMarkdown(List<StyledLine> lines, String source, int width) {
      appendMarkdown(lines, MarkdownTerminalRenderer.render(source, width));
    }

    private static void appendMarkdown(
        List<StyledLine> lines, List<MarkdownTerminalRenderer.Line> rendered) {
      for (MarkdownTerminalRenderer.Line line : rendered) {
        List<StyledSpan> spans = line.spans().stream()
            .map(span -> new StyledSpan(span.text(), span.style())).toList();
        lines.add(new StyledLine(line.text(), Style.NORMAL, spans));
      }
    }

    private enum Style { NORMAL, ACCENT, MUTED, DANGER, SUCCESS }
    private record StyledSpan(String text, TerminalStyle style) {}
    private record StyledLine(String text, Style style, List<StyledSpan> spans) {
      private StyledLine(String text, Style style) { this(text, style, List.of()); }
      private StyledLine { spans = List.copyOf(spans); }
    }
    private record MessageRender(List<StyledLine> lines, boolean animating) {}
    private record RenderedLines(List<StyledLine> lines, boolean animating,
                                 Optional<ScrollbackLedger.ScrollbackDebt> scrollbackDebt) {}

    private static String displayTool(String name) {
      if (name.isEmpty()) return name;
      return Character.toUpperCase(name.charAt(0)) + name.substring(1).replace('_', ' ');
    }

    private static String filenameOnly(String path) {
      int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
      return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String parentSegment(String path) {
      int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
      if (slash <= 0) return "";
      String parent = path.substring(0, slash);
      int previous = Math.max(parent.lastIndexOf('/'), parent.lastIndexOf('\\'));
      return previous < 0 ? parent : parent.substring(previous + 1);
    }

    private static int pickerStart(int index, int size) {
      return Math.max(0, Math.min(Math.max(0, size - 14), index - 7));
    }

    void updatePlan(List<PlanModal.Item> items) {
      synchronized (lock) { planItems = List.copyOf(items); }
      render();
    }
  }
}
