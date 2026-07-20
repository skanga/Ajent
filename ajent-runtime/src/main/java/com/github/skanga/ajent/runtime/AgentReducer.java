package com.github.skanga.ajent.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.domain.ActiveTurn;
import com.github.skanga.ajent.domain.Attachment;
import com.github.skanga.ajent.domain.CancellationSignal;
import com.github.skanga.ajent.domain.CompactionRecord;
import com.github.skanga.ajent.domain.CheckpointId;
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
import com.github.skanga.ajent.core.loop.DoomLoopBreaker;
import com.github.skanga.ajent.provider.ErrorClass;
import com.github.skanga.ajent.provider.ProviderErrorPolicy;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.concurrent.ThreadLocalRandom;

/** Pure headless agent reducer: {@code (state, message) -> (state, effects)}. */
public final class AgentReducer {
  public enum ProviderRetryMode { RETRY, FAIL_FAST }

  private static final int MAX_STREAMING_BYTES = 8 * 1024 * 1024;
  private static final int MAX_TRUNCATION_RETRIES = 2;
  private static final long TOOL_PREVIEW_INTERVAL_NANOS = Duration.ofMillis(120).toNanos();
  private static final int TOOL_STRUCTURED_PARSE_GROWTH = 512;
  private static final Duration TICK_REBASE_THRESHOLD = Duration.ofSeconds(2);
  private static final Duration STREAM_STALL = Duration.ofSeconds(120);
  private static final Duration TOOL_NO_RUNNING_GRACE = Duration.ofSeconds(30);
  private static final Duration TOOL_WEDGE = Duration.ofSeconds(330);
  private static final String MAX_TOKENS_TOOL_ERROR =
      "Output token cap (max_tokens) was reached before the tool input finished streaming, "
          + "so the call was cut off. Even if the args parsed, the body is likely truncated. "
          + "Retry with a smaller payload: prefer `edit` over `write` for long files, or split "
          + "the change across multiple calls.";
  private static final String MID_STRING_TOOL_ERROR =
      "tool args truncated mid-string — the wire cut off inside a string value (likely "
          + "`content` / `command` / `new_text`), so the body is incomplete and the call was "
          + "refused. Re-emit the tool with the full payload — prefer `edit` over `write` for "
          + "long files, or split the change across multiple calls.";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() {};

  public record Context(LongSupplier nanoClock, Supplier<Instant> wallClock,
                        Supplier<MessageId> messageIds,
                        Function<ToolUse, PermissionVerdict> permissions,
                        DoubleSupplier retryJitter,
                        IntSupplier contextMax,
                        Supplier<Optional<String>> oauthRefreshToken,
                        Supplier<Optional<CheckpointId>> checkpointIds,
                        AttachmentContentPort attachmentContent,
                        ProviderRetryMode providerRetryMode) {
    public Context(LongSupplier nanoClock, Supplier<Instant> wallClock,
                   Supplier<MessageId> messageIds,
                   Function<ToolUse, PermissionVerdict> permissions,
                   DoubleSupplier retryJitter, IntSupplier contextMax,
                   Supplier<Optional<String>> oauthRefreshToken,
                   Supplier<Optional<CheckpointId>> checkpointIds,
                   AttachmentContentPort attachmentContent) {
      this(nanoClock, wallClock, messageIds, permissions, retryJitter, contextMax,
          oauthRefreshToken, checkpointIds, attachmentContent, ProviderRetryMode.RETRY);
    }

    public Context(LongSupplier nanoClock, Supplier<Instant> wallClock,
                   Supplier<MessageId> messageIds,
                   Function<ToolUse, PermissionVerdict> permissions) {
      this(nanoClock, wallClock, messageIds, permissions,
          () -> ThreadLocalRandom.current().nextDouble(0.80, 1.20), () -> 200_000,
          Optional::empty, Optional::empty, AttachmentContentPort.inline());
    }

    public Context(LongSupplier nanoClock, Supplier<Instant> wallClock,
                   Supplier<MessageId> messageIds,
                   Function<ToolUse, PermissionVerdict> permissions,
                   DoubleSupplier retryJitter) {
      this(nanoClock, wallClock, messageIds, permissions, retryJitter, () -> 200_000,
          Optional::empty, Optional::empty, AttachmentContentPort.inline());
    }

    public Context(LongSupplier nanoClock, Supplier<Instant> wallClock,
                   Supplier<MessageId> messageIds,
                   Function<ToolUse, PermissionVerdict> permissions,
                   DoubleSupplier retryJitter, IntSupplier contextMax) {
      this(nanoClock, wallClock, messageIds, permissions, retryJitter, contextMax,
          Optional::empty, Optional::empty, AttachmentContentPort.inline());
    }

    public Context(LongSupplier nanoClock, Supplier<Instant> wallClock,
                   Supplier<MessageId> messageIds,
                   Function<ToolUse, PermissionVerdict> permissions,
                   DoubleSupplier retryJitter, IntSupplier contextMax,
                   Supplier<Optional<String>> oauthRefreshToken) {
      this(nanoClock, wallClock, messageIds, permissions, retryJitter, contextMax,
          oauthRefreshToken, Optional::empty, AttachmentContentPort.inline());
    }

    public Context(LongSupplier nanoClock, Supplier<Instant> wallClock,
                   Supplier<MessageId> messageIds,
                   Function<ToolUse, PermissionVerdict> permissions,
                   DoubleSupplier retryJitter, IntSupplier contextMax,
                   Supplier<Optional<String>> oauthRefreshToken,
                   Supplier<Optional<CheckpointId>> checkpointIds) {
      this(nanoClock, wallClock, messageIds, permissions, retryJitter, contextMax,
          oauthRefreshToken, checkpointIds, AttachmentContentPort.inline());
    }

    public Context {
      Objects.requireNonNull(nanoClock, "nanoClock");
      Objects.requireNonNull(wallClock, "wallClock");
      Objects.requireNonNull(messageIds, "messageIds");
      Objects.requireNonNull(permissions, "permissions");
      Objects.requireNonNull(retryJitter, "retryJitter");
      Objects.requireNonNull(contextMax, "contextMax");
      Objects.requireNonNull(oauthRefreshToken, "oauthRefreshToken");
      Objects.requireNonNull(checkpointIds, "checkpointIds");
      Objects.requireNonNull(attachmentContent, "attachmentContent");
      Objects.requireNonNull(providerRetryMode, "providerRetryMode");
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
      case RuntimeMessage.ReplaceQueued replace -> replaceQueued(state, replace);
      case RuntimeMessage.ProviderEvent event -> providerEvent(state, event);
      case RuntimeMessage.ToolCompleted completed -> toolCompleted(state, completed);
      case RuntimeMessage.ToolProgress progress -> toolProgress(state, progress);
      case RuntimeMessage.PermissionResolved resolved -> permissionResolved(state, resolved);
      case RuntimeMessage.RetryStream retry -> retryStream(state, retry);
      case RuntimeMessage.TokenRefreshed refreshed -> tokenRefreshed(state, refreshed);
      case RuntimeMessage.CompactContext ignored -> compactContext(state);
      case RuntimeMessage.ProfileChanged ignored -> profileChanged(state);
      case RuntimeMessage.Tick ignored -> tick(state);
      case RuntimeMessage.Cancel ignored -> cancel(state);
    };
  }

  private static Step replaceQueued(AgentState state, RuntimeMessage.ReplaceQueued replace) {
    return done(new AgentState(state.thread(), state.phase(), state.activeTurnId(),
        state.turnCounter(), state.tokensIn(), state.tokensOut(), state.lastTickNanos(),
        state.status(), state.toolDraft(), replace.queued(), state.compaction(),
        state.oauthRefreshInFlight(), state.truncatedToolIds(), state.sessionGrants()));
  }

  private static Step profileChanged(AgentState state) {
    return new Step(new AgentState(state.thread(), state.phase(), state.activeTurnId(),
        state.turnCounter(), state.tokensIn(), state.tokensOut(), state.lastTickNanos(),
        state.status(), state.toolDraft(), state.queued(), state.compaction(),
        state.oauthRefreshInFlight(), state.truncatedToolIds(), Set.of()), List.of());
  }

  private Step tick(AgentState state) {
    long now = context.nanoClock().getAsLong();
    long previous = state.lastTickNanos() == 0 ? now : state.lastTickNanos();
    long gap = Math.max(0, now - previous);
    AgentState revised = withLastTick(state, now);
    if (revised.phase().active().isEmpty()) return done(revised);

    ActiveTurn active = revised.phase().active().orElseThrow();
    if (gap >= TICK_REBASE_THRESHOLD.toNanos() && active.lastEventNanos() != 0) {
      active = active.withLastEventNanos(active.lastEventNanos() + gap);
      revised = withActive(revised, active);
    }
    if (revised.phase() instanceof SessionPhase.Streaming
        && active.retryState() instanceof RetryState.Fresh
        && active.lastEventNanos() != 0
        && now - active.lastEventNanos() >= STREAM_STALL.toNanos()) {
      long silentSeconds = Duration.ofNanos(now - active.lastEventNanos()).toSeconds();
      active.cancellation().cancel();
      revised = withActive(revised, active.withRetryState(new RetryState.StallFired()));
      var error = new StreamEvent.Error("stream stalled — no events for " + silentSeconds + "s",
          Optional.empty(), ErrorClass.TRANSIENT, true);
      return new Step(revised, List.of(new RuntimeEffect.Schedule(Duration.ZERO,
          new RuntimeMessage.ProviderEvent(revised.activeTurnId(), error))));
    }
    return toolWedge(revised, now);
  }

  private Step toolWedge(AgentState state, long now) {
    if (!(state.phase() instanceof SessionPhase.ExecutingTool)) return done(state);
    Optional<Message> assistant = lastAssistant(state);
    if (assistant.isEmpty()) return done(state);
    boolean anyRunning = false;
    boolean wedged = false;
    AgentState revised = state;
    for (ToolUse call : assistant.orElseThrow().toolCalls()) {
      if (!(call.status() instanceof ToolStatus.Running running)) continue;
      anyRunning = true;
      if (running.startedNanos() == 0
          || now - running.startedNanos() < TOOL_WEDGE.toNanos()) continue;
      long seconds = Duration.ofNanos(now - running.startedNanos()).toSeconds();
      String failure = "tool ran " + seconds + "s with no result — worker likely hung on a "
          + "blocking syscall; failing it so the turn can recover. The worker thread may "
          + "continue in the background; its result is discarded if it ever returns.";
      revised = updateTool(revised, call.id().value(), value -> new ToolUse(value.id(),
          value.name(), value.arguments(), new ToolStatus.Failed(running.startedNanos(), now,
              failure)));
      wedged = true;
    }
    ActiveTurn active = revised.phase().active().orElseThrow();
    if (!anyRunning && active.lastEventNanos() != 0
        && now - active.lastEventNanos() >= TOOL_NO_RUNNING_GRACE.toNanos()) wedged = true;
    return wedged ? kickTools(revised) : done(revised);
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
    Optional<CheckpointId> checkpoint = submit.checkpointId().isPresent()
        ? submit.checkpointId() : context.checkpointIds().get();
    messages.add(message(Role.USER, submit.text(), submit.images(), submit.attachments(), List.of(),
        now, checkpoint));
    messages.add(message(Role.ASSISTANT, "", List.of(), List.of(), now));
    var thread = withMessages(state.thread(), messages, now);
    var cancellation = new CancellationSignal();
    SessionPhase phase = SessionPhase.start(new SessionPhase.Idle(),
        ActiveTurn.start(cancellation, context.nanoClock().getAsLong()));
    AgentState revised = clearTruncationSignals(copy(state, thread, phase, turnId, turnId,
        state.tokensIn(), state.tokensOut(), "", Optional.empty(), state.queued(),
        state.sessionGrants()));
    var effects = new ArrayList<RuntimeEffect>();
    effects.add(new RuntimeEffect.Persist(thread));
    checkpoint.ifPresent(id -> effects.add(new RuntimeEffect.CreateCheckpoint(id)));
    effects.add(new RuntimeEffect.StartStream(turnId, wireMessages(thread), cancellation));
    return new Step(revised, effects);
  }

  private Step compactContext(AgentState state) {
    if (!(state.phase() instanceof SessionPhase.Idle)
        || state.compaction().active().isPresent() || state.thread().messages().isEmpty())
      return done(state);
    long turnId = state.turnCounter() + 1;
    var cancellation = new CancellationSignal();
    SessionPhase phase = SessionPhase.start(new SessionPhase.Idle(),
        ActiveTurn.start(cancellation, context.nanoClock().getAsLong()));
    var active = new AgentState.ActiveCompaction(state.thread().messages().size(), "");
    var compaction = new AgentState.Compaction(Optional.of(active),
        state.compaction().recentCompacts(), state.compaction().turnsSinceLastCompact(),
        state.compaction().autoDisabled());
    AgentState revised = withCompaction(copy(state, state.thread(), phase, turnId, turnId,
        state.tokensIn(), state.tokensOut(), "compacting context…", Optional.empty(),
        state.queued(), state.sessionGrants()), compaction);
    return new Step(revised, List.of(new RuntimeEffect.StartStream(turnId,
        ConversationWire.forCompaction(state.thread(), context.contextMax().getAsInt()),
        cancellation)));
  }

  private Step providerEvent(AgentState state, RuntimeMessage.ProviderEvent envelope) {
    if (state.activeTurnId() != envelope.turnId() || state.phase() instanceof SessionPhase.Idle)
      return done(state);
    AgentState live = withActive(state, state.phase().active().orElseThrow()
        .withLastEventNanos(context.nanoClock().getAsLong()));
    if (live.compaction().active().isPresent())
      return compactionProviderEvent(live, envelope.event());
    return switch (envelope.event()) {
      case StreamEvent.Started ignored -> done(streamStarted(live));
      case StreamEvent.TextDelta delta -> done(appendText(recordDelta(live, delta.text()),
          delta.text()));
      case StreamEvent.TextBlockClosed ignored -> done(closeTextBlock(live));
      case StreamEvent.ThinkingDelta delta -> done(appendThinking(heartbeat(live), delta));
      case StreamEvent.ToolUseStart start -> done(startTool(live, start));
      case StreamEvent.ToolUseDelta delta -> done(appendToolArguments(
          recordDelta(live, delta.partialJson()), delta.partialJson()));
      case StreamEvent.ToolUseEnd ignored -> done(endTool(live));
      case StreamEvent.Heartbeat ignored -> done(heartbeat(live));
      case StreamEvent.Usage usage -> done(applyUsage(live, usage));
      case StreamEvent.Finished finished -> finalizeStream(live, finished.stopReason());
      case StreamEvent.Error error -> streamError(live, error);
    };
  }

  private Step compactionProviderEvent(AgentState state, StreamEvent event) {
    return switch (event) {
      case StreamEvent.Started ignored -> done(streamStarted(state));
      case StreamEvent.TextDelta delta -> done(appendCompaction(recordDelta(state, delta.text()),
          delta.text()));
      case StreamEvent.TextBlockClosed ignored -> done(state);
      case StreamEvent.ThinkingDelta ignored -> done(heartbeat(state));
      case StreamEvent.Heartbeat ignored -> done(heartbeat(state));
      case StreamEvent.Finished ignored -> finishCompaction(state);
      case StreamEvent.Error error -> streamError(state, error);
      case StreamEvent.ToolUseStart ignored -> done(state);
      case StreamEvent.ToolUseDelta ignored -> done(state);
      case StreamEvent.ToolUseEnd ignored -> done(state);
      case StreamEvent.Usage ignored -> done(state);
    };
  }

  private AgentState applyUsage(AgentState state, StreamEvent.Usage usage) {
    int input = state.tokensIn();
    if (usage.inputTokens() != 0 || usage.cacheCreationInputTokens() != 0
        || usage.cacheReadInputTokens() != 0) {
      input = usage.inputTokens() + usage.cacheCreationInputTokens()
          + usage.cacheReadInputTokens();
    }
    int output = usage.outputTokens() == 0 ? state.tokensOut() : usage.outputTokens();
    return copy(state, state.thread(), state.phase(), state.activeTurnId(), state.turnCounter(),
        input, output, state.status(), state.toolDraft(), state.queued(), state.sessionGrants());
  }

  private AgentState appendThinking(AgentState state, StreamEvent.ThinkingDelta delta) {
    return updateLastAssistant(state, message -> new Message(message.id(), message.role(),
        message.text(), message.images(), message.attachments(), message.thinking() + delta.text(),
        delta.signature().isEmpty() ? message.thinkingSignature() : delta.signature(),
        message.toolCalls(), message.timestamp(), message.checkpointId(), message.error(),
        message.textBlockClosed(), message.isCompactSummary()));
  }

  private AgentState closeTextBlock(AgentState state) {
    return updateLastAssistant(state, message -> new Message(message.id(), message.role(),
        message.text(), message.images(), message.attachments(), message.thinking(),
        message.thinkingSignature(), message.toolCalls(), message.timestamp(),
        message.checkpointId(), message.error(), true, message.isCompactSummary()));
  }

  private AgentState appendCompaction(AgentState state, String text) {
    AgentState.ActiveCompaction active = state.compaction().active().orElseThrow();
    String buffer = appendUtf8Capped(active.buffer(), text, MAX_STREAMING_BYTES);
    var revisedActive = new AgentState.ActiveCompaction(active.targetIndex(), buffer);
    var revised = new AgentState.Compaction(Optional.of(revisedActive),
        state.compaction().recentCompacts(), state.compaction().turnsSinceLastCompact(),
        state.compaction().autoDisabled());
    return withCompaction(state, revised);
  }

  private Step finishCompaction(AgentState state) {
    AgentState.ActiveCompaction active = state.compaction().active().orElseThrow();
    String summary = active.buffer().strip();
    if (summary.isEmpty()) summary = "[compaction produced no text]";
    int target = Math.min(active.targetIndex(), state.thread().messages().size());
    var records = new ArrayList<>(state.thread().compactions());
    records.add(new CompactionRecord(target, summary, context.wallClock().get()));
    var thread = new com.github.skanga.ajent.domain.Thread(state.thread().id(),
        state.thread().title(), state.thread().messages(), state.thread().createdAt(),
        context.wallClock().get(), records);
    int recent = state.compaction().turnsSinceLastCompact() <= 3
        ? state.compaction().recentCompacts() + 1 : 1;
    boolean disabled = recent >= 3;
    var compaction = new AgentState.Compaction(Optional.empty(), recent, 0, disabled);
    String status = disabled ? "auto-compact disabled (rapid refill); use /compact manually"
        : state.queued().isEmpty() ? "context compacted" : "";
    AgentState idle = withCompaction(copy(state, thread, new SessionPhase.Idle(), 0,
        state.turnCounter(), state.tokensIn(), state.tokensOut(), status, Optional.empty(),
        state.queued(), state.sessionGrants()), compaction);
    var effects = new ArrayList<RuntimeEffect>();
    effects.add(new RuntimeEffect.Persist(thread));
    if (idle.queued().isEmpty()) return new Step(idle, effects);
    RuntimeMessage.Submit head = idle.queued().getFirst();
    AgentState ready = copy(idle, idle.thread(), idle.phase(), idle.activeTurnId(),
        idle.turnCounter(), idle.tokensIn(), idle.tokensOut(), idle.status(), idle.toolDraft(),
        idle.queued().subList(1, idle.queued().size()), idle.sessionGrants());
    Step submitted = submit(ready, head);
    effects.addAll(submitted.effects());
    return new Step(submitted.state(), effects);
  }

  private Step finalizeStream(AgentState state,
                              com.github.skanga.ajent.provider.stream.StopReason stopReason) {
    DraftFinalization draft = finalizeDraft(state, stopReason);
    AgentState revised = draft.state();
    if (draft.truncated() && stopReason !=
        com.github.skanga.ajent.provider.stream.StopReason.MAX_TOKENS) {
      ActiveTurn active = revised.phase().active().orElseThrow();
      Optional<Message> assistant = lastAssistant(revised);
      boolean committed = assistant.map(message -> !message.text().isEmpty()
          || message.toolCalls().stream().anyMatch(call ->
              call.status() instanceof ToolStatus.Done
                  || call.status() instanceof ToolStatus.Running)).orElse(false);
      if (!committed && active.truncationRetries() < MAX_TRUNCATION_RETRIES) {
        ActiveTurn retry = active.withTruncationRetries(active.truncationRetries() + 1)
            .withCancellation(new CancellationSignal());
        revised = replaceUncommittedAssistant(withActive(revised, retry));
        revised = copy(revised, revised.thread(), revised.phase(), revised.activeTurnId(),
            revised.turnCounter(), revised.tokensIn(), revised.tokensOut(),
            "retrying (upstream cut off)…", Optional.empty(), revised.queued(),
            revised.sessionGrants());
        return new Step(revised, List.of(new RuntimeEffect.StartStream(revised.activeTurnId(),
            wireMessages(revised.thread()), retry.cancellation())));
      }
      revised = clearTruncationSignals(failPendingTools(revised, MID_STRING_TOOL_ERROR));
    }
    List<ToolUse> calls = lastAssistant(revised).map(Message::toolCalls).orElse(List.of());
    if (!calls.isEmpty()) return kickTools(revised);
    return finishNaturalTurn(revised);
  }

  private Step finishNaturalTurn(AgentState state) {
    int turns = Math.min(1_000_000, state.compaction().turnsSinceLastCompact() + 1);
    boolean disabled = state.compaction().autoDisabled();
    int recent = state.compaction().recentCompacts();
    if (disabled && turns > 10) {
      disabled = false;
      recent = 0;
    }
    var policy = new AgentState.Compaction(state.compaction().active(), recent, turns, disabled);
    Step finished = finishTurn(withCompaction(state, policy), "");
    AgentState idle = finished.state();
    int contextMax = context.contextMax().getAsInt();
    int threshold = Math.max(0, contextMax - 13_000 - 4_000);
    boolean shouldCompact = idle.phase() instanceof SessionPhase.Idle
        && idle.compaction().active().isEmpty() && !idle.compaction().autoDisabled()
        && contextMax > 0 && idle.queued().isEmpty() && !idle.thread().messages().isEmpty()
        && Math.max(idle.tokensIn(), ConversationWire.estimateTokens(
            ConversationWire.messages(idle.thread()))) > threshold;
    if (!shouldCompact) return finished;
    Step compacting = compactContext(idle);
    var effects = new ArrayList<>(finished.effects());
    effects.addAll(compacting.effects());
    return new Step(compacting.state(), effects);
  }

  private record DraftFinalization(AgentState state, boolean truncated) {}

  private DraftFinalization finalizeDraft(
      AgentState state, com.github.skanga.ajent.provider.stream.StopReason stopReason) {
    if (state.toolDraft().isEmpty()) {
      return stopReason == com.github.skanga.ajent.provider.stream.StopReason.MAX_TOKENS
          ? new DraftFinalization(clearTruncationSignals(
              failPendingTools(state, MAX_TOKENS_TOOL_ERROR)), false)
          : validatePendingTools(state, false);
    }
    AgentState.ToolDraft draft = state.toolDraft().orElseThrow();
    if (stopReason == com.github.skanga.ajent.provider.stream.StopReason.MAX_TOKENS) {
      AgentState cleared = clearDraft(state);
      return new DraftFinalization(clearTruncationSignals(
          failPendingTools(cleared, MAX_TOKENS_TOOL_ERROR)), false);
    }
    if (PartialJson.endedInsideString(draft.partialJson())) {
      AgentState marked = markTruncated(clearDraft(state), draft.callId());
      return validatePendingTools(marked, true);
    }
    try {
      var root = JSON.readTree(PartialJson.close(draft.partialJson()));
      if (!root.isObject()) throw new IllegalArgumentException("arguments must be an object");
      Map<String, Object> arguments = JSON.convertValue(root, ARGUMENTS);
      AgentState parsed = updateTool(state, draft.callId(), call -> new ToolUse(call.id(),
          call.name(), arguments, call.status()));
      String missing = missingRequiredField(findTool(parsed, draft.callId()).orElseThrow());
      parsed = clearDraft(parsed);
      if (missing.isEmpty()) return validatePendingTools(parsed, false);
      String failure = "Tool call arguments look incomplete — `" + missing
          + "` is missing. This usually means the stream was truncated before the full tool "
          + "input arrived. Please emit a fresh tool call with every required field populated "
          + "(including `" + missing + "`).";
      AgentState failed = updateTool(parsed, draft.callId(), call -> new ToolUse(call.id(),
          call.name(), call.arguments(), failed(call, failure)));
      return validatePendingTools(failed, true);
    } catch (Exception exception) {
      AgentState failed = updateTool(state, draft.callId(), call -> new ToolUse(call.id(),
          call.name(), call.arguments(), failed(call,
              "invalid tool arguments: " + exception.getMessage())));
      return new DraftFinalization(clearDraft(failed), false);
    }
  }

  private AgentState clearDraft(AgentState state) {
    return copy(state, state.thread(), state.phase(), state.activeTurnId(), state.turnCounter(),
        state.tokensIn(), state.tokensOut(), state.status(), Optional.empty(), state.queued(),
        state.sessionGrants());
  }

  private DraftFinalization validatePendingTools(AgentState state, boolean truncated) {
    AgentState revised = state;
    for (ToolUse call : lastAssistant(state).map(Message::toolCalls).orElse(List.of())) {
      if (!(call.status() instanceof ToolStatus.Pending)) continue;
      if (state.truncatedToolIds().contains(call.id().value())) {
        truncated = true;
        continue;
      }
      String missing = missingRequiredField(call);
      if (missing.isEmpty()) continue;
      String failure = "Tool call arguments look incomplete — `" + missing
          + "` is missing. This usually means the stream was truncated before the full tool "
          + "input arrived. Please emit a fresh tool call with every required field populated "
          + "(including `" + missing + "`).";
      revised = updateTool(revised, call.id().value(), value -> new ToolUse(value.id(),
          value.name(), value.arguments(), failed(value, failure)));
      truncated = true;
    }
    return new DraftFinalization(revised, truncated);
  }

  private AgentState markTruncated(AgentState state, String callId) {
    var ids = new java.util.HashSet<>(state.truncatedToolIds());
    ids.add(callId);
    return new AgentState(state.thread(), state.phase(), state.activeTurnId(), state.turnCounter(),
        state.tokensIn(), state.tokensOut(), state.lastTickNanos(), state.status(),
        state.toolDraft(), state.queued(),
        state.compaction(), state.oauthRefreshInFlight(), ids, state.sessionGrants());
  }

  private AgentState clearTruncationSignals(AgentState state) {
    if (state.truncatedToolIds().isEmpty()) return state;
    return new AgentState(state.thread(), state.phase(), state.activeTurnId(), state.turnCounter(),
        state.tokensIn(), state.tokensOut(), state.lastTickNanos(), state.status(),
        state.toolDraft(), state.queued(),
        state.compaction(), state.oauthRefreshInFlight(), Set.of(), state.sessionGrants());
  }

  private static AgentState withCompaction(AgentState state, AgentState.Compaction compaction) {
    return new AgentState(state.thread(), state.phase(), state.activeTurnId(), state.turnCounter(),
        state.tokensIn(), state.tokensOut(), state.lastTickNanos(), state.status(),
        state.toolDraft(), state.queued(),
        compaction, state.oauthRefreshInFlight(), state.truncatedToolIds(),
        state.sessionGrants());
  }

  private AgentState failPendingTools(AgentState state, String error) {
    return updateEveryTool(state, call -> call.status() instanceof ToolStatus.Pending
        ? new ToolUse(call.id(), call.name(), call.arguments(), failed(call, error)) : call);
  }

  private ToolStatus.Failed failed(ToolUse call, String output) {
    return new ToolStatus.Failed(call.status().startedNanos(), context.nanoClock().getAsLong(),
        output);
  }

  static String missingRequiredField(ToolUse call) {
    Map<String, Object> arguments = call.arguments();
    return switch (call.name().value()) {
      case "write" -> !hasString(arguments, "path", "file_path", "filepath", "filename")
          ? "path" : !hasString(arguments, "content", "file_text", "text", "file_content",
              "contents", "body", "data") ? "content" : "";
      case "edit" -> missingEditField(arguments);
      case "bash", "diagnostics" -> hasString(arguments, "command") ? "" : "command";
      case "grep" -> hasString(arguments, "pattern") ? "" : "pattern";
      case "find_definition" -> hasString(arguments, "symbol") ? "" : "symbol";
      case "search_docs" -> hasString(arguments, "query") ? "" : "query";
      case "web_fetch" -> hasString(arguments, "url") ? "" : "url";
      case "git_commit" -> hasString(arguments, "message") ? "" : "message";
      case "remember" -> hasString(arguments, "text") ? "" : "text";
      case "task" -> hasString(arguments, "prompt") ? "" : "prompt";
      case "skill" -> hasString(arguments, "name") ? "" : "name";
      default -> "";
    };
  }

  private static String missingEditField(Map<String, Object> arguments) {
    if (!hasString(arguments, "path", "file_path", "filepath", "filename")) return "path";
    if (arguments.get("edits") instanceof List<?> edits && !edits.isEmpty()) return "";
    if (!hasString(arguments, "old_string", "old_str", "oldStr")) return "old_string";
    return hasString(arguments, "new_string", "new_str", "newStr") ? "" : "new_string";
  }

  private static boolean hasString(Map<String, Object> arguments, String... keys) {
    for (String key : keys) {
      if (arguments.get(key) instanceof String value && !value.isEmpty()) return true;
    }
    return false;
  }

  private Step toolCompleted(AgentState state, RuntimeMessage.ToolCompleted completed) {
    if (completed.turnId() != state.activeTurnId() || state.phase() instanceof SessionPhase.Idle)
      return done(state);
    AgentState revised = updateTool(state, completed.callId(), call -> {
      if (call.status().isTerminal()) return call;
      long now = context.nanoClock().getAsLong();
      ToolStatus status = switch (completed.result()) {
        case ToolCompletion.Success success -> new ToolStatus.Done(
            call.status().startedNanos(), now, success.output());
        case ToolCompletion.Failure failure -> new ToolStatus.Failed(
            call.status().startedNanos(), now, failure.error());
      };
      return new ToolUse(call.id(), call.name(), call.arguments(), status);
    });
    return kickTools(revised);
  }

  private Step toolProgress(AgentState state, RuntimeMessage.ToolProgress progress) {
    if (progress.turnId() != state.activeTurnId() || state.phase() instanceof SessionPhase.Idle)
      return done(state);
    return done(updateTool(state, progress.callId(), call ->
        call.status() instanceof ToolStatus.Running running
            ? new ToolUse(call.id(), call.name(), call.arguments(),
                new ToolStatus.Running(running.startedNanos(), progress.text()))
            : call));
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
          call.name(), call.arguments(), new ToolStatus.Approved(call.status().startedNanos())));
      return kickTools(approved);
    }
    AgentState rejected = updateTool(withGrants, resolved.callId(), call -> new ToolUse(call.id(),
        call.name(), call.arguments(), new ToolStatus.Rejected(context.nanoClock().getAsLong())));
    return kickTools(rejected);
  }

  private Step cancel(AgentState state) {
    if (state.phase() instanceof SessionPhase.Idle) return done(state);
    if (state.compaction().active().isPresent()) return cancelCompaction(state);
    state.phase().active().orElseThrow().cancellation().cancel();
    AgentState settled = updateEveryTool(state, call -> call.status().isTerminal() ? call
        : new ToolUse(call.id(), call.name(), call.arguments(),
            new ToolStatus.Rejected(context.nanoClock().getAsLong())));
    SessionPhase.Idle idle = SessionPhase.abort(settled.phase());
    AgentState revised = clearTruncationSignals(copy(settled, settled.thread(), idle, 0, settled.turnCounter(),
        settled.tokensIn(), settled.tokensOut(), "cancelled", Optional.empty(), settled.queued(),
        settled.sessionGrants()));
    return new Step(revised, List.of(new RuntimeEffect.Persist(revised.thread())));
  }

  private Step cancelCompaction(AgentState state) {
    state.phase().active().orElseThrow().cancellation().cancel();
    var compaction = new AgentState.Compaction(Optional.empty(),
        state.compaction().recentCompacts(), state.compaction().turnsSinceLastCompact(),
        state.compaction().autoDisabled());
    AgentState idle = withCompaction(copy(state, state.thread(), new SessionPhase.Idle(), 0,
        state.turnCounter(), state.tokensIn(), state.tokensOut(), "cancelled", Optional.empty(),
        state.queued(), state.sessionGrants()), compaction);
    if (idle.queued().isEmpty()) return done(idle);
    RuntimeMessage.Submit head = idle.queued().getFirst();
    AgentState ready = copy(idle, idle.thread(), idle.phase(), idle.activeTurnId(),
        idle.turnCounter(), idle.tokensIn(), idle.tokensOut(), idle.status(), idle.toolDraft(),
        idle.queued().subList(1, idle.queued().size()), idle.sessionGrants());
    return submit(ready, head);
  }

  private Step kickTools(AgentState state) {
    List<ToolUse> calls = lastAssistant(state).map(Message::toolCalls).orElse(List.of());
    List<Integer> promote = ToolScheduler.scheduleParallelBatch(calls).promote();
    if (promote.isEmpty()) {
      boolean running = calls.stream().anyMatch(call -> call.status() instanceof ToolStatus.Running);
      boolean waiting = calls.stream().anyMatch(call -> call.status() instanceof ToolStatus.Pending
          || call.status() instanceof ToolStatus.Approved);
      if (running || waiting) return done(state);
      Optional<DoomLoopBreaker.LoopBreak> loop =
          DoomLoopBreaker.shouldBreak(state.thread().messages(), true);
      return loop.isPresent() ? finishTurn(state, loop.orElseThrow().reason())
          : continueStream(state);
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
            value.name(), value.arguments(), new ToolStatus.Failed(
                value.status().startedNanos(), context.nanoClock().getAsLong(),
                "Tool call denied by policy.")));
        denied = true;
        continue;
      }
      if (verdict == PermissionVerdict.PROMPT) {
        effects.add(new RuntimeEffect.RequestPermission(revised.activeTurnId(), current));
        prompt = true;
        break; // AgenTTY presents one permission card at a time.
      }
      revised = updateTool(revised, current.id().value(), value -> new ToolUse(value.id(),
          value.name(), value.arguments(), new ToolStatus.Running(
              value.status().startedNanos(), "")));
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
    ActiveTurn active = state.phase().active().orElseThrow()
        .withCancellation(new CancellationSignal())
        .withLastEventNanos(context.nanoClock().getAsLong())
        .withRetryState(new RetryState.Fresh());
    long turnId = state.turnCounter() + 1;
    Instant now = context.wallClock().get();
    var messages = new ArrayList<>(state.thread().messages());
    messages.add(message(Role.ASSISTANT, "", List.of(), List.of(), now));
    var thread = withMessages(state.thread(), messages, now);
    SessionPhase phase = new SessionPhase.Streaming(active);
    AgentState revised = copy(withActive(state, active), thread, phase, turnId, turnId, state.tokensIn(),
        state.tokensOut(), "", Optional.empty(), state.queued(), state.sessionGrants());
    return new Step(revised, List.of(new RuntimeEffect.Persist(thread),
        new RuntimeEffect.StartStream(turnId, wireMessages(thread), active.cancellation())));
  }

  private Step finishTurn(AgentState state, String status) {
    SessionPhase.Idle idle = state.phase() instanceof SessionPhase.Streaming streaming
        ? SessionPhase.finish(streaming) : SessionPhase.abort(state.phase());
    AgentState revised = clearTruncationSignals(copy(state, state.thread(), idle, 0, state.turnCounter(),
        state.tokensIn(), state.tokensOut(), status, Optional.empty(), state.queued(),
        state.sessionGrants()));
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
    if (state.compaction().active().isPresent()) return compactionError(state, error);
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
    if (context.providerRetryMode() == ProviderRetryMode.RETRY
        && errorClass == ErrorClass.AUTH && !committed && !state.oauthRefreshInFlight()
        && priorTransient < ProviderErrorPolicy.MAX_RETRIES) {
      Optional<String> refreshToken = context.oauthRefreshToken().get()
          .filter(token -> !token.isEmpty());
      if (refreshToken.isPresent()) {
        ActiveTurn parked = active.withTransientRetries(priorTransient + 1)
            .withLastFailureNanos(now).withRetryState(new RetryState.Scheduled());
        AgentState revised = replaceUncommittedAssistant(withActive(state, parked));
        revised = withOAuthRefreshInFlight(revised, true);
        revised = copy(revised, revised.thread(), revised.phase(), revised.activeTurnId(),
            revised.turnCounter(), revised.tokensIn(), revised.tokensOut(),
            "auth expired — refreshing token…", Optional.empty(), revised.queued(),
            revised.sessionGrants());
        return new Step(revised, List.of(new RuntimeEffect.RefreshOAuth(
            revised.activeTurnId(), refreshToken.orElseThrow())));
      }
    }
    boolean midStream = active.retryState() instanceof RetryState.StallFired
        || active.firstDeltaNanos() != 0;
    int retryCap = ProviderErrorPolicy.maxRetries(errorClass, midStream);
    int priorBudget = midStream ? active.midStreamFailures() : priorTransient;
    boolean canRetry = context.providerRetryMode() == ProviderRetryMode.RETRY
        && (errorClass == ErrorClass.TRANSIENT
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

  private Step compactionError(AgentState state, StreamEvent.Error error) {
    ActiveTurn active = state.phase().active().orElseThrow();
    if (active.retryState() instanceof RetryState.Scheduled) return done(state);
    ErrorClass errorClass = error.errorClass();
    if (errorClass == ErrorClass.CANCELLED
        && (error.fromStall() || active.retryState() instanceof RetryState.StallFired))
      errorClass = ErrorClass.TRANSIENT;
    long now = context.nanoClock().getAsLong();
    int prior = active.transientRetries();
    if (active.lastFailureNanos() != 0
        && now - active.lastFailureNanos() >= ProviderErrorPolicy.RETRY_DECAY.toNanos())
      prior = 0;
    boolean canRetry = context.providerRetryMode() == ProviderRetryMode.RETRY
        && (errorClass == ErrorClass.TRANSIENT
        || errorClass == ErrorClass.RATE_LIMIT) && prior < ProviderErrorPolicy.MAX_RETRIES;
    if (canRetry) {
      ErrorClass retryClass = errorClass;
      int attempt = prior;
      Duration delay = error.retryAfter().map(AgentReducer::clampRetryAfter).orElseGet(() ->
          ProviderErrorPolicy.backoffWithJitter(retryClass, attempt,
              context.retryJitter().getAsDouble()));
      ActiveTurn scheduled = active.withTransientRetries(prior + 1).withLastFailureNanos(now)
          .withRetryState(new RetryState.Scheduled());
      AgentState revised = withActive(state, scheduled);
      AgentState.ActiveCompaction compact = revised.compaction().active().orElseThrow();
      var empty = new AgentState.ActiveCompaction(compact.targetIndex(), "");
      revised = withCompaction(revised, new AgentState.Compaction(Optional.of(empty),
          revised.compaction().recentCompacts(), revised.compaction().turnsSinceLastCompact(),
          revised.compaction().autoDisabled()));
      long seconds = Math.max(1, (delay.toMillis() + 999) / 1_000);
      revised = copy(revised, revised.thread(), revised.phase(), revised.activeTurnId(),
          revised.turnCounter(), revised.tokensIn(), revised.tokensOut(),
          "compacting — retrying in " + seconds + "s", Optional.empty(), revised.queued(),
          revised.sessionGrants());
      return new Step(revised, List.of(new RuntimeEffect.Schedule(delay,
          new RuntimeMessage.RetryStream(state.activeTurnId()))));
    }

    var compaction = new AgentState.Compaction(Optional.empty(),
        state.compaction().recentCompacts(), state.compaction().turnsSinceLastCompact(),
        state.compaction().autoDisabled());
    String status = errorClass == ErrorClass.CANCELLED ? "compaction cancelled"
        : "compaction failed: " + error.message() + " — retry with /compact";
    AgentState idle = withCompaction(copy(state, state.thread(), new SessionPhase.Idle(), 0,
        state.turnCounter(), state.tokensIn(), state.tokensOut(), status, Optional.empty(),
        state.queued(), state.sessionGrants()), compaction);
    if (idle.queued().isEmpty()) return done(idle);
    RuntimeMessage.Submit head = idle.queued().getFirst();
    AgentState ready = copy(idle, idle.thread(), idle.phase(), idle.activeTurnId(),
        idle.turnCounter(), idle.tokensIn(), idle.tokensOut(), idle.status(), idle.toolDraft(),
        idle.queued().subList(1, idle.queued().size()), idle.sessionGrants());
    return submit(ready, head);
  }

  private Step tokenRefreshed(AgentState state, RuntimeMessage.TokenRefreshed refreshed) {
    if (!state.oauthRefreshInFlight()) return done(state);
    boolean parked = refreshed.turnId() == state.activeTurnId()
        && state.phase().active().map(ActiveTurn::retryState)
            .filter(RetryState.Scheduled.class::isInstance).isPresent();
    AgentState revised = withOAuthRefreshInFlight(state, false);
    return switch (refreshed.result()) {
      case OAuthRefreshPort.Result.Success ignored -> {
        revised = copy(revised, revised.thread(), revised.phase(), revised.activeTurnId(),
            revised.turnCounter(), revised.tokensIn(), revised.tokensOut(),
            "OAuth token refreshed", revised.toolDraft(), revised.queued(),
            revised.sessionGrants());
        if (parked) {
          yield new Step(revised, List.of(new RuntimeEffect.Schedule(Duration.ZERO,
              new RuntimeMessage.RetryStream(refreshed.turnId()))));
        }
        if (revised.phase() instanceof SessionPhase.Idle && !revised.queued().isEmpty()) {
          RuntimeMessage.Submit head = revised.queued().getFirst();
          AgentState ready = copy(revised, revised.thread(), revised.phase(),
              revised.activeTurnId(), revised.turnCounter(), revised.tokensIn(),
              revised.tokensOut(), revised.status(), revised.toolDraft(),
              revised.queued().subList(1, revised.queued().size()), revised.sessionGrants());
          yield submit(ready, head);
        }
        yield done(revised);
      }
      case OAuthRefreshPort.Result.Failure failure -> parked
          ? authRefreshFailed(revised, failure.error())
          : done(copy(revised, revised.thread(), revised.phase(), revised.activeTurnId(),
              revised.turnCounter(), revised.tokensIn(), revised.tokensOut(),
              "error: token refresh failed: " + failure.error(), revised.toolDraft(),
              revised.queued(), revised.sessionGrants()));
    };
  }

  private Step authRefreshFailed(AgentState state, String error) {
    String status = "error: token refresh failed: " + error;
    AgentState revised = updateLastAssistant(state, message -> withError(message, status));
    revised = updateEveryTool(revised, call -> call.status().isTerminal() ? call
        : new ToolUse(call.id(), call.name(), call.arguments(),
            failed(call, "auth refresh failed")));
    revised = dropEmptyAssistant(revised);
    revised = copy(revised, revised.thread(), new SessionPhase.Idle(), 0,
        revised.turnCounter(), revised.tokensIn(), revised.tokensOut(), status, Optional.empty(),
        revised.queued(), revised.sessionGrants());
    return new Step(revised, List.of(new RuntimeEffect.Persist(revised.thread())));
  }

  private AgentState dropEmptyAssistant(AgentState state) {
    List<Message> current = state.thread().messages();
    if (current.isEmpty()) return state;
    Message last = current.getLast();
    if (last.role() != Role.ASSISTANT || !last.text().isEmpty() || !last.toolCalls().isEmpty())
      return state;
    var messages = new ArrayList<>(current);
    messages.removeLast();
    var thread = withMessages(state.thread(), messages, context.wallClock().get());
    return copy(state, thread, state.phase(), state.activeTurnId(), state.turnCounter(),
        state.tokensIn(), state.tokensOut(), state.status(), state.toolDraft(), state.queued(),
        state.sessionGrants());
  }

  private Step retryStream(AgentState state, RuntimeMessage.RetryStream retry) {
    if (state.activeTurnId() != retry.turnId() || state.phase() instanceof SessionPhase.Idle)
      return done(state);
    ActiveTurn active = state.phase().active().orElseThrow();
    if (!(active.retryState() instanceof RetryState.Scheduled)) return done(state);
    ActiveTurn fresh = active.withRetryState(new RetryState.Fresh())
        .withCancellation(new CancellationSignal());
    AgentState revised = withActive(state, fresh);
    return new Step(revised, List.of(new RuntimeEffect.StartStream(retry.turnId(),
        activeWireMessages(revised), fresh.cancellation())));
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
    AgentState revised = copy(state, thread, state.phase(), state.activeTurnId(), state.turnCounter(),
        state.tokensIn(), state.tokensOut(), state.status(), Optional.empty(), state.queued(),
        state.sessionGrants());
    return clearTruncationSignals(revised);
  }

  private List<Message> wireMessages(com.github.skanga.ajent.domain.Thread thread) {
    return ConversationWire.forNormalTurn(
        thread, context.contextMax().getAsInt(), context.attachmentContent()::body);
  }

  private List<Message> activeWireMessages(AgentState state) {
    return state.compaction().active().isPresent()
        ? ConversationWire.forCompaction(state.thread(), context.contextMax().getAsInt(),
            context.attachmentContent()::body)
        : wireMessages(state.thread());
  }

  private static Duration clampRetryAfter(Duration value) {
    long seconds = Math.clamp(value.getSeconds(), 1, 600);
    return Duration.ofSeconds(seconds);
  }

  private AgentState startTool(AgentState state, StreamEvent.ToolUseStart start) {
    ToolUse call = new ToolUse(new ToolCallId(start.id()), new ToolName(start.name()), Map.of(),
        new ToolStatus.Pending(context.nanoClock().getAsLong()));
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
    long now = context.nanoClock().getAsLong();
    boolean previewDue = draft.lastPreviewNanos() == 0
        || now - draft.lastPreviewNanos() >= TOOL_PREVIEW_INTERVAL_NANOS;
    long lastPreview = previewDue ? Math.max(1, now) : draft.lastPreviewNanos();
    int bytes = partial.getBytes(StandardCharsets.UTF_8).length;
    boolean parseDue = previewDue && (draft.parseThroughBytes() == 0
        || bytes >= draft.parseThroughBytes() + TOOL_STRUCTURED_PARSE_GROWTH);
    int parseThrough = parseDue ? bytes : draft.parseThroughBytes();
    AgentState revised = copy(state, state.thread(), state.phase(), state.activeTurnId(),
        state.turnCounter(),
        state.tokensIn(), state.tokensOut(), state.status(),
        Optional.of(new AgentState.ToolDraft(
            draft.callId(), partial, lastPreview, parseThrough)), state.queued(),
        state.sessionGrants());
    if (!parseDue) return revised;
    Optional<ToolUse> call = findTool(revised, draft.callId());
    if (call.isEmpty() || !call.orElseThrow().name().value().equals("todo")) return revised;
    List<Map<String, Object>> todos = previewTodos(partial);
    if (todos.isEmpty() || todos.equals(call.orElseThrow().arguments().get("todos"))) {
      return revised;
    }
    return updateTool(revised, draft.callId(), current -> {
      var arguments = new HashMap<String, Object>(current.arguments());
      arguments.put("todos", todos);
      return new ToolUse(current.id(), current.name(), arguments, current.status());
    });
  }

  private static List<Map<String, Object>> previewTodos(String partialJson) {
    try {
      var root = JSON.readTree(PartialJson.close(partialJson));
      if (!root.isObject() || !root.path("todos").isArray() || root.path("todos").isEmpty()) {
        return List.of();
      }
      var result = new ArrayList<Map<String, Object>>();
      for (var item : root.path("todos")) {
        if (!item.isObject() || !item.path("content").isTextual()) continue;
        String status = item.path("status").isTextual()
            ? item.path("status").textValue() : "pending";
        if (!status.equals("completed") && !status.equals("in_progress")) status = "pending";
        result.add(Map.of("content", item.path("content").textValue(), "status", status));
      }
      return List.copyOf(result);
    } catch (Exception exception) {
      return List.of();
    }
  }

  private AgentState endTool(AgentState state) {
    if (state.toolDraft().isEmpty()) return state;
    AgentState.ToolDraft draft = state.toolDraft().orElseThrow();
    if (PartialJson.endedInsideString(draft.partialJson()))
      return markTruncated(clearDraft(state), draft.callId());
    AgentState revised;
    try {
      String raw = draft.partialJson().isEmpty() ? "{}" : draft.partialJson();
      com.fasterxml.jackson.databind.JsonNode root;
      try {
        root = JSON.readTree(raw);
      } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
        root = JSON.readTree(PartialJson.close(raw));
      }
      if (!root.isObject()) throw new IllegalArgumentException("arguments must be an object");
      Map<String, Object> arguments = JSON.convertValue(root, ARGUMENTS);
      revised = updateTool(state, draft.callId(), call -> new ToolUse(call.id(), call.name(),
          arguments, call.status()));
    } catch (Exception exception) {
      revised = updateTool(state, draft.callId(), call -> new ToolUse(call.id(), call.name(),
          call.arguments(), failed(call, "invalid tool arguments: " + exception.getMessage())));
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

  private static AgentState withLastTick(AgentState state, long lastTickNanos) {
    return new AgentState(state.thread(), state.phase(), state.activeTurnId(), state.turnCounter(),
        state.tokensIn(), state.tokensOut(), lastTickNanos, state.status(), state.toolDraft(),
        state.queued(), state.compaction(), state.oauthRefreshInFlight(),
        state.truncatedToolIds(), state.sessionGrants());
  }

  private static AgentState withOAuthRefreshInFlight(AgentState state, boolean inFlight) {
    return new AgentState(state.thread(), state.phase(), state.activeTurnId(), state.turnCounter(),
        state.tokensIn(), state.tokensOut(), state.lastTickNanos(), state.status(),
        state.toolDraft(), state.queued(), state.compaction(), inFlight,
        state.truncatedToolIds(), state.sessionGrants());
  }

  private Message message(Role role, String text, List<ImageContent> images, List<ToolUse> calls,
                          Instant now) {
    return message(role, text, images, List.of(), calls, now, Optional.empty());
  }

  private Message message(Role role, String text, List<ImageContent> images,
                          List<Attachment> attachments,
                          List<ToolUse> calls, Instant now, Optional<CheckpointId> checkpoint) {
    MessageId id = checkpoint.<MessageId>map(value -> new MessageId(value.value()))
        .orElseGet(context.messageIds());
    return new Message(id, role, text, images, attachments, "", "", calls,
        now, checkpoint, Optional.empty(), false, false);
  }

  private static Message withText(Message message, String text) {
    return new Message(message.id(), message.role(), text, message.images(), message.attachments(),
        message.thinking(), message.thinkingSignature(), message.toolCalls(), message.timestamp(),
        message.checkpointId(), message.error(), message.textBlockClosed(),
        message.isCompactSummary());
  }

  private static Message withError(Message message, String error) {
    return new Message(message.id(), message.role(), message.text(), message.images(),
        message.attachments(), message.thinking(), message.thinkingSignature(),
        message.toolCalls(), message.timestamp(), message.checkpointId(), Optional.of(error),
        message.textBlockClosed(), message.isCompactSummary());
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
    return new AgentState(thread, phase, activeTurnId, turnCounter, tokensIn, tokensOut,
        state.lastTickNanos(), status, draft, queued, state.compaction(),
        state.oauthRefreshInFlight(), state.truncatedToolIds(), grants);
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
