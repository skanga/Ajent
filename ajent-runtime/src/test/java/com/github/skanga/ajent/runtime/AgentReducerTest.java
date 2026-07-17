package com.github.skanga.ajent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.MessageId;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.SessionPhase;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.CompactionRecord;
import com.github.skanga.ajent.domain.RetryState;
import com.github.skanga.ajent.provider.ErrorClass;
import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class AgentReducerTest {
  @Test void submitStartsADeterministicTurnAndDescribesProviderAndPersistenceEffects() {
    AgentReducer.Step step = reducer(PermissionVerdict.ALLOW).update(AgentState.initial(thread()),
        new RuntimeMessage.Submit("hello", List.of()));

    assertThat(step.state().phase()).isInstanceOf(SessionPhase.Streaming.class);
    assertThat(step.state().activeTurnId()).isEqualTo(1);
    assertThat(step.state().thread().messages()).extracting(message -> message.role())
        .containsExactly(Role.USER, Role.ASSISTANT);
    assertThat(step.state().thread().messages().getFirst().text()).isEqualTo("hello");
    assertThat(step.effects()).satisfiesExactly(
        effect -> assertThat(effect).isInstanceOf(RuntimeEffect.Persist.class),
        effect -> assertThat(effect).isInstanceOf(RuntimeEffect.StartStream.class));
  }

  @Test void providerEffectsUseCompactedWireViewWithoutMutatingVisibleHistory() {
    Instant now = Instant.parse("2026-07-17T00:00:00Z");
    var raw = List.of(new com.github.skanga.ajent.domain.Message(new MessageId("old-user"),
        Role.USER, "old request", List.of(), List.of(), "", "", List.of(), now,
        Optional.empty(), Optional.empty(), false),
        new com.github.skanga.ajent.domain.Message(new MessageId("old-assistant"), Role.ASSISTANT,
            "old answer", List.of(), List.of(), "", "", List.of(), now, Optional.empty(),
            Optional.empty(), false));
    Thread compacted = new Thread(new ThreadId("thread"), "Test", raw, now, now,
        List.of(new CompactionRecord(2, "preserved state", now)));

    AgentReducer.Step step = reducer(PermissionVerdict.ALLOW).update(AgentState.initial(compacted),
        new RuntimeMessage.Submit("continue", List.of()));

    RuntimeEffect.StartStream stream = (RuntimeEffect.StartStream) step.effects().getLast();
    assertThat(stream.messages().getFirst().isCompactSummary()).isTrue();
    assertThat(stream.messages().getFirst().text()).contains("preserved state");
    assertThat(stream.messages()).extracting(message -> message.text())
        .containsSequence("continue", "");
    assertThat(step.state().thread().messages()).hasSize(4)
        .startsWith(raw.toArray(com.github.skanga.ajent.domain.Message[]::new));
  }

  @Test void manualCompactionIsIdleOnlyAndStreamsIntoAnOffTranscriptBuffer() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState empty = AgentState.initial(thread());
    assertThat(reducer.update(empty, new RuntimeMessage.CompactContext()).state()).isEqualTo(empty);
    AgentState busy = submit(reducer);
    assertThat(reducer.update(busy, new RuntimeMessage.CompactContext()).state()).isEqualTo(busy);

    AgentState withHistory = reducer.update(busy, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.END_TURN))).state();
    AgentReducer.Step started = reducer.update(withHistory, new RuntimeMessage.CompactContext());
    assertThat(started.state().phase()).isInstanceOf(SessionPhase.Streaming.class);
    assertThat(started.state().compaction().active()).isPresent();
    assertThat(started.state().compaction().active().orElseThrow().targetIndex()).isEqualTo(2);
    assertThat(started.state().status()).isEqualTo("compacting context…");
    RuntimeEffect.StartStream stream = (RuntimeEffect.StartStream) started.effects().getFirst();
    assertThat(stream.messages().getLast().text())
        .isEqualTo(ConversationWire.COMPACTION_SUMMARY_PROMPT);

    AgentState buffered = event(reducer, started.state(), started.state().activeTurnId(),
        new StreamEvent.TextDelta("  concise summary  "));
    assertThat(buffered.thread()).isEqualTo(withHistory.thread());
    assertThat(buffered.compaction().active().orElseThrow().buffer())
        .isEqualTo("  concise summary  ");
    AgentState usage = event(reducer, buffered, buffered.activeTurnId(),
        new StreamEvent.Usage(999, 888));
    assertThat(usage.tokensIn()).isEqualTo(withHistory.tokensIn());
    assertThat(usage.tokensOut()).isEqualTo(withHistory.tokensOut());
  }

  @Test void compactionFinishPersistsRecordWithoutDeletingTranscriptAndDrainsQueue() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = submit(reducer);
    state = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.END_TURN))).state();
    state = reducer.update(state, new RuntimeMessage.CompactContext()).state();
    long compactTurn = state.activeTurnId();
    state = reducer.update(state, new RuntimeMessage.Submit("queued work", List.of())).state();
    state = event(reducer, state, compactTurn, new StreamEvent.TextDelta("  state summary  "));
    AgentReducer.Step finished = reducer.update(state, new RuntimeMessage.ProviderEvent(compactTurn,
        new StreamEvent.Finished(StopReason.END_TURN)));

    assertThat(finished.state().thread().compactions()).singleElement().satisfies(record -> {
      assertThat(record.upToIndex()).isEqualTo(2);
      assertThat(record.summary()).isEqualTo("state summary");
    });
    assertThat(finished.state().thread().messages()).extracting(message -> message.text())
        .containsExactly("hello", "", "queued work", "");
    assertThat(finished.state().phase()).isInstanceOf(SessionPhase.Streaming.class);
    assertThat(finished.state().compaction().active()).isEmpty();
    assertThat(finished.effects()).anyMatch(RuntimeEffect.Persist.class::isInstance)
        .anyMatch(RuntimeEffect.StartStream.class::isInstance);
    RuntimeEffect.StartStream queued = (RuntimeEffect.StartStream) finished.effects().getLast();
    assertThat(queued.messages().getFirst().isCompactSummary()).isTrue();
    assertThat(queued.messages().getFirst().text()).contains("state summary");
  }

  @Test void emptyCompactionSummaryGetsNativeFallbackText() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = submit(reducer);
    state = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.END_TURN))).state();
    state = reducer.update(state, new RuntimeMessage.CompactContext()).state();
    AgentReducer.Step finished = reducer.update(state, new RuntimeMessage.ProviderEvent(
        state.activeTurnId(), new StreamEvent.Finished(StopReason.END_TURN)));
    assertThat(finished.state().thread().compactions().getLast().summary())
        .isEqualTo("[compaction produced no text]");
  }

  @Test void cancellingCompactionPreservesHistoryAndImmediatelyDrainsQueuedWork() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = submit(reducer);
    state = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.END_TURN))).state();
    Thread before = state.thread();
    state = reducer.update(state, new RuntimeMessage.CompactContext()).state();
    state = reducer.update(state, new RuntimeMessage.Submit("after cancel", List.of())).state();
    AgentReducer.Step cancelled = reducer.update(state, new RuntimeMessage.Cancel());
    assertThat(cancelled.state().thread().compactions()).isEmpty();
    assertThat(cancelled.state().thread().messages()).startsWith(
        before.messages().toArray(com.github.skanga.ajent.domain.Message[]::new));
    assertThat(cancelled.state().thread().messages().get(2).text()).isEqualTo("after cancel");
    assertThat(cancelled.state().phase()).isInstanceOf(SessionPhase.Streaming.class);
    assertThat(cancelled.effects()).anyMatch(RuntimeEffect.StartStream.class::isInstance);
  }

  @Test void transientCompactionFailureClearsOnlyBufferAndRetriesTheSameSummaryRequest() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = submit(reducer);
    state = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.END_TURN))).state();
    state = reducer.update(state, new RuntimeMessage.CompactContext()).state();
    long turn = state.activeTurnId();
    state = event(reducer, state, turn, new StreamEvent.TextDelta("discard me"));
    AgentReducer.Step failed = reducer.update(state, new RuntimeMessage.ProviderEvent(turn,
        new StreamEvent.Error("connection reset", Optional.empty(), ErrorClass.TRANSIENT, false)));
    assertThat(failed.state().compaction().active().orElseThrow().buffer()).isEmpty();
    assertThat(failed.state().phase().active().orElseThrow().transientRetries()).isEqualTo(1);
    assertThat(failed.state().status()).startsWith("compacting — retrying in 1s");
    assertThat(failed.effects()).singleElement().isEqualTo(new RuntimeEffect.Schedule(
        Duration.ofMillis(500), new RuntimeMessage.RetryStream(turn)));

    AgentReducer.Step retry = reducer.update(failed.state(), new RuntimeMessage.RetryStream(turn));
    RuntimeEffect.StartStream stream = (RuntimeEffect.StartStream) retry.effects().getFirst();
    assertThat(stream.turnId()).isEqualTo(turn);
    assertThat(stream.messages().getLast().text())
        .isEqualTo(ConversationWire.COMPACTION_SUMMARY_PROMPT);
  }

  @Test void terminalCompactionFailureKeepsTranscriptAndDrainsQueuedWork() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = submit(reducer);
    state = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.END_TURN))).state();
    Thread before = state.thread();
    state = reducer.update(state, new RuntimeMessage.CompactContext()).state();
    long turn = state.activeTurnId();
    state = reducer.update(state, new RuntimeMessage.Submit("continue anyway", List.of())).state();
    AgentReducer.Step failed = reducer.update(state, new RuntimeMessage.ProviderEvent(turn,
        new StreamEvent.Error("model not found", Optional.empty(), ErrorClass.TERMINAL, false)));
    assertThat(failed.state().compaction().active()).isEmpty();
    assertThat(failed.state().thread().compactions()).isEmpty();
    assertThat(failed.state().thread().messages()).startsWith(
        before.messages().toArray(com.github.skanga.ajent.domain.Message[]::new));
    assertThat(failed.state().thread().messages().get(2).text()).isEqualTo("continue anyway");
    assertThat(failed.state().phase()).isInstanceOf(SessionPhase.Streaming.class);
    assertThat(failed.effects()).anyMatch(RuntimeEffect.StartStream.class::isInstance);
  }

  @Test void postTurnThresholdAutoStartsTheSameCompactionPath() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW, () -> 1_000L, 100);
    AgentState state = submit(reducer);
    AgentReducer.Step finished = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.END_TURN)));
    assertThat(finished.state().compaction().active()).isPresent();
    assertThat(finished.state().status()).isEqualTo("compacting context…");
    assertThat(finished.effects()).satisfiesExactly(
        effect -> assertThat(effect).isInstanceOf(RuntimeEffect.Persist.class),
        effect -> assertThat(effect).isInstanceOf(RuntimeEffect.StartStream.class));
    RuntimeEffect.StartStream compact = (RuntimeEffect.StartStream) finished.effects().getLast();
    assertThat(compact.messages().getLast().text())
        .isEqualTo(ConversationWire.COMPACTION_SUMMARY_PROMPT);
  }

  @Test void rapidRefillBreakerDisablesAutoCompactionThenQuietTurnsReenableIt() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = submit(reducer);
    state = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.END_TURN))).state();
    for (int compact = 0; compact < 3; compact++) {
      state = reducer.update(state, new RuntimeMessage.CompactContext()).state();
      long turn = state.activeTurnId();
      state = event(reducer, state, turn, new StreamEvent.TextDelta("summary " + compact));
      state = reducer.update(state, new RuntimeMessage.ProviderEvent(turn,
          new StreamEvent.Finished(StopReason.END_TURN))).state();
    }
    assertThat(state.compaction().autoDisabled()).isTrue();
    assertThat(state.status()).contains("auto-compact disabled");

    for (int quiet = 0; quiet < 11; quiet++) {
      AgentReducer.Step submitted = reducer.update(state,
          new RuntimeMessage.Submit("quiet " + quiet, List.of()));
      state = reducer.update(submitted.state(), new RuntimeMessage.ProviderEvent(
          submitted.state().activeTurnId(), new StreamEvent.Finished(StopReason.END_TURN))).state();
    }
    assertThat(state.compaction().autoDisabled()).isFalse();
    assertThat(state.compaction().recentCompacts()).isZero();
  }

  @Test void streamsTextUsageAndNaturalFinishThenIgnoresStaleEvents() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = submit(reducer);
    state = event(reducer, state, 1, new StreamEvent.TextDelta("answer "));
    state = event(reducer, state, 1, new StreamEvent.TextDelta("complete"));
    state = event(reducer, state, 1, new StreamEvent.Usage(120, 7));
    AgentReducer.Step finished = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.END_TURN)));

    assertThat(finished.state().phase()).isInstanceOf(SessionPhase.Idle.class);
    assertThat(finished.state().tokensIn()).isEqualTo(120);
    assertThat(finished.state().tokensOut()).isEqualTo(7);
    assertThat(finished.state().thread().messages().getLast().text()).isEqualTo("answer complete");
    assertThat(finished.effects()).singleElement().isInstanceOf(RuntimeEffect.Persist.class);
    assertThat(reducer.update(finished.state(), new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.TextDelta("late"))).state()).isEqualTo(finished.state());
  }

  @Test void completeToolCallExecutesThenContinuesTheSameAgentTurn() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = submit(reducer);
    state = event(reducer, state, 1, new StreamEvent.ToolUseStart("call-1", "read"));
    state = event(reducer, state, 1, new StreamEvent.ToolUseDelta("{\"path\":\"README.md\"}"));
    state = event(reducer, state, 1, new StreamEvent.ToolUseEnd());
    AgentReducer.Step finalized = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.TOOL_USE)));

    assertThat(finalized.state().phase()).isInstanceOf(SessionPhase.ExecutingTool.class);
    assertThat(finalized.state().thread().messages().getLast().toolCalls().getFirst().status())
        .isInstanceOf(ToolStatus.Running.class);
    RuntimeEffect.ExecuteTool execute = (RuntimeEffect.ExecuteTool) finalized.effects().getFirst();
    assertThat(execute.call().arguments()).containsEntry("path", "README.md");

    AgentReducer.Step completed = reducer.update(finalized.state(), new RuntimeMessage.ToolCompleted(
        1, "call-1", new ToolCompletion.Success("file contents")));
    assertThat(completed.state().phase()).isInstanceOf(SessionPhase.Streaming.class);
    assertThat(completed.state().activeTurnId()).isEqualTo(2);
    assertThat(completed.state().thread().messages().get(1).toolCalls().getFirst().status())
        .isEqualTo(new ToolStatus.Done("file contents"));
    assertThat(completed.state().thread().messages().getLast().role()).isEqualTo(Role.ASSISTANT);
    assertThat(completed.effects()).anyMatch(RuntimeEffect.StartStream.class::isInstance);
  }

  @Test void permissionApprovalAndRejectionDriveTypedPhaseTransitions() {
    AgentReducer prompting = reducer(PermissionVerdict.PROMPT);
    AgentState state = toolFinished(prompting);
    assertThat(state.phase()).isInstanceOf(SessionPhase.AwaitingPermission.class);

    AgentReducer.Step approved = prompting.update(state,
        new RuntimeMessage.PermissionResolved("call-1", true, false));
    assertThat(approved.state().phase()).isInstanceOf(SessionPhase.ExecutingTool.class);
    assertThat(approved.effects()).singleElement().isInstanceOf(RuntimeEffect.ExecuteTool.class);

    AgentReducer.Step rejected = prompting.update(state,
        new RuntimeMessage.PermissionResolved("call-1", false, false));
    assertThat(rejected.state().thread().messages().get(1).toolCalls().getFirst().status())
        .isInstanceOf(ToolStatus.Rejected.class);
    assertThat(rejected.state().phase()).isInstanceOf(SessionPhase.Streaming.class);
    assertThat(rejected.effects()).anyMatch(RuntimeEffect.StartStream.class::isInstance);
  }

  @Test void cancelIsIdempotentAndSettlesEveryUnfinishedTool() {
    AgentReducer reducer = reducer(PermissionVerdict.PROMPT);
    AgentState state = toolFinished(reducer);
    AgentReducer.Step cancelled = reducer.update(state, new RuntimeMessage.Cancel());
    assertThat(cancelled.state().phase()).isInstanceOf(SessionPhase.Idle.class);
    assertThat(cancelled.state().status()).isEqualTo("cancelled");
    assertThat(cancelled.state().thread().messages().get(1).toolCalls().getFirst().status())
        .isInstanceOf(ToolStatus.Rejected.class);
    assertThat(cancelled.effects()).singleElement().isInstanceOf(RuntimeEffect.Persist.class);
    assertThat(reducer.update(cancelled.state(), new RuntimeMessage.Cancel()).state())
        .isEqualTo(cancelled.state());
  }

  @Test void malformedToolArgumentsBecomeAFailedResultAndStillContinue() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = submit(reducer);
    state = event(reducer, state, 1, new StreamEvent.ToolUseStart("bad", "read"));
    state = event(reducer, state, 1, new StreamEvent.ToolUseDelta("{not-json"));
    state = event(reducer, state, 1, new StreamEvent.ToolUseEnd());
    AgentReducer.Step step = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.TOOL_USE)));
    assertThat(step.state().thread().messages().get(1).toolCalls().getFirst().status())
        .isInstanceOfSatisfying(ToolStatus.Failed.class,
            failed -> assertThat(failed.output()).contains("invalid tool arguments"));
    assertThat(step.state().phase()).isInstanceOf(SessionPhase.Streaming.class);
  }

  @Test void activeSubmissionsQueueAndEmptyIdleSubmissionsAreNoOps() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState initial = AgentState.initial(thread());
    assertThat(reducer.update(initial, new RuntimeMessage.Submit("", List.of())).state())
        .isEqualTo(initial);
    AgentState active = submit(reducer);
    AgentReducer.Step queued = reducer.update(active,
        new RuntimeMessage.Submit("next request", List.of()));
    assertThat(queued.state().queued()).extracting(RuntimeMessage.Submit::text)
        .containsExactly("next request");
    assertThat(queued.effects()).isEmpty();
  }

  @Test void providerErrorsSettleTheTurnAndToolFailuresContinueIt() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentReducer.Step errored = reducer.update(submit(reducer),
        new RuntimeMessage.ProviderEvent(1, new StreamEvent.Error("offline")));
    assertThat(errored.state().phase()).isInstanceOf(SessionPhase.Idle.class);
    assertThat(errored.state().status()).isEqualTo("error: offline");

    AgentState executing = toolFinished(reducer(PermissionVerdict.ALLOW));
    AgentReducer.Step failed = reducer(PermissionVerdict.ALLOW).update(executing,
        new RuntimeMessage.ToolCompleted(1, "call-1", new ToolCompletion.Failure("read failed")));
    assertThat(failed.state().thread().messages().get(1).toolCalls().getFirst().status())
        .isEqualTo(new ToolStatus.Failed("read failed"));
    assertThat(failed.state().phase()).isInstanceOf(SessionPhase.Streaming.class);
    assertThat(reducer(PermissionVerdict.ALLOW).update(failed.state(),
        new RuntimeMessage.ToolCompleted(1, "call-1", new ToolCompletion.Success("late"))).state())
        .isEqualTo(failed.state());
  }

  @Test void transientFailureSchedulesAgenTTYBackoffAndDedupeThenRetriesSameTurn() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState active = submit(reducer);
    AgentReducer.Step failed = reducer.update(active, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Error("connection reset", Optional.empty(), ErrorClass.TRANSIENT, false)));

    assertThat(failed.state().phase().active().orElseThrow().retryState())
        .isInstanceOf(RetryState.Scheduled.class);
    assertThat(failed.state().phase().active().orElseThrow().transientRetries()).isEqualTo(1);
    assertThat(failed.effects()).singleElement().isEqualTo(new RuntimeEffect.Schedule(
        Duration.ofMillis(500), new RuntimeMessage.RetryStream(1)));
    assertThat(reducer.update(failed.state(), new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Error("late cancelled"))).effects()).isEmpty();

    AgentReducer.Step retry = reducer.update(failed.state(), new RuntimeMessage.RetryStream(1));
    assertThat(retry.state().activeTurnId()).isEqualTo(1);
    assertThat(retry.state().phase().active().orElseThrow().retryState())
        .isInstanceOf(RetryState.Fresh.class);
    assertThat(retry.effects()).singleElement().isInstanceOf(RuntimeEffect.StartStream.class);
  }

  @Test void tickTripsTheNativeStreamStallWatchdogExactlyOnce() {
    var clock = new java.util.concurrent.atomic.AtomicLong(1_000L);
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW, clock::get);
    AgentState state = submit(reducer);

    AgentReducer.Step firstTick = reducer.update(state, new RuntimeMessage.Tick());
    assertThat(firstTick.state().lastTickNanos()).isEqualTo(1_000L);
    assertThat(firstTick.effects()).isEmpty();

    state = firstTick.state();
    for (int second = 1; second < 120; second++) {
      clock.set(1_000L + Duration.ofSeconds(second).toNanos());
      AgentReducer.Step healthy = reducer.update(state, new RuntimeMessage.Tick());
      assertThat(healthy.effects()).isEmpty();
      state = healthy.state();
    }
    clock.set(1_000L + Duration.ofSeconds(120).toNanos());
    AgentReducer.Step stalled = reducer.update(state, new RuntimeMessage.Tick());

    assertThat(stalled.state().phase().active().orElseThrow().retryState())
        .isInstanceOf(RetryState.StallFired.class);
    assertThat(stalled.state().phase().active().orElseThrow().cancellation().isCancelled())
        .isTrue();
    assertThat(stalled.effects()).singleElement().isEqualTo(new RuntimeEffect.Schedule(
        Duration.ZERO, new RuntimeMessage.ProviderEvent(1,
            new StreamEvent.Error("stream stalled — no events for 120s", Optional.empty(),
                ErrorClass.TRANSIENT, true))));

    clock.incrementAndGet();
    assertThat(reducer.update(stalled.state(), new RuntimeMessage.Tick()).effects()).isEmpty();
  }

  @Test void longTickGapRebasesActivityAndWatchdogOnlyCoversFreshStreaming() {
    var clock = new java.util.concurrent.atomic.AtomicLong(1_000L);
    AgentReducer reducer = reducer(PermissionVerdict.PROMPT, clock::get);
    AgentState state = reducer.update(submit(reducer), new RuntimeMessage.Tick()).state();

    clock.set(1_000L + Duration.ofSeconds(121).toNanos());
    AgentState rebased = reducer.update(state, new RuntimeMessage.Tick()).state();
    assertThat(rebased.phase().active().orElseThrow().retryState())
        .isInstanceOf(RetryState.Fresh.class);
    assertThat(rebased.phase().active().orElseThrow().lastEventNanos())
        .isEqualTo(1_000L + Duration.ofSeconds(121).toNanos());

    AgentState awaiting = event(reducer, rebased, 1,
        new StreamEvent.ToolUseStart("permission", "write"));
    awaiting = event(reducer, awaiting, 1,
        new StreamEvent.ToolUseDelta("{\"path\":\"x\",\"content\":\"y\"}"));
    awaiting = event(reducer, awaiting, 1, new StreamEvent.ToolUseEnd());
    awaiting = event(reducer, awaiting, 1, new StreamEvent.Finished(StopReason.TOOL_USE));
    assertThat(awaiting.phase()).isInstanceOf(SessionPhase.AwaitingPermission.class);
    clock.addAndGet(Duration.ofSeconds(120).toNanos());
    assertThat(reducer.update(awaiting, new RuntimeMessage.Tick()).state().phase())
        .isInstanceOf(SessionPhase.AwaitingPermission.class);
  }

  @Test void heartbeatAndFirstDeltaResetTransientBudgetButNotMidStreamFailures() {
    var clock = new java.util.concurrent.atomic.AtomicLong(1_000L);
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW, clock::get);
    AgentState state = submit(reducer);
    state = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Error("offline", Optional.empty(), ErrorClass.TRANSIENT, false))).state();
    state = reducer.update(state, new RuntimeMessage.RetryStream(1)).state();
    clock.set(2_000L);
    state = event(reducer, state, 1, new StreamEvent.Heartbeat());
    assertThat(state.phase().active().orElseThrow().transientRetries()).isZero();

    state = event(reducer, state, 1, new StreamEvent.ToolUseStart("partial", "write"));
    state = event(reducer, state, 1, new StreamEvent.ToolUseDelta("{\"path\":"));
    state = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Error("timeout", Optional.empty(), ErrorClass.TRANSIENT, false))).state();
    assertThat(state.phase().active().orElseThrow().midStreamFailures()).isEqualTo(1);
    state = reducer.update(state, new RuntimeMessage.RetryStream(1)).state();
    state = event(reducer, state, 1, new StreamEvent.Heartbeat());
    assertThat(state.phase().active().orElseThrow().transientRetries()).isZero();
    assertThat(state.phase().active().orElseThrow().midStreamFailures()).isEqualTo(1);
  }

  @Test void retryAfterIsClampedAndCommittedOutputOrTerminalErrorsNeverRetry() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentReducer.Step bounded = reducer.update(submit(reducer),
        new RuntimeMessage.ProviderEvent(1, new StreamEvent.Error("rate limited",
            Optional.of(Duration.ofHours(1)), ErrorClass.RATE_LIMIT, false)));
    assertThat(bounded.effects()).singleElement().isEqualTo(new RuntimeEffect.Schedule(
        Duration.ofMinutes(10), new RuntimeMessage.RetryStream(1)));

    AgentState committed = event(reducer, submit(reducer), 1, new StreamEvent.TextDelta("visible"));
    AgentReducer.Step noDuplicate = reducer.update(committed, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Error("offline", Optional.empty(), ErrorClass.TRANSIENT, false)));
    assertThat(noDuplicate.state().phase()).isInstanceOf(SessionPhase.Idle.class);
    assertThat(noDuplicate.effects()).noneMatch(RuntimeEffect.Schedule.class::isInstance);

    AgentReducer.Step terminal = reducer.update(submit(reducer), new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Error("model not found", Optional.empty(), ErrorClass.TERMINAL, false)));
    assertThat(terminal.state().phase()).isInstanceOf(SessionPhase.Idle.class);
  }

  @Test void retryBudgetDecaysAfterNinetySecondsAndCancelInvalidatesScheduledRetry() {
    var clock = new java.util.concurrent.atomic.AtomicLong(1_000L);
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW, clock::get);
    AgentState state = submit(reducer);
    state = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Error("offline", Optional.empty(), ErrorClass.TRANSIENT, false))).state();
    state = reducer.update(state, new RuntimeMessage.RetryStream(1)).state();
    clock.set(1_000L + Duration.ofSeconds(91).toNanos());
    AgentReducer.Step decayed = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Error("offline", Optional.empty(), ErrorClass.TRANSIENT, false)));
    assertThat(decayed.state().phase().active().orElseThrow().transientRetries()).isEqualTo(1);

    AgentState cancelled = reducer.update(decayed.state(), new RuntimeMessage.Cancel()).state();
    AgentReducer.Step lateRetry = reducer.update(cancelled, new RuntimeMessage.RetryStream(1));
    assertThat(lateRetry.state()).isEqualTo(cancelled);
    assertThat(lateRetry.effects()).isEmpty();
  }

  @Test void naturalFinishDrainsQueuedTurnsInFifoOrder() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = submit(reducer);
    state = reducer.update(state, new RuntimeMessage.Submit("second", List.of())).state();
    state = reducer.update(state, new RuntimeMessage.Submit("third", List.of())).state();
    AgentReducer.Step drained = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.END_TURN)));
    assertThat(drained.state().phase()).isInstanceOf(SessionPhase.Streaming.class);
    assertThat(drained.state().thread().messages()).extracting(message -> message.text())
        .containsExactly("hello", "", "second", "");
    assertThat(drained.state().queued()).extracting(RuntimeMessage.Submit::text)
        .containsExactly("third");
    assertThat(drained.effects()).anyMatch(RuntimeEffect.StartStream.class::isInstance);
  }

  @Test void upstreamMidStringToolCutoffRetriesTwiceOnTheSameContext() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = unfinishedWrite(reducer, submit(reducer), 1);
    AgentReducer.Step first = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.END_TURN)));
    assertThat(first.state().activeTurnId()).isEqualTo(1);
    assertThat(first.state().phase().active().orElseThrow().truncationRetries()).isEqualTo(1);
    assertThat(first.state().status()).isEqualTo("retrying (upstream cut off)…");
    assertThat(first.effects()).singleElement().isInstanceOf(RuntimeEffect.StartStream.class);

    state = unfinishedWrite(reducer, first.state(), 1);
    AgentReducer.Step second = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.END_TURN)));
    assertThat(second.state().phase().active().orElseThrow().truncationRetries()).isEqualTo(2);
    assertThat(second.effects()).singleElement().isInstanceOf(RuntimeEffect.StartStream.class);
  }

  @Test void exhaustedTruncationBudgetFailsTheCallAndContinuesForModelRecovery() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = submit(reducer);
    for (int attempt = 0; attempt < 2; attempt++) {
      state = unfinishedWrite(reducer, state, 1);
      state = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
          new StreamEvent.Finished(StopReason.END_TURN))).state();
    }
    state = unfinishedWrite(reducer, state, 1);
    AgentReducer.Step exhausted = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.END_TURN)));
    assertThat(exhausted.state().phase()).isInstanceOf(SessionPhase.Streaming.class);
    assertThat(exhausted.state().activeTurnId()).isEqualTo(2);
    assertThat(exhausted.state().thread().messages().get(1).toolCalls().getFirst().status())
        .isInstanceOfSatisfying(ToolStatus.Failed.class, failed ->
            assertThat(failed.output()).contains("truncated mid-string", "full payload"));
  }

  @Test void maxTokensNeverRetriesAndExplainsHowToSplitTheToolPayload() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = unfinishedWrite(reducer, submit(reducer), 1);
    AgentReducer.Step capped = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.MAX_TOKENS)));
    assertThat(capped.state().activeTurnId()).isEqualTo(2);
    assertThat(capped.state().thread().messages().get(1).toolCalls().getFirst().status())
        .isInstanceOfSatisfying(ToolStatus.Failed.class, failed ->
            assertThat(failed.output()).contains("max_tokens", "prefer `edit` over `write`"));
  }

  @Test void safelyClosesNonStringPartialJsonBeforeDispatchingTheTool() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = submit(reducer);
    state = event(reducer, state, 1, new StreamEvent.ToolUseStart("partial", "read"));
    state = event(reducer, state, 1, new StreamEvent.ToolUseDelta("{\"path\":\"README.md\""));
    AgentReducer.Step finalized = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.TOOL_USE)));
    assertThat(finalized.effects()).singleElement().isInstanceOfSatisfying(
        RuntimeEffect.ExecuteTool.class,
        effect -> assertThat(effect.call().arguments()).containsEntry("path", "README.md"));
  }

  @Test void toolBlockCloseRetainsMidStringTruncationUntilTurnFinish() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = unfinishedWrite(reducer, submit(reducer), 1);
    state = event(reducer, state, 1, new StreamEvent.ToolUseEnd());
    assertThat(state.thread().messages().getLast().toolCalls().getFirst().status())
        .isInstanceOf(ToolStatus.Pending.class);
    AgentReducer.Step finished = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.TOOL_USE)));
    assertThat(finished.state().phase().active().orElseThrow().truncationRetries()).isEqualTo(1);
    assertThat(finished.effects()).singleElement().isInstanceOf(RuntimeEffect.StartStream.class);
  }

  @Test void missingRequiredFieldAfterSafePartialCloseIsRetryEligible() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = submit(reducer);
    state = event(reducer, state, 1, new StreamEvent.ToolUseStart("missing", "write"));
    state = event(reducer, state, 1, new StreamEvent.ToolUseDelta("{\"path\":\"x\","));
    AgentReducer.Step finished = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.TOOL_USE)));
    assertThat(finished.state().phase().active().orElseThrow().truncationRetries()).isEqualTo(1);
    assertThat(finished.effects()).singleElement().isInstanceOf(RuntimeEffect.StartStream.class);
  }

  @Test void committedTextBlocksTruncationRetryAndMalformedNonStringArgsFailDirectly() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = submit(reducer);
    state = event(reducer, state, 1, new StreamEvent.TextDelta("committed"));
    state = unfinishedWrite(reducer, state, 1);
    AgentReducer.Step committed = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.END_TURN)));
    assertThat(committed.effects()).filteredOn(RuntimeEffect.StartStream.class::isInstance)
        .singleElement().isInstanceOfSatisfying(RuntimeEffect.StartStream.class,
            stream -> assertThat(stream.turnId()).isEqualTo(2));
    assertThat(committed.state().thread().messages().get(1).toolCalls().getFirst().status())
        .isInstanceOf(ToolStatus.Failed.class);

    state = submit(reducer);
    state = event(reducer, state, 1, new StreamEvent.ToolUseStart("bad-finish", "read"));
    state = event(reducer, state, 1, new StreamEvent.ToolUseDelta("{not-json"));
    AgentReducer.Step malformed = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.TOOL_USE)));
    assertThat(malformed.state().thread().messages().get(1).toolCalls().getFirst().status())
        .isInstanceOfSatisfying(ToolStatus.Failed.class,
            failed -> assertThat(failed.output()).contains("invalid tool arguments"));
  }

  @Test void maxTokensUpgradesAPendingToolEvenAfterItsBlockClosed() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = submit(reducer);
    state = event(reducer, state, 1, new StreamEvent.ToolUseStart("closed", "read"));
    state = event(reducer, state, 1, new StreamEvent.ToolUseDelta("{\"path\":\"x\"}"));
    state = event(reducer, state, 1, new StreamEvent.ToolUseEnd());
    AgentReducer.Step capped = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.MAX_TOKENS)));
    assertThat(capped.state().thread().messages().get(1).toolCalls().getFirst().status())
        .isInstanceOfSatisfying(ToolStatus.Failed.class,
            failed -> assertThat(failed.output()).contains("max_tokens"));
  }

  @Test void policyDenialBecomesAToolErrorWithoutExecuting() {
    AgentReducer reducer = reducer(PermissionVerdict.DENY);
    AgentState state = toolFinished(reducer);
    assertThat(state.phase()).isInstanceOf(SessionPhase.Streaming.class);
    assertThat(state.thread().messages().get(1).toolCalls().getFirst().status())
        .isEqualTo(new ToolStatus.Failed("Tool call denied by policy."));
  }

  @Test void alwaysApprovalPropagatesToLaterCallsOfTheSameTool() {
    AgentReducer reducer = reducer(PermissionVerdict.PROMPT);
    AgentState awaiting = toolFinished(reducer);
    AgentReducer.Step approved = reducer.update(awaiting,
        new RuntimeMessage.PermissionResolved("call-1", true, true));
    assertThat(approved.state().sessionGrants()).containsExactly("write");
    AgentState continued = reducer.update(approved.state(), new RuntimeMessage.ToolCompleted(
        1, "call-1", new ToolCompletion.Success("ok"))).state();
    long turn = continued.activeTurnId();
    continued = event(reducer, continued, turn, new StreamEvent.ToolUseStart("call-2", "write"));
    continued = event(reducer, continued, turn,
        new StreamEvent.ToolUseDelta("{\"path\":\"y\",\"content\":\"z\"}"));
    continued = event(reducer, continued, turn, new StreamEvent.ToolUseEnd());
    AgentReducer.Step second = reducer.update(continued, new RuntimeMessage.ProviderEvent(turn,
        new StreamEvent.Finished(StopReason.TOOL_USE)));
    assertThat(second.state().phase()).isInstanceOf(SessionPhase.ExecutingTool.class);
    assertThat(second.effects()).singleElement().isInstanceOf(RuntimeEffect.ExecuteTool.class);
  }

  @Test void multiToolBatchCanMoveFromExecutionBackToPermission() {
    AgentReducer reducer = reducer(call -> call.name().value().equals("read")
        ? PermissionVerdict.ALLOW : PermissionVerdict.PROMPT);
    AgentState state = submit(reducer);
    state = event(reducer, state, 1, new StreamEvent.ToolUseDelta("ignored-before-start"));
    state = event(reducer, state, 1, new StreamEvent.ToolUseEnd());
    state = event(reducer, state, 1, new StreamEvent.ToolUseStart("read-1", "read"));
    state = event(reducer, state, 1, new StreamEvent.ToolUseDelta("{\"path\":\"x\"}"));
    state = event(reducer, state, 1, new StreamEvent.ToolUseEnd());
    state = event(reducer, state, 1, new StreamEvent.ToolUseStart("write-1", "write"));
    state = event(reducer, state, 1,
        new StreamEvent.ToolUseDelta("{\"path\":\"x\",\"content\":\"y\"}"));
    state = event(reducer, state, 1, new StreamEvent.ToolUseEnd());
    AgentReducer.Step first = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.TOOL_USE)));
    assertThat(first.state().phase()).isInstanceOf(SessionPhase.ExecutingTool.class);
    AgentState executionEvent = event(reducer, first.state(), 1,
        new StreamEvent.TextDelta("late execution byte"));
    AgentReducer.Step second = reducer.update(executionEvent, new RuntimeMessage.ToolCompleted(
        1, "read-1", new ToolCompletion.Success("read")));
    assertThat(second.state().phase()).isInstanceOf(SessionPhase.AwaitingPermission.class);
    assertThat(second.effects()).singleElement().isInstanceOf(RuntimeEffect.RequestPermission.class);
    AgentState permissionEvent = event(reducer, second.state(), 1,
        new StreamEvent.TextDelta("late permission byte"));
    assertThat(permissionEvent.phase()).isInstanceOf(SessionPhase.AwaitingPermission.class);
    assertThat(reducer.update(permissionEvent,
        new RuntimeMessage.PermissionResolved("wrong", true, false)).state())
        .isEqualTo(permissionEvent);
  }

  @Test void nonObjectArgumentsFailAndPermissionMessagesOutsideThePromptAreIgnored() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = submit(reducer);
    assertThat(reducer.update(state,
        new RuntimeMessage.PermissionResolved("none", true, false)).state()).isEqualTo(state);
    state = event(reducer, state, 1, new StreamEvent.ToolUseStart("array", "read"));
    state = event(reducer, state, 1, new StreamEvent.ToolUseDelta("[1,2]"));
    state = event(reducer, state, 1, new StreamEvent.ToolUseEnd());
    AgentReducer.Step step = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.TOOL_USE)));
    assertThat(step.state().thread().messages().get(1).toolCalls().getFirst().status())
        .isInstanceOf(ToolStatus.Failed.class);
  }

  @Test void lateErrorsRepeatedFinalizationAndUnknownToolResultsStaySafe() {
    AgentReducer prompting = reducer(PermissionVerdict.PROMPT);
    AgentState awaiting = toolFinished(prompting);
    AgentReducer.Step repeated = prompting.update(awaiting, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.TOOL_USE)));
    assertThat(repeated.state().phase()).isInstanceOf(SessionPhase.AwaitingPermission.class);
    AgentReducer.Step errored = prompting.update(awaiting, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Error("disconnected while prompting")));
    assertThat(errored.state().phase()).isInstanceOf(SessionPhase.Idle.class);

    AgentReducer allowing = reducer(PermissionVerdict.ALLOW);
    AgentState executing = toolFinished(allowing);
    assertThat(allowing.update(executing, new RuntimeMessage.ToolCompleted(
        1, "unknown", new ToolCompletion.Success("ignored"))).state()).isEqualTo(executing);
  }

  @Test void oneFinishLaunchesACompatibleBatchInParallel() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = submit(reducer);
    state = event(reducer, state, 1, new StreamEvent.ToolUseStart("one", "read"));
    state = event(reducer, state, 1, new StreamEvent.ToolUseEnd());
    state = event(reducer, state, 1, new StreamEvent.ToolUseStart("two", "glob"));
    state = event(reducer, state, 1, new StreamEvent.ToolUseEnd());
    AgentReducer.Step sibling = reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.TOOL_USE)));
    assertThat(sibling.state().phase()).isInstanceOf(SessionPhase.ExecutingTool.class);
    assertThat(sibling.effects()).hasSize(2).allMatch(RuntimeEffect.ExecuteTool.class::isInstance);
  }

  @Test void streamingByteCapIsUtf8SafeAndDropsFurtherOrEmptyDeltas() {
    AgentReducer reducer = reducer(PermissionVerdict.ALLOW);
    AgentState state = submit(reducer);
    state = event(reducer, state, 1, new StreamEvent.TextDelta(""));
    state = event(reducer, state, 1, new StreamEvent.TextDelta("é".repeat(4_194_305)));
    String capped = state.thread().messages().getLast().text();
    assertThat(capped.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .hasSize(8 * 1024 * 1024);
    AgentState unchanged = event(reducer, state, 1, new StreamEvent.TextDelta("more"));
    assertThat(unchanged.thread().messages().getLast().text()).isEqualTo(capped);
  }

  private static AgentState toolFinished(AgentReducer reducer) {
    AgentState state = submit(reducer);
    state = event(reducer, state, 1, new StreamEvent.ToolUseStart("call-1", "write"));
    state = event(reducer, state, 1,
        new StreamEvent.ToolUseDelta("{\"path\":\"x\",\"content\":\"body\"}"));
    state = event(reducer, state, 1, new StreamEvent.ToolUseEnd());
    return reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.TOOL_USE))).state();
  }

  private static AgentState unfinishedWrite(AgentReducer reducer, AgentState state, long turn) {
    state = event(reducer, state, turn, new StreamEvent.ToolUseStart("cutoff", "write"));
    return event(reducer, state, turn,
        new StreamEvent.ToolUseDelta("{\"path\":\"x\",\"content\":\"half"));
  }

  private static AgentState submit(AgentReducer reducer) {
    return reducer.update(AgentState.initial(thread()),
        new RuntimeMessage.Submit("hello", List.of())).state();
  }

  private static AgentState event(AgentReducer reducer, AgentState state, long turn,
                                  StreamEvent event) {
    return reducer.update(state, new RuntimeMessage.ProviderEvent(turn, event)).state();
  }

  private static AgentReducer reducer(PermissionVerdict verdict) {
    return reducer(call -> verdict);
  }

  private static AgentReducer reducer(java.util.function.Function<
      com.github.skanga.ajent.domain.ToolUse, PermissionVerdict> permission) {
    return reducer(permission, () -> 1_000L);
  }

  private static AgentReducer reducer(PermissionVerdict verdict, java.util.function.LongSupplier clock) {
    return reducer(call -> verdict, clock, 200_000);
  }

  private static AgentReducer reducer(PermissionVerdict verdict,
                                      java.util.function.LongSupplier clock, int contextMax) {
    return reducer(call -> verdict, clock, contextMax);
  }

  private static AgentReducer reducer(java.util.function.Function<
      com.github.skanga.ajent.domain.ToolUse, PermissionVerdict> permission,
      java.util.function.LongSupplier clock) {
    return reducer(permission, clock, 200_000);
  }

  private static AgentReducer reducer(java.util.function.Function<
      com.github.skanga.ajent.domain.ToolUse, PermissionVerdict> permission,
      java.util.function.LongSupplier clock, int contextMax) {
    var ids = new AtomicInteger();
    return new AgentReducer(new AgentReducer.Context(clock,
        () -> Instant.parse("2026-07-17T00:00:00Z"),
        () -> new MessageId("m-" + ids.incrementAndGet()), permission, () -> 1.0,
        () -> contextMax));
  }

  private static Thread thread() {
    Instant now = Instant.parse("2026-07-17T00:00:00Z");
    return new Thread(new ThreadId("thread"), "Test", List.of(), now, now, List.of());
  }
}
