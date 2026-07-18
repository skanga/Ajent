package com.github.skanga.ajent.runtime;

import com.github.skanga.ajent.domain.CancellationSignal;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.MessageId;
import com.github.skanga.ajent.domain.Profile;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.provider.ErrorClass;
import com.github.skanga.ajent.provider.ProviderHttpTransport;
import com.github.skanga.ajent.provider.ToolSpecification;
import com.github.skanga.ajent.provider.openai.ProviderRegistry;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import com.github.skanga.ajent.tools.catalog.NativeToolWireCatalog;
import com.github.skanga.ajent.tools.host.HostServices;
import com.github.skanga.ajent.tools.policy.Effect;
import com.github.skanga.ajent.tools.catalog.ToolCatalog;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.Consumer;

/** Production implementation of AgenTTY's isolated provider-backed {@code task} tool. */
public final class ProviderBackedSubagentRunner implements HostServices.SubagentRunner {
  static final int MAX_DEPTH = 2;
  static final int MAX_TURNS = 24;
  static final int MAX_OUTPUT_TOKENS = 32_000;
  private static final Duration COMPLETION_BOUND = Duration.ofMinutes(30);
  private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

  @FunctionalInterface
  interface CompletionProviderFactory {
    ProviderPort create(LiveProviderFactory.Configuration configuration, String systemPrompt,
                        List<ToolSpecification> tools);
  }

  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  private record AgentType(String name, boolean readOnly, String role, Set<String> allow) {}

  private static final Map<String, AgentType> TYPES = Map.of(
      "explorer", new AgentType("explorer", true,
          "Your role: EXPLORER. Map and explain the codebase region the task names. Read widely, "
              + "trace call sites and definitions, and return a precise map: the key files, the "
              + "functions/types involved, how they connect, and any gotchas. Cite exact file "
              + "paths and line numbers. You are READ-ONLY — never modify anything.",
          Set.of("read", "grep", "glob", "list_dir", "find_definition", "repo_map",
              "web_search", "web_fetch")),
      "reviewer", new AgentType("reviewer", true,
          "Your role: REVIEWER. Critically review the code or change the task names. Look for "
              + "bugs, edge cases, race conditions, security issues, and deviations from the "
              + "surrounding conventions. Return findings as a prioritised list (blocker / major "
              + "/ minor / nit), each with the exact file:line and a concrete fix suggestion. "
              + "You are READ-ONLY.",
          Set.of("read", "grep", "glob", "list_dir", "find_definition", "repo_map",
              "git_diff", "git_log", "git_status")),
      "tester", new AgentType("tester", false,
          "Your role: TESTER. Reproduce, run, and diagnose. Build/run the relevant tests or "
              + "commands the task names, read the failures, and report the root cause with the "
              + "exact failing assertion and the file:line that produced it. Prefer running over "
              + "guessing. Do NOT rewrite production code — only run, read, and diagnose.",
          Set.of("read", "grep", "glob", "list_dir", "find_definition", "repo_map",
              "bash", "diagnostics", "git_diff", "git_status")),
      "coder", new AgentType("coder", false,
          "Your role: CODER. Implement the change the task names end-to-end: read the relevant "
              + "code first, make the edits, and verify they build/compile if a build command is "
              + "obvious. Follow the surrounding conventions exactly. Report what you changed "
              + "(files + a one-line summary each) and whether it built.", Set.of()),
      "general", new AgentType("general", false,
          "Your role: GENERAL. Complete the delegated task end-to-end using whatever tools fit, "
              + "then report the outcome.", Set.of()));

  private final CompletionProviderFactory providers;
  private final Sleeper sleeper;
  private final AtomicReference<Supplier<LiveProviderFactory.Configuration>> configuration =
      new AtomicReference<>();
  private final AtomicReference<ToolPort> tools = new AtomicReference<>();

  public ProviderBackedSubagentRunner(HttpClient client) {
    Objects.requireNonNull(client, "client");
    var transport = new ProviderHttpTransport(client);
    providers = (configuration, prompt, selectedTools) -> new HttpProviderPort(transport,
        messages -> LiveProviderFactory.request(configuration, messages, prompt, selectedTools,
            MAX_OUTPUT_TOKENS));
    sleeper = duration -> java.lang.Thread.sleep(duration);
  }

  ProviderBackedSubagentRunner(CompletionProviderFactory providers) {
    this(providers, ignored -> {});
  }

  ProviderBackedSubagentRunner(CompletionProviderFactory providers, Sleeper sleeper) {
    this.providers = Objects.requireNonNull(providers, "providers");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  public void bind(ToolPort toolPort) {
    tools.set(Objects.requireNonNull(toolPort, "toolPort"));
  }

  public void install(Supplier<LiveProviderFactory.Configuration> source) {
    configuration.set(Objects.requireNonNull(source, "source"));
  }

  @Override public boolean available() {
    return DEPTH.get() < MAX_DEPTH && tools.get() != null && currentConfiguration().isPresent();
  }

  @Override public HostServices.SubagentResponse run(HostServices.SubagentRequest request) {
    return run(request, new CancellationSignal());
  }

  @Override public HostServices.SubagentResponse run(HostServices.SubagentRequest request,
                                                      CancellationSignal cancellation) {
    return run(request, cancellation, ignored -> {});
  }

  @Override public HostServices.SubagentResponse run(HostServices.SubagentRequest request,
                                                      CancellationSignal cancellation,
                                                      Consumer<String> progress) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(cancellation, "cancellation");
    Objects.requireNonNull(progress, "progress");
    Optional<LiveProviderFactory.Configuration> selected = currentConfiguration();
    ToolPort boundTools = tools.get();
    if (selected.isEmpty() || boundTools == null) return error(
        "subagents are unavailable in this context (no model/provider wired)");
    if (DEPTH.get() >= MAX_DEPTH) return error(
        "subagent depth limit reached — a subagent cannot spawn further subagents at this "
            + "nesting level");

    AgentType type = TYPES.getOrDefault(request.agentType(), TYPES.get("general"));
    DEPTH.set(DEPTH.get() + 1);
    try {
      return execute(request.prompt(), type, selected.orElseThrow(), boundTools, cancellation,
          progress);
    } finally {
      int revised = DEPTH.get() - 1;
      if (revised == 0) DEPTH.remove(); else DEPTH.set(revised);
    }
  }

  private HostServices.SubagentResponse execute(String prompt, AgentType type,
                                                 LiveProviderFactory.Configuration selected,
                                                 ToolPort boundTools,
                                                 CancellationSignal parentCancellation,
                                                 Consumer<String> progress) {
    List<ToolSpecification> selectedTools = NativeToolWireCatalog.all().stream()
        .filter(tool -> allowed(type, tool.name())).toList();
    String systemPrompt = systemPrompt(selected, type);
    var turns = new AtomicInteger();
    var turnBudgetHit = new java.util.concurrent.atomic.AtomicBoolean();
    ProviderPort provider = (turnId, messages, cancellation, events) -> {
      int turn = turns.incrementAndGet();
      if (turn > MAX_TURNS) {
        turnBudgetHit.set(true);
        events.accept(new StreamEvent.Error("subagent hit its turn budget", Optional.empty(),
            ErrorClass.TERMINAL, false));
        return;
      }
      streamCompletion(selected, systemPrompt, selectedTools, turnId, messages, cancellation,
          events);
    };
    ToolPort filteredTools = call -> allowed(type, call.name().value())
        ? boundTools.execute(call)
        : new ToolCompletion.Failure("[PERMISSION] subagent tool is not allowed: "
            + call.name().value());
    var reducer = new AgentReducer(new AgentReducer.Context(System::nanoTime, Instant::now,
        MessageId::random, call -> allowed(type, call.name().value())
            ? PermissionVerdict.ALLOW : PermissionVerdict.DENY,
        () -> 1.0, () -> selected.contextWindow() > 0 ? selected.contextWindow() : 200_000));
    var thread = new com.github.skanga.ajent.domain.Thread(
        new ThreadId(MessageId.random().value()), "", List.of(), Instant.now(), Instant.now(),
        List.of());
    AgentState finalState;
    var lastProgress = new java.util.concurrent.atomic.AtomicLong();
    Consumer<String> emit = text -> {
      try {
        progress.accept(text.length() <= 40_000 ? text : text.substring(text.length() - 40_000));
      } catch (RuntimeException ignored) {
        // Progress is observational; a UI callback cannot fail the delegated task.
      }
    };
    emit.accept("◆ " + type.name() + " agent");
    try (var loop = new AgentLoop(AgentState.initial(thread), reducer, provider, filteredTools,
        ignored -> new PermissionPort.Decision(false, false), ignored -> {}, (message, state) -> {
          long now = System.nanoTime();
          boolean force = message instanceof RuntimeMessage.ToolCompleted
              || message instanceof RuntimeMessage.ToolProgress
              || message instanceof RuntimeMessage.ProviderEvent providerEvent
                  && (providerEvent.event() instanceof StreamEvent.ToolUseStart
                      || providerEvent.event() instanceof StreamEvent.ToolUseEnd
                      || providerEvent.event() instanceof StreamEvent.Finished);
          if (force || now - lastProgress.get() >= Duration.ofMillis(80).toNanos()) {
            lastProgress.set(now);
            emit.accept(activity(type, state));
          }
        })) {
      loop.dispatch(new RuntimeMessage.Submit(prompt, List.of()));
      long deadline = System.nanoTime() + COMPLETION_BOUND.toNanos();
      while (!loop.awaitIdle(Duration.ofMillis(100))) {
        if (parentCancellation.isCancelled()) {
          loop.dispatch(new RuntimeMessage.Cancel());
          return error("subagent cancelled");
        }
        if (System.nanoTime() - deadline >= 0) {
          loop.dispatch(new RuntimeMessage.Cancel());
          return error("subagent timed out before producing a final report");
        }
      }
      finalState = loop.state();
    } catch (InterruptedException exception) {
      java.lang.Thread.currentThread().interrupt();
      return error("subagent interrupted before producing a final report");
    } catch (RuntimeException exception) {
      return error("subagent failed: " + Objects.toString(exception.getMessage(),
          exception.getClass().getSimpleName()));
    }

    String report = finalState.thread().messages().stream()
        .filter(message -> message.role() == Role.ASSISTANT && !message.text().isEmpty())
        .reduce((first, second) -> second).map(Message::text).orElse("");
    boolean failed = report.isEmpty() && finalState.thread().messages().stream()
        .anyMatch(message -> message.error().isPresent());
    if (report.isEmpty()) {
      if (turnBudgetHit.get()) {
        report = "[subagent hit its turn budget without producing a final report]";
        failed = false;
      } else if (failed) report = "[subagent failed without producing a final report]";
      else if (finalState.status().contains("failed every time"))
        report = "[subagent stopped: the same tool call failed 3× in a row without converging]";
      else report = "[subagent finished without a text report]";
    }
    return new HostServices.SubagentResponse("Subagent report (" + type.name() + ", "
        + Math.min(turns.get(), MAX_TURNS) + " turn" + (turns.get() == 1 ? "" : "s")
        + "):\n\n" + report, failed);
  }

  private static String activity(AgentType type, AgentState state) {
    var output = new StringBuilder("◆ ").append(type.name()).append(" agent");
    for (Message message : state.thread().messages()) {
      if (message.role() != Role.ASSISTANT) continue;
      for (ToolUse call : message.toolCalls()) {
        output.append("\n  ");
        switch (call.status()) {
          case com.github.skanga.ajent.domain.ToolStatus.Done ignored -> output.append("✓ ");
          case com.github.skanga.ajent.domain.ToolStatus.Failed ignored -> output.append("✗ ");
          default -> output.append("⚙ ");
        }
        output.append(summarize(call));
        if (call.status() instanceof com.github.skanga.ajent.domain.ToolStatus.Failed failed)
          output.append("  — ").append(oneLine(failed.output(), 120));
      }
    }
    state.thread().messages().stream()
        .filter(message -> message.role() == Role.ASSISTANT && !message.text().isEmpty())
        .reduce((first, second) -> second).map(Message::text)
        .ifPresent(text -> output.append("\n  ▸ ").append(text));
    return output.toString();
  }

  private static String summarize(ToolUse call) {
    String result = call.name().value();
    for (String key : List.of("path", "file_path", "pattern", "command", "url", "query",
        "symbol", "prompt")) {
      if (call.arguments().get(key) instanceof String value) {
        return result + "  " + oneLine(value, 80);
      }
    }
    return result;
  }

  private static String oneLine(String value, int maximum) {
    String line = value.replace('\n', ' ').replace('\r', ' ');
    return line.length() <= maximum ? line : line.substring(0, maximum - 3) + "...";
  }

  private void streamCompletion(LiveProviderFactory.Configuration fallback, String systemPrompt,
                                List<ToolSpecification> selectedTools, long turnId,
                                List<Message> messages, CancellationSignal cancellation,
                                Consumer<StreamEvent> events) {
    for (int failures = 0; ; failures++) {
      var buffered = new java.util.ArrayList<StreamEvent>();
      try {
        providers.create(fallback, systemPrompt, selectedTools)
            .stream(turnId, messages, cancellation, buffered::add);
      } catch (RuntimeException exception) {
        buffered.add(new StreamEvent.Error("subagent provider: "
            + Objects.toString(exception.getMessage(), exception.getClass().getSimpleName())));
      }
      Optional<StreamEvent.Error> error = buffered.stream()
          .filter(StreamEvent.Error.class::isInstance).map(StreamEvent.Error.class::cast)
          .reduce((first, second) -> second);
      if (error.isEmpty()) {
        buffered.forEach(events);
        return;
      }
      if (failures >= 3 || cancellation.isCancelled()) {
        StreamEvent.Error failed = error.orElseThrow();
        events.accept(new StreamEvent.Error(failed.message(), failed.retryAfter(),
            ErrorClass.TERMINAL, failed.fromStall()));
        return;
      }
      try {
        sleeper.sleep(Duration.ofSeconds(1L << failures));
      } catch (InterruptedException exception) {
        java.lang.Thread.currentThread().interrupt();
        events.accept(new StreamEvent.Error("subagent interrupted while retrying",
            Optional.empty(), ErrorClass.TERMINAL, false));
        return;
      }
    }
  }

  private Optional<LiveProviderFactory.Configuration> currentConfiguration() {
    Supplier<LiveProviderFactory.Configuration> source = configuration.get();
    if (source == null) return Optional.empty();
    LiveProviderFactory.Configuration value;
    try {
      value = source.get();
    } catch (RuntimeException exception) {
      return Optional.empty();
    }
    if (value == null || value.model().isBlank()) return Optional.empty();
    boolean local = ProviderRegistry.presetFor(value.provider())
        .map(preset -> preset.authStyle() == ProviderRegistry.AuthStyle.NONE).orElse(false);
    return value.auth().isEmpty() && !local ? Optional.empty() : Optional.of(value);
  }

  private static boolean allowed(AgentType type, String name) {
    if (name.equals("task")) return false;
    if (!type.allow().isEmpty() && !type.allow().contains(name)) return false;
    if (!type.readOnly()) return ToolCatalog.byName(name).isPresent();
    return ToolCatalog.byName(name).map(spec -> !spec.effects().has(Effect.WRITE_FS)
        && !spec.effects().has(Effect.EXEC) && !spec.effects().has(Effect.NET)).orElse(false);
  }

  private static String systemPrompt(LiveProviderFactory.Configuration configuration,
                                     AgentType type) {
    String base = configuration.systemPrompt().anthropic();
    String prompt = base + "\n\n<subagent>\n" + type.role()
        + "\n\nYou are a SUBAGENT spawned to complete ONE delegated task in isolation. You do "
        + "NOT see the parent conversation and cannot ask it questions — work fully autonomously "
        + "from the task prompt alone. Use your tools to investigate and act, then STOP calling "
        + "tools and write your final report as plain text.\n\nYour final message is the ONLY "
        + "thing the parent receives — not your transcript, not your tool output. So the report "
        + "must stand alone. Structure it as:\n  • A one-line OUTCOME (what you found / did)."
        + "\n  • The key details the parent needs to act, with exact file:line references where "
        + "relevant.\n  • Anything you could NOT determine, stated plainly.\nBe concrete and "
        + "cite evidence (paths, line numbers, command output). Do not pad. If the task is "
        + "impossible or underspecified, say so and explain what's missing rather than guessing.";
    if (type.readOnly()) prompt += "\n\nYou are READ-ONLY: you have no tools that modify "
        + "files, run commands, or reach the network. Investigate and report only.";
    return prompt + "\n</subagent>";
  }

  private static HostServices.SubagentResponse error(String message) {
    return new HostServices.SubagentResponse(message, true);
  }
}
