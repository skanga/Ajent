package com.github.skanga.ajent.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.domain.ActiveTurn;
import com.github.skanga.ajent.domain.CancellationSignal;
import com.github.skanga.ajent.domain.ImageContent;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.MessageId;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.SessionPhase;
import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.domain.RetryState;
import com.github.skanga.ajent.core.scheduling.ToolScheduler;
import com.github.skanga.ajent.provider.ErrorClass;
import com.github.skanga.ajent.provider.ProviderErrorPolicy;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.concurrent.ThreadLocalRandom;

/** Pure headless agent reducer: {@code (state, message) -> (state, effects)}. */
public final class AgentReducer {
  private static final int MAX_STREAMING_BYTES = 8 * 1024 * 1024;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() {};

  public record Context(LongSupplier nanoClock, Supplier<Instant> wallClock,
                        Supplier<MessageId> messageIds,
                        Function<ToolUse, PermissionVerdict> permissions,
                        DoubleSupplier retryJitter) {
    public Context(LongSupplier nanoClock, Supplier<Instant> wallClock,
                   Supplier<MessageId> messageIds,
                   Function<ToolUse, PermissionVerdict> permissions) {
      this(nanoClock, wallClock, messageIds, permissions,
          () -> ThreadLocalRandom.current().nextDouble(0.80, 1.20));
    }

    public Context {
      Objects.requireNonNull(nanoClock, "nanoClock");
      Objects.requireNonNull(wallClock, "wallClock");
      Objects.requireNonNull(messageIds, "messageIds");
      Objects.requireNonNull(permissions, "permissions");
      Objects.requireNonNull(retryJitter, "retryJitter");
    }
  }

  public record Step(AgentState state, List<RuntimeEffect> effects) {
    public Step { effects = List.copyOf(effects); }
  }

  private final Context context;

  public AgentReducer(Context context) {
    this.context = context;
  }

  public Step update(AgentState state, RuntimeMessage message) {
    return switch (message) {
      case RuntimeMessage.Submit submit -> submit(state, submit);
      case RuntimeMessage.ProviderEvent event -> providerEvent(state, event);
      case RuntimeMessage.ToolCompleted completed -> toolCompleted(state, completed);
      case RuntimeMessage.PermissionResolved resolved -> permissionResolved(state, resolved);
      case RuntimeMessage.RetryStream retry -> retryStream(state, retry);
      case RuntimeMessage.Cancel ignored -> cancel(state);
    };
  }

  private Step submit(AgentState state, RuntimeMessage.Submit submit) {
    if (!(state.phase() instanceof SessionPhase.Idle)) {
      var queued = new ArrayList<>(state.queued());
      queued.add(submit);
      return done(copy(state, state.thread(), state.phase(), state.activeTurnId(),
          state.turnCounter(), state.tokensIn(), state.tokensOut(), state.status(),
          state.toolDraft(), queued, state.sessionGrants()));
    }
    if (submit.text().isEmpty() && submit.images().isEmpty()) return done(state);
    long turnId = state.turnCounter() + 1;
    Instant now = context.wallClock().get();
    var messages = new ArrayList<>(state.thread().messages());
    messages.add(message(Role.USER, submit.text(), submit.images(), List.of(), now));
    messages.add(message(Role.ASSISTANT, "", List.of(), List.of(), now));
    var thread = withMessages(state.thread(), messages, now);
    var cancellation = new CancellationSignal();
    SessionPhase phase = SessionPhase.start(new SessionPhase.Idle(),
        ActiveTurn.start(cancellation, context.nanoClock().getAsLong()));
    AgentState revised = copy(state, thread, phase, turnId, turnId, state.tokensIn(),
        state.tokensOut(), "", Optional.empty(), state.queued(), state.sessionGrants());
    return new Step(revised, List.of(new RuntimeEffect.Persist(thread),
        new RuntimeEffect.StartStream(turnId, thread.messages(), cancellation)));
  }

  private Step providerEvent(AgentState state, RuntimeMessage.ProviderEvent envelope) {
    if (state.activeTurnId() != envelope.turnId() || state.phase() instanceof SessionPhase.Idle)
      return done(state);
    AgentState live = withActive(state, state.phase().active().orElseThrow()
        .withLastEventNanos(context.nanoClock().getAsLong()));
    return switch (envelope.event()) {
      case StreamEvent.Started ignored -> done(streamStarted(live));
      case StreamEvent.TextDelta delta -> done(appendText(recordDelta(live, delta.text()),
          delta.text()));
      case StreamEvent.ToolUseStart start -> done(startTool(live, start));
      case StreamEvent.ToolUseDelta delta -> done(appendToolArguments(
          recordDelta(live, delta.partialJson()), delta.partialJson()));
      case StreamEvent.ToolUseEnd ignored -> done(endTool(live));
      case StreamEvent.Heartbeat ignored -> done(heartbeat(live));
      case StreamEvent.Usage usage -> done(copy(live, live.thread(), live.phase(),
          live.activeTurnId(), live.turnCounter(), usage.inputTokens(), usage.outputTokens(),
          live.status(), live.toolDraft(), live.queued(), live.sessionGrants()));
      case StreamEvent.Finished ignored -> finalizeStream(live);
      case StreamEvent.Error error -> streamError(live, error);
    };
  }

  private Step finalizeStream(AgentState state) {
    AgentState revised = state.toolDraft().isPresent() ? endTool(state) : state;
    List<ToolUse> calls = lastAssistant(revised).map(Message::toolCalls).orElse(List.of());
    if (!calls.isEmpty()) return kickTools(revised);
    return finishTurn(revised, "");
  }

  private Step toolCompleted(AgentState state, RuntimeMessage.ToolCompleted completed) {
    if (completed.turnId() != state.activeTurnId() || state.phase() instanceof SessionPhase.Idle)
      return done(state);
    AgentState revised = updateTool(state, completed.callId(), call -> {
      if (call.status().isTerminal()) return call;
      ToolStatus status = switch (completed.result()) {
        case ToolCompletion.Success success -> new ToolStatus.Done(success.output());
        case ToolCompletion.Failure failure -> new ToolStatus.Failed(failure.error());
      };
      return new ToolUse(call.id(), call.name(), call.arguments(), status);
    });
    return kickTools(revised);
  }

  private Step permissionResolved(AgentState state, RuntimeMessage.PermissionResolved resolved) {
    if (!(state.phase() instanceof SessionPhase.AwaitingPermission)) return done(state);
    Optional<ToolUse> selected = findTool(state, resolved.callId());
    if (selected.isEmpty() || !(selected.get().status() instanceof ToolStatus.Pending))
      return done(state);
    Set<String> grants = state.sessionGrants();
    if (resolved.always() && resolved.approved()) {
      var mutable = new java.util.HashSet<>(grants);
      mutable.add(selected.get().name().value());
      grants = Set.copyOf(mutable);
    }
    AgentState withGrants = copy(state, state.thread(), state.phase(), state.activeTurnId(),
        state.turnCounter(), state.tokensIn(), state.tokensOut(), state.status(), state.toolDraft(),
        state.queued(), grants);
    if (resolved.approved()) {
      AgentState approved = updateTool(withGrants, resolved.callId(), call -> new ToolUse(call.id(),
          call.name(), call.arguments(), new ToolStatus.Approved()));
      return kickTools(approved);
    }
    AgentState rejected = updateTool(withGrants, resolved.callId(), call -> new ToolUse(call.id(),
        call.name(), call.arguments(), new ToolStatus.Rejected()));
    return kickTools(rejected);
  }

  private Step cancel(AgentState state) {
    if (state.phase() instanceof SessionPhase.Idle) return done(state);
    state.phase().active().orElseThrow().cancellation().cancel();
    AgentState settled = updateEveryTool(state, call -> call.status().isTerminal() ? call
        : new ToolUse(call.id(), call.name(), call.arguments(), new ToolStatus.Rejected()));
    SessionPhase.Idle idle = SessionPhase.abort(settled.phase());
    AgentState revised = copy(settled, settled.thread(), idle, 0, settled.turnCounter(),
        settled.tokensIn(), settled.tokensOut(), "cancelled", Optional.empty(), settled.queued(),
        settled.sessionGrants());
    return new Step(revised, List.of(new RuntimeEffect.Persist(revised.thread())));
  }

  private Step kickTools(AgentState state) {
    List<ToolUse> calls = lastAssistant(state).map(Message::toolCalls).orElse(List.of());
    List<Integer> promote = ToolScheduler.scheduleParallelBatch(calls).promote();
    if (promote.isEmpty()) {
      boolean running = calls.stream().anyMatch(call -> call.status() instanceof ToolStatus.Running);
      boolean waiting = calls.stream().anyMatch(call -> call.status() instanceof ToolStatus.Pending
          || call.status() instanceof ToolStatus.Approved);
      return running || waiting ? done(state) : continueStream(state);
    }

    AgentState revised = state;
    var effects = new ArrayList<RuntimeEffect>();
    boolean prompt = false;
    boolean denied = false;
    for (int index : promote) {
      ToolUse original = calls.get(index);
      ToolUse current = findTool(revised, original.id().value()).orElseThrow();
      PermissionVerdict verdict = current.status() instanceof ToolStatus.Approved
          || revised.sessionGrants().contains(current.name().value())
          ? PermissionVerdict.ALLOW : context.permissions().apply(current);
      if (verdict == PermissionVerdict.DENY) {
        revised = updateTool(revised, current.id().value(), value -> new ToolUse(value.id(),
            value.name(), value.arguments(), new ToolStatus.Failed("Tool call denied by policy.")));
        denied = true;
        continue;
      }
      if (verdict == PermissionVerdict.PROMPT) {
        effects.add(new RuntimeEffect.RequestPermission(revised.activeTurnId(), current));
        prompt = true;
        break; // AgenTTY presents one permission card at a time.
      }
      revised = updateTool(revised, current.id().value(), value -> new ToolUse(value.id(),
          value.name(), value.arguments(), new ToolStatus.Running("")));
      effects.add(new RuntimeEffect.ExecuteTool(revised.activeTurnId(),
          findTool(revised, current.id().value()).orElseThrow()));
    }
    if (effects.isEmpty() && denied) return kickTools(revised);
    SessionPhase phase = prompt ? toAwaitingPermission(revised.phase())
        : toExecutingTool(revised.phase());
    revised = copy(revised, revised.thread(), phase, revised.activeTurnId(),
        revised.turnCounter(), revised.tokensIn(), revised.tokensOut(), revised.status(),
        revised.toolDraft(), revised.queued(), revised.sessionGrants());
    return new Step(revised, effects);
  }

  private Step continueStream(AgentState state) {
    ActiveTurn active = state.phase().active().orElseThrow();
    long turnId = state.turnCounter() + 1;
    Instant now = context.wallClock().get();
    var messages = new ArrayList<>(state.thread().messages());
    messages.add(message(Role.ASSISTANT, "", List.of(), List.of(), now));
    var thread = withMessages(state.thread(), messages, now);
    SessionPhase phase = new SessionPhase.Streaming(active);
    AgentState revised = copy(state, thread, phase, turnId, turnId, state.tokensIn(),
        state.tokensOut(), "", Optional.empty(), state.queued(), state.sessionGrants());
    return new Step(revised, List.of(new RuntimeEffect.Persist(thread),
        new RuntimeEffect.StartStream(turnId, thread.messages(), active.cancellation())));
  }

  private Step finishTurn(AgentState state, String status) {
    SessionPhase.Idle idle = state.phase() instanceof SessionPhase.Streaming streaming
        ? SessionPhase.finish(streaming) : SessionPhase.abort(state.phase());
    AgentState revised = copy(state, state.thread(), idle, 0, state.turnCounter(),
        state.tokensIn(), state.tokensOut(), status, Optional.empty(), state.queued(),
        state.sessionGrants());
    var effects = new ArrayList<RuntimeEffect>();
    effects.add(new RuntimeEffect.Persist(revised.thread()));
    if (revised.queued().isEmpty()) return new Step(revised, effects);
    RuntimeMessage.Submit head = revised.queued().getFirst();
    AgentState ready = copy(revised, revised.thread(), revised.phase(), revised.activeTurnId(),
        revised.turnCounter(), revised.tokensIn(), revised.tokensOut(), revised.status(),
        revised.toolDraft(), revised.queued().subList(1, revised.queued().size()),
        revised.sessionGrants());
    Step submitted = submit(ready, head);
    effects.addAll(submitted.effects());
    return new Step(submitted.state(), effects);
  }

  private Step streamError(AgentState state, StreamEvent.Error error) {
    ActiveTurn active = state.phase().active().orElseThrow();
    if (active.retryState() instanceof RetryState.Scheduled) return done(state);
    ErrorClass errorClass = error.errorClass();
    if (errorClass == ErrorClass.CANCELLED
        && (error.fromStall() || active.retryState() instanceof RetryState.StallFired))
      errorClass = ErrorClass.TRANSIENT;

    Optional<Message> assistant = lastAssistant(state);
    boolean committed = assistant.map(message -> !message.text().isEmpty()
        || message.toolCalls().stream().anyMatch(call ->
            call.status() instanceof ToolStatus.Done
                || call.status() instanceof ToolStatus.Running)).orElse(false);
    long now = context.nanoClock().getAsLong();
    int priorTransient = active.transientRetries();
    if (active.lastFailureNanos() != 0
        && now - active.lastFailureNanos() >= ProviderErrorPolicy.RETRY_DECAY.toNanos())
      priorTransient = 0;
    boolean midStream = active.retryState() instanceof RetryState.StallFired
        || active.firstDeltaNanos() != 0;
    int retryCap = ProviderErrorPolicy.maxRetries(errorClass, midStream);
    int priorBudget = midStream ? active.midStreamFailures() : priorTransient;
    boolean canRetry = (errorClass == ErrorClass.TRANSIENT
        || errorClass == ErrorClass.RATE_LIMIT) && priorBudget < retryCap && !committed;
    if (canRetry) {
      ErrorClass retryClass = errorClass;
      int retryAttempt = priorTransient;
      Duration delay = error.retryAfter().map(AgentReducer::clampRetryAfter).orElseGet(() ->
          ProviderErrorPolicy.backoffWithJitter(retryClass, retryAttempt,
              context.retryJitter().getAsDouble()));
      ActiveTurn scheduled = active.withTransientRetries(priorTransient + 1)
          .withMidStreamFailures(midStream ? active.midStreamFailures() + 1
              : active.midStreamFailures())
          .withLastFailureNanos(now).withRetryState(new RetryState.Scheduled());
      AgentState revised = withActive(state, scheduled);
      revised = replaceUncommittedAssistant(revised);
      long seconds = Math.max(1, (delay.toMillis() + 999) / 1_000);
      String status = errorClass.label() + " — retrying in " + seconds + "s (attempt "
          + (priorBudget + 1) + "/" + retryCap + ")…";
      revised = copy(revised, revised.thread(), revised.phase(), revised.activeTurnId(),
          revised.turnCounter(), revised.tokensIn(), revised.tokensOut(), status,
          Optional.empty(), revised.queued(), revised.sessionGrants());
      return new Step(revised, List.of(new RuntimeEffect.Schedule(delay,
          new RuntimeMessage.RetryStream(state.activeTurnId()))));
    }

    AgentState terminal = errorClass == ErrorClass.CANCELLED ? state
        : updateLastAssistant(state, message -> withError(message, error.message()));
    return finishTurn(terminal, errorClass == ErrorClass.CANCELLED
        ? "cancelled" : "error: " + error.message());
  }

  private Step retryStream(AgentState state, RuntimeMessage.RetryStream retry) {
    if (state.activeTurnId() != retry.turnId() || state.phase() instanceof SessionPhase.Idle)
      return done(state);
    ActiveTurn active = state.phase().active().orElseThrow();
    if (!(active.retryState() instanceof RetryState.Scheduled)) return done(state);
    AgentState revised = withActive(state, active.withRetryState(new RetryState.Fresh()));
    return new Step(revised, List.of(new RuntimeEffect.StartStream(retry.turnId(),
        revised.thread().messages(), active.cancellation())));
  }

  private AgentState streamStarted(AgentState state) {
    long now = context.nanoClock().getAsLong();
    AgentState revised = withActive(state,
        state.phase().active().orElseThrow().restartStream(now));
    return copy(revised, revised.thread(), revised.phase(), revised.activeTurnId(),
        revised.turnCounter(), revised.tokensIn(), revised.tokensOut(), "",
        revised.toolDraft(), revised.queued(), revised.sessionGrants());
  }

  private AgentState heartbeat(AgentState state) {
    ActiveTurn active = state.phase().active().orElseThrow()
        .withLastEventNanos(context.nanoClock().getAsLong()).withTransientRetries(0);
    return withActive(state, active);
  }

  private AgentState recordDelta(AgentState state, String delta) {
    if (delta.isEmpty()) return state;
    ActiveTurn active = state.phase().active().orElseThrow();
    long now = context.nanoClock().getAsLong();
    long first = active.firstDeltaNanos() == 0 ? now : active.firstDeltaNanos();
    if (active.firstDeltaNanos() == 0) active = active.withTransientRetries(0);
    active = active.withLiveDelta(active.liveDeltaBytes()
        + delta.getBytes(StandardCharsets.UTF_8).length, first,
        active.rateLastSampleNanos(), active.rateLastSampleBytes());
    return withActive(state, active);
  }

  private AgentState replaceUncommittedAssistant(AgentState state) {
    var messages = new ArrayList<>(state.thread().messages());
    if (!messages.isEmpty() && messages.getLast().role() == Role.ASSISTANT)
      messages.removeLast();
    messages.add(message(Role.ASSISTANT, "", List.of(), List.of(), context.wallClock().get()));
    var thread = withMessages(state.thread(), messages, context.wallClock().get());
    return copy(state, thread, state.phase(), state.activeTurnId(), state.turnCounter(),
        state.tokensIn(), state.tokensOut(), state.status(), Optional.empty(), state.queued(),
        state.sessionGrants());
  }

  private static Duration clampRetryAfter(Duration value) {
    long seconds = Math.clamp(value.getSeconds(), 1, 600);
    return Duration.ofSeconds(seconds);
  }

  private AgentState startTool(AgentState state, StreamEvent.ToolUseStart start) {
    ToolUse call = new ToolUse(new ToolCallId(start.id()), new ToolName(start.name()), Map.of(),
        new ToolStatus.Pending());
    AgentState revised = updateLastAssistant(state, message -> {
      var calls = new ArrayList<>(message.toolCalls());
      calls.add(call);
      return message.withToolCalls(calls);
    });
    return copy(revised, revised.thread(), revised.phase(), revised.activeTurnId(),
        revised.turnCounter(), revised.tokensIn(), revised.tokensOut(), revised.status(),
        Optional.of(new AgentState.ToolDraft(start.id(), "")), revised.queued(),
        revised.sessionGrants());
  }

  private AgentState appendToolArguments(AgentState state, String fragment) {
    if (state.toolDraft().isEmpty()) return state;
    AgentState.ToolDraft draft = state.toolDraft().orElseThrow();
    String partial = appendUtf8Capped(draft.partialJson(), fragment, MAX_STREAMING_BYTES);
    return copy(state, state.thread(), state.phase(), state.activeTurnId(), state.turnCounter(),
        state.tokensIn(), state.tokensOut(), state.status(),
        Optional.of(new AgentState.ToolDraft(draft.callId(), partial)), state.queued(),
        state.sessionGrants());
  }

  private AgentState endTool(AgentState state) {
    if (state.toolDraft().isEmpty()) return state;
    AgentState.ToolDraft draft = state.toolDraft().orElseThrow();
    AgentState revised;
    try {
      var root = JSON.readTree(draft.partialJson().isEmpty() ? "{}" : draft.partialJson());
      if (!root.isObject()) throw new IllegalArgumentException("arguments must be an object");
      Map<String, Object> arguments = JSON.convertValue(root, ARGUMENTS);
      revised = updateTool(state, draft.callId(), call -> new ToolUse(call.id(), call.name(),
          arguments, call.status()));
    } catch (Exception exception) {
      revised = updateTool(state, draft.callId(), call -> new ToolUse(call.id(), call.name(),
          call.arguments(), new ToolStatus.Failed("invalid tool arguments: "
              + exception.getMessage())));
    }
    return copy(revised, revised.thread(), revised.phase(), revised.activeTurnId(),
        revised.turnCounter(), revised.tokensIn(), revised.tokensOut(), revised.status(),
        Optional.empty(), revised.queued(), revised.sessionGrants());
  }

  private AgentState appendText(AgentState state, String text) {
    return updateLastAssistant(state, message -> withText(message,
        appendUtf8Capped(message.text(), text, MAX_STREAMING_BYTES)));
  }

  private AgentState updateLastAssistant(AgentState state, Function<Message, Message> updater) {
    var messages = new ArrayList<>(state.thread().messages());
    if (messages.isEmpty() || messages.getLast().role() != Role.ASSISTANT) return state;
    messages.set(messages.size() - 1, updater.apply(messages.getLast()));
    var thread = withMessages(state.thread(), messages, context.wallClock().get());
    return copy(state, thread, state.phase(), state.activeTurnId(), state.turnCounter(),
        state.tokensIn(), state.tokensOut(), state.status(), state.toolDraft(), state.queued(),
        state.sessionGrants());
  }

  private AgentState updateTool(AgentState state, String id, Function<ToolUse, ToolUse> updater) {
    var messages = new ArrayList<>(state.thread().messages());
    for (int messageIndex = messages.size() - 1; messageIndex >= 0; messageIndex--) {
      Message message = messages.get(messageIndex);
      var calls = new ArrayList<>(message.toolCalls());
      for (int callIndex = calls.size() - 1; callIndex >= 0; callIndex--) {
        if (calls.get(callIndex).id().value().equals(id)) {
          calls.set(callIndex, updater.apply(calls.get(callIndex)));
          messages.set(messageIndex, message.withToolCalls(calls));
          var thread = withMessages(state.thread(), messages, context.wallClock().get());
          return copy(state, thread, state.phase(), state.activeTurnId(), state.turnCounter(),
              state.tokensIn(), state.tokensOut(), state.status(), state.toolDraft(), state.queued(),
              state.sessionGrants());
        }
      }
    }
    return state;
  }

  private AgentState updateEveryTool(AgentState state, Function<ToolUse, ToolUse> updater) {
    var messages = new ArrayList<Message>(state.thread().messages().size());
    for (Message message : state.thread().messages())
      messages.add(message.withToolCalls(message.toolCalls().stream().map(updater).toList()));
    var thread = withMessages(state.thread(), messages, context.wallClock().get());
    return copy(state, thread, state.phase(), state.activeTurnId(), state.turnCounter(),
        state.tokensIn(), state.tokensOut(), state.status(), state.toolDraft(), state.queued(),
        state.sessionGrants());
  }

  private Optional<ToolUse> findTool(AgentState state, String id) {
    return state.thread().messages().stream().flatMap(message -> message.toolCalls().stream())
        .filter(call -> call.id().value().equals(id)).findFirst();
  }

  private static Optional<Message> lastAssistant(AgentState state) {
    return state.thread().messages().isEmpty() ? Optional.empty()
        : Optional.of(state.thread().messages().getLast()).filter(message ->
            message.role() == Role.ASSISTANT);
  }

  private static SessionPhase toAwaitingPermission(SessionPhase phase) {
    return switch (phase) {
      case SessionPhase.Streaming streaming -> SessionPhase.landPermission(streaming);
      case SessionPhase.ExecutingTool executing -> SessionPhase.landPermission(
          SessionPhase.resumeStream(executing));
      case SessionPhase.AwaitingPermission permission -> permission;
      case SessionPhase.Idle ignored -> throw new IllegalStateException("idle tool permission");
    };
  }

  private static SessionPhase toExecutingTool(SessionPhase phase) {
    return switch (phase) {
      case SessionPhase.AwaitingPermission permission -> SessionPhase.executeTool(permission);
      case SessionPhase.Streaming streaming -> SessionPhase.executeTool(
          SessionPhase.landPermission(streaming));
      case SessionPhase.ExecutingTool executing -> executing;
      case SessionPhase.Idle ignored -> throw new IllegalStateException("idle tool execution");
    };
  }

  private AgentState withActive(AgentState state, ActiveTurn active) {
    SessionPhase phase = switch (state.phase()) {
      case SessionPhase.Streaming ignored -> new SessionPhase.Streaming(active);
      case SessionPhase.AwaitingPermission ignored -> new SessionPhase.AwaitingPermission(active);
      case SessionPhase.ExecutingTool ignored -> new SessionPhase.ExecutingTool(active);
      case SessionPhase.Idle ignored -> throw new IllegalStateException("idle has no active context");
    };
    return copy(state, state.thread(), phase, state.activeTurnId(), state.turnCounter(),
        state.tokensIn(), state.tokensOut(), state.status(), state.toolDraft(), state.queued(),
        state.sessionGrants());
  }

  private Message message(Role role, String text, List<ImageContent> images, List<ToolUse> calls,
                          Instant now) {
    return new Message(context.messageIds().get(), role, text, images, List.of(), "", "", calls,
        now, Optional.empty(), Optional.empty(), false);
  }

  private static Message withText(Message message, String text) {
    return new Message(message.id(), message.role(), text, message.images(), message.attachments(),
        message.thinking(), message.thinkingSignature(), message.toolCalls(), message.timestamp(),
        message.checkpointId(), message.error(), message.isCompactSummary());
  }

  private static Message withError(Message message, String error) {
    return new Message(message.id(), message.role(), message.text(), message.images(),
        message.attachments(), message.thinking(), message.thinkingSignature(),
        message.toolCalls(), message.timestamp(), message.checkpointId(), Optional.of(error),
        message.isCompactSummary());
  }

  private static com.github.skanga.ajent.domain.Thread withMessages(
      com.github.skanga.ajent.domain.Thread thread, List<Message> messages, Instant now) {
    return new com.github.skanga.ajent.domain.Thread(thread.id(), thread.title(), messages,
        thread.createdAt(), now, thread.compactions());
  }

  private static AgentState copy(AgentState state,
                                 com.github.skanga.ajent.domain.Thread thread,
                                 SessionPhase phase, long activeTurnId, long turnCounter,
                                 int tokensIn, int tokensOut, String status,
                                 Optional<AgentState.ToolDraft> draft,
                                 List<RuntimeMessage.Submit> queued, Set<String> grants) {
    return new AgentState(thread, phase, activeTurnId, turnCounter, tokensIn, tokensOut, status,
        draft, queued, grants);
  }

  private static Step done(AgentState state) {
    return new Step(state, List.of());
  }

  private static String appendUtf8Capped(String existing, String addition, int cap) {
    int used = existing.getBytes(StandardCharsets.UTF_8).length;
    if (used >= cap || addition.isEmpty()) return existing;
    byte[] bytes = addition.getBytes(StandardCharsets.UTF_8);
    int room = cap - used;
    if (bytes.length <= room) return existing + addition;
    int characters = 0;
    int consumed = 0;
    while (characters < addition.length()) {
      int codePoint = addition.codePointAt(characters);
      int width = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
      if (consumed + width > room) break;
      consumed += width;
      characters += Character.charCount(codePoint);
    }
    return existing + addition.substring(0, characters);
  }
}
