package com.github.skanga.ajent.cli;

import com.github.skanga.ajent.core.persistence.Settings;
import com.github.skanga.ajent.core.persistence.SettingsStore;
import com.github.skanga.ajent.core.persistence.ThreadStore;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Profile;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.SessionPhase;
import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.provider.auth.Credential;
import com.github.skanga.ajent.provider.auth.CredentialResolver;
import com.github.skanga.ajent.provider.auth.CredentialStore;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import com.github.skanga.ajent.provider.openai.ProviderAuthResolver;
import com.github.skanga.ajent.runtime.AgentLoop;
import com.github.skanga.ajent.runtime.AgentSessionFactory;
import com.github.skanga.ajent.runtime.AgentState;
import com.github.skanga.ajent.runtime.LiveProviderFactory;
import com.github.skanga.ajent.runtime.PermissionPort;
import com.github.skanga.ajent.runtime.RuntimeMessage;
import com.github.skanga.ajent.terminal.JLineTerminalSession;
import com.github.skanga.ajent.terminal.input.TerminalEvent;
import com.github.skanga.ajent.terminal.input.TerminalKey;
import com.github.skanga.ajent.terminal.render.CanvasSerializer;
import com.github.skanga.ajent.terminal.render.ColumnTextWrapper;
import com.github.skanga.ajent.terminal.render.InlineFrameRenderer;
import com.github.skanga.ajent.terminal.render.TerminalCanvas;
import com.github.skanga.ajent.terminal.render.TerminalColor;
import com.github.skanga.ajent.terminal.render.TerminalStyle;
import com.github.skanga.ajent.terminal.render.TerminalStylePool;
import com.github.skanga.ajent.terminal.render.TextReveal;
import com.github.skanga.ajent.terminal.ui.CommandPalette;
import com.github.skanga.ajent.terminal.ui.ToolOutputViewer;
import com.github.skanga.ajent.tools.process.ProcessSandbox;
import com.github.skanga.ajent.tools.runtime.ToolRuntimeFactory;
import com.github.skanga.ajent.tools.web.JdkWebTransport;
import java.io.IOException;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Base64;

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
    var permission = new PermissionGate();
    try (var animations = new FrameScheduler()) {
      var ui = new Ui(new TerminalPort() {
        @Override public JLineTerminalSession.Size size() { return terminal.size(); }
        @Override public void write(String value) { terminal.write(value); }
      }, state, permission, animations);
      permission.onChange(ui::render);
      var threadStore = new ThreadStore(configured.dataDirectory());
      var conversation = new com.github.skanga.ajent.domain.Thread(
          threadStore.newId(), "", List.of(), Instant.now(), Instant.now(), List.of());
      AgentLoop loop = configured.sessions().create(conversation, configured.profile(),
          configured.model(), permission, (message, next) -> {
            state.set(next);
            ui.render();
          });
      state.set(loop.state());
      try (loop) {
        terminal.onResize(ignored -> ui.render());
        ui.render();
        boolean running = true;
        while (running) {
          List<TerminalEvent> events = terminal.read();
          if (events.isEmpty()) events = terminal.flushEscape();
          for (TerminalEvent event : events) {
            if (event instanceof TerminalEvent.Key key) {
              running = ui.key(key.value(), new AgentControl() {
                @Override public AgentState state() { return loop.state(); }
                @Override public void dispatch(RuntimeMessage message) { loop.dispatch(message); }
              });
            } else if (event instanceof TerminalEvent.Paste paste) {
              ui.insert(paste.text());
            }
            if (!running) break;
          }
        }
        return 0;
      } finally {
        permission.cancel();
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
    Settings settings = new SettingsStore(dataDirectory).load();
    String provider = arguments.provider().isBlank() ? settings.provider() : arguments.provider();
    if (provider.isBlank()) provider = "anthropic";
    String model = arguments.model().isBlank() ? settings.modelId().value() : arguments.model();
    if (model.isBlank()) model = DEFAULT_MODEL;
    CredentialResolver.Resolution anthropic = CredentialResolver.resolve(
        arguments.key(), environment, credentials.load(), System.currentTimeMillis());
    ProviderAuth auth = ProviderAuthResolver.resolve(provider, providerAuth(anthropic.credential()),
        arguments.key(), settings.providerKeys().getOrDefault(provider, ""), environment);
    Path docs = resolveDocs(workspace);
    var tools = ToolRuntimeFactory.compose(new ToolRuntimeFactory.Configuration(
        workspace, workspace, home, docs, new JdkWebTransport(), null, null, sandbox.runner()));
    var providers = new LiveProviderFactory.Configuration(provider, model, auth, settings.effort(),
        tools.systemPrompt(), 0, environment);
    return new Configuration(new AgentSessionFactory(tools, providers, client, dataDirectory),
        dataDirectory, profile, model);
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

  record Configuration(
      AgentSessionFactory sessions, Path dataDirectory, Profile profile, String model) {}

  interface TerminalPort {
    JLineTerminalSession.Size size();
    void write(String value);
  }

  interface AgentControl {
    AgentState state();
    void dispatch(RuntimeMessage message);
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
    private final TerminalStylePool styles = new TerminalStylePool();
    private InlineFrameRenderer.Frame frame = new InlineFrameRenderer.Empty();
    private CommandPalette.State palette = new CommandPalette.Closed();
    private ToolOutputViewer.State toolViewer = new ToolOutputViewer.Closed();
    private String uiStatus = "";
    private String composer = "";
    private int cursor;
    private com.github.skanga.ajent.domain.MessageId revealMessage;
    private TextReveal reveal;

    Ui(TerminalPort terminal, AtomicReference<AgentState> agent, PermissionGate permission) {
      this(terminal, agent, permission, new AnimationPort() {
        @Override public long nowNanos() { return System.nanoTime(); }
        @Override public void request(Runnable frame) { }
      });
    }

    Ui(TerminalPort terminal, AtomicReference<AgentState> agent, PermissionGate permission,
        AnimationPort animations) {
      this.terminal = terminal;
      this.agent = agent;
      this.permission = permission;
      this.animations = animations;
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
      if (palette instanceof CommandPalette.Open) return paletteKey(key, loop);
      if (toolViewer instanceof ToolOutputViewer.Open) return toolViewerKey(key);
      if (key.key() instanceof TerminalKey.CharacterKey character && key.modifiers().ctrl()) {
        int codePoint = Character.toLowerCase(character.codePoint());
        if (codePoint == 'c') return false;
        if (codePoint == 'k') { palette = CommandPalette.open(); render(); return true; }
        if (codePoint == 'o') { openToolViewer(loop.state()); render(); return true; }
        if (codePoint == 'd' && composer.isEmpty()) return false;
        if (codePoint == 'u') { composer = composer.substring(cursor); cursor = 0; render(); return true; }
      }
      if (key.key() instanceof TerminalKey.SpecialKey special) {
        switch (special) {
          case ENTER -> {
            if (key.modifiers().shift() || key.modifiers().alt()) insert("\n");
            else if (!composer.isEmpty()) {
              String submitted = composer;
              clearComposer();
              loop.dispatch(new RuntimeMessage.Submit(submitted, List.of()));
            }
          }
          case BACKSPACE -> {
            if (cursor > 0) {
              int previous = composer.offsetByCodePoints(cursor, -1);
              composer = composer.substring(0, previous) + composer.substring(cursor);
              cursor = previous;
              render();
            }
          }
          case LEFT -> { if (cursor > 0) cursor = composer.offsetByCodePoints(cursor, -1); render(); }
          case RIGHT -> {
            if (cursor < composer.length()) cursor = composer.offsetByCodePoints(cursor, 1);
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
        insert(new String(Character.toChars(character.codePoint())));
      }
      return true;
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

    void insert(String value) {
      synchronized (lock) {
        composer = composer.substring(0, cursor) + value + composer.substring(cursor);
        cursor += value.length();
      }
      render();
    }

    private void clearComposer() {
      synchronized (lock) { composer = ""; cursor = 0; }
      render();
    }

    void render() {
      synchronized (lock) {
        AgentState state = agent.get();
        if (state == null) return;
        JLineTerminalSession.Size size = terminal.size();
        int width = Math.max(1, size.columns());
        RenderedLines rendered = lines(state, permission.current(), width, composer,
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
        var canvas = new TerminalCanvas(width, Math.max(1, lines.size()));
        int normal = 0;
        int accent = styles.intern(TerminalStyle.EMPTY.withForeground(TerminalColor.cyan()).withBold());
        int muted = styles.intern(TerminalStyle.EMPTY.withForeground(TerminalColor.brightBlack()));
        int danger = styles.intern(TerminalStyle.EMPTY.withForeground(TerminalColor.red()).withBold());
        for (int row = 0; row < lines.size(); row++) {
          StyledLine line = lines.get(row);
          int style = switch (line.style()) {
            case NORMAL -> normal; case ACCENT -> accent; case MUTED -> muted; case DANGER -> danger;
          };
          canvas.writeText(0, row, line.text(), style);
        }
        var rows = CanvasSerializer.contentRows(canvas);
        frame = render(frame, canvas, rows, Math.max(1, size.rows()), styles,
            value -> terminal.write(value));
        if (rendered.animating()) animations.request(this::render);
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
          yield witness.isPresent() && proof.isPresent()
              ? synced.render(canvas, rows, terminalRows, styles, writer,
                  witness.orElseThrow(), proof.orElseThrow(), false)
              : synced.demoteToStale().render(canvas, rows, terminalRows, styles, writer, false);
        }
        case InlineFrameRenderer.Stale stale -> stale.render(
            canvas, rows, terminalRows, styles, writer, false);
        case InlineFrameRenderer.HardReset reset -> reset.render(
            canvas, rows, terminalRows, styles, writer, false);
        case InlineFrameRenderer.Sealed ignored -> throw new IllegalStateException("renderer sealed");
      };
    }

    private RenderedLines lines(
        AgentState state, ToolUse permission, int width, String composer, long nowNanos) {
      var output = new ArrayList<StyledLine>();
      boolean animating = false;
      if (state.thread().messages().isEmpty()) {
        output.add(new StyledLine("Ajent", Style.ACCENT));
        output.add(new StyledLine("AI coding agent · Ctrl-D to quit", Style.MUTED));
      }
      List<Message> messages = state.thread().messages();
      for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
        Message message = messages.get(messageIndex);
        if (!output.isEmpty()) output.add(new StyledLine("", Style.NORMAL));
        output.add(new StyledLine(message.role() == Role.USER ? "you" : "assistant", Style.ACCENT));
        String text = message.text();
        boolean revealable = message.role() == Role.ASSISTANT
            && messageIndex == messages.size() - 1;
        if (revealable) {
          boolean streaming = !(state.phase() instanceof SessionPhase.Idle)
              && !message.textBlockClosed();
          TextReveal.Frame revealFrame;
          if (!message.id().equals(revealMessage)) {
            revealMessage = message.id();
            reveal = new TextReveal();
            revealFrame = reveal.begin(text, streaming, nowNanos);
          } else {
            revealFrame = reveal.update(text, streaming, nowNanos);
          }
          text = revealFrame.text();
          animating |= revealFrame.animating();
        }
        wrap(output, text, width, Style.NORMAL);
        for (ToolUse call : message.toolCalls()) {
          output.add(new StyledLine("  " + call.name().value() + " · "
              + call.status().getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT),
              call.status().isError() ? Style.DANGER : Style.MUTED));
        }
        message.error().ifPresent(error -> wrap(output, "error: " + error, width, Style.DANGER));
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
      wrap(output, "> " + composer, width, Style.NORMAL);
      return new RenderedLines(List.copyOf(output), animating);
    }

    private static void wrap(List<StyledLine> lines, String text, int width, Style style) {
      for (String line : ColumnTextWrapper.wrap(text, width)) {
        lines.add(new StyledLine(line, style));
      }
    }

    private enum Style { NORMAL, ACCENT, MUTED, DANGER }
    private record StyledLine(String text, Style style) {}
    private record RenderedLines(List<StyledLine> lines, boolean animating) {}

    private static String displayTool(String name) {
      if (name.isEmpty()) return name;
      return Character.toUpperCase(name.charAt(0)) + name.substring(1).replace('_', ' ');
    }
  }
}
