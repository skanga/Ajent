package com.github.skanga.ajent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.MessageId;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.SessionPhase;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import java.time.Instant;
import java.util.List;
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
    state = event(reducer, state, 1, new StreamEvent.ToolUseEnd());
    state = event(reducer, state, 1, new StreamEvent.ToolUseStart("write-1", "write"));
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
    state = event(reducer, state, 1, new StreamEvent.ToolUseDelta("{\"path\":\"x\"}"));
    state = event(reducer, state, 1, new StreamEvent.ToolUseEnd());
    return reducer.update(state, new RuntimeMessage.ProviderEvent(1,
        new StreamEvent.Finished(StopReason.TOOL_USE))).state();
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
    var ids = new AtomicInteger();
    return new AgentReducer(new AgentReducer.Context(() -> 1_000L,
        () -> Instant.parse("2026-07-17T00:00:00Z"),
        () -> new MessageId("m-" + ids.incrementAndGet()), permission));
  }

  private static Thread thread() {
    Instant now = Instant.parse("2026-07-17T00:00:00Z");
    return new Thread(new ThreadId("thread"), "Test", List.of(), now, now, List.of());
  }
}
