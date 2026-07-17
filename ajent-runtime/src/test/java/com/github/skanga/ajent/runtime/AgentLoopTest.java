package com.github.skanga.ajent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.github.skanga.ajent.domain.MessageId;
import com.github.skanga.ajent.domain.SessionPhase;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import com.github.skanga.ajent.provider.ErrorClass;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class AgentLoopTest {
  @Test void interpretsACompleteProviderTurnAndPersistsState() throws Exception {
    var idle = new CountDownLatch(1);
    var saves = new AtomicInteger();
    ProviderPort provider = (turn, messages, cancellation, sink) -> {
      sink.accept(new StreamEvent.TextDelta("final answer"));
      sink.accept(new StreamEvent.Finished(StopReason.END_TURN));
    };
    try (var loop = new AgentLoop(AgentState.initial(thread()), reducer(PermissionVerdict.ALLOW),
        provider, call -> new ToolCompletion.Success("unused"),
        call -> new PermissionPort.Decision(true, false), thread -> saves.incrementAndGet(),
        state -> { if (state.phase() instanceof SessionPhase.Idle
            && !state.thread().messages().isEmpty()) idle.countDown(); })) {
      loop.dispatch(new RuntimeMessage.Submit("question", List.of()));
      assertThat(idle.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(loop.state().thread().messages().getLast().text()).isEqualTo("final answer");
    }
    assertThat(saves.get()).isGreaterThanOrEqualTo(2);
  }

  @Test void executesPermissionedToolsAndFeedsTheirResultIntoAContinuation() throws Exception {
    var idle = new CountDownLatch(1);
    var providerCalls = new AtomicInteger();
    var toolCalls = new AtomicInteger();
    var permissionCalls = new AtomicInteger();
    ProviderPort provider = (turn, messages, cancellation, sink) -> {
      if (providerCalls.incrementAndGet() == 1) {
        sink.accept(new StreamEvent.ToolUseStart("call", "read"));
        sink.accept(new StreamEvent.ToolUseDelta("{\"path\":\"README.md\"}"));
        sink.accept(new StreamEvent.ToolUseEnd());
        sink.accept(new StreamEvent.Finished(StopReason.TOOL_USE));
      } else {
        sink.accept(new StreamEvent.TextDelta("used tool output"));
        sink.accept(new StreamEvent.Finished(StopReason.END_TURN));
      }
    };
    try (var loop = new AgentLoop(AgentState.initial(thread()), reducer(PermissionVerdict.PROMPT),
        provider, call -> {
          toolCalls.incrementAndGet();
          return new ToolCompletion.Success("contents");
        }, call -> {
          permissionCalls.incrementAndGet();
          return new PermissionPort.Decision(true, false);
        }, thread -> {}, state -> {
          if (state.phase() instanceof SessionPhase.Idle
              && state.thread().messages().size() >= 3) idle.countDown();
        })) {
      loop.dispatch(new RuntimeMessage.Submit("inspect", List.of()));
      assertThat(idle.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(loop.state().thread().messages().getLast().text()).isEqualTo("used tool output");
    }
    assertThat(providerCalls).hasValue(2);
    assertThat(toolCalls).hasValue(1);
    assertThat(permissionCalls).hasValue(1);
  }

  @Test void convertsPortExceptionsAndCloseCancelsAndRejectsFurtherDispatch() throws Exception {
    var idle = new CountDownLatch(1);
    var loop = new AgentLoop(AgentState.initial(thread()), reducer(PermissionVerdict.ALLOW),
        (turn, messages, cancellation, sink) -> { throw new IllegalStateException("boom"); },
        call -> { throw new IllegalStateException("tool boom"); },
        call -> { throw new IllegalStateException("permission boom"); },
        thread -> {}, state -> { if (state.phase() instanceof SessionPhase.Idle
            && state.status().startsWith("error:")) idle.countDown(); });
    loop.dispatch(new RuntimeMessage.Submit("question", List.of()));
    assertThat(idle.await(5, TimeUnit.SECONDS)).isTrue();
    loop.close();
    loop.close();
    assertThatIllegalStateException().isThrownBy(
        () -> loop.dispatch(new RuntimeMessage.Submit("late", List.of())));
  }

  @Test void interpretsScheduledRetryWithoutBlockingWorkers() throws Exception {
    var idle = new CountDownLatch(1);
    var providerCalls = new AtomicInteger();
    ProviderPort provider = (turn, messages, cancellation, sink) -> {
      if (providerCalls.incrementAndGet() == 1) {
        sink.accept(new StreamEvent.Error("connection reset", java.util.Optional.empty(),
            ErrorClass.TRANSIENT, false));
      } else {
        sink.accept(new StreamEvent.TextDelta("recovered"));
        sink.accept(new StreamEvent.Finished(StopReason.END_TURN));
      }
    };
    try (var loop = new AgentLoop(AgentState.initial(thread()), reducer(PermissionVerdict.ALLOW),
        provider, call -> new ToolCompletion.Success("unused"),
        call -> new PermissionPort.Decision(true, false), thread -> {},
        state -> { if (state.phase() instanceof SessionPhase.Idle
            && state.status().isEmpty()) idle.countDown(); })) {
      loop.dispatch(new RuntimeMessage.Submit("question", List.of()));
      assertThat(idle.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(loop.state().thread().messages().getLast().text()).isEqualTo("recovered");
    }
    assertThat(providerCalls).hasValue(2);
  }

  @Test void suppliesPeriodicTicksWhileATurnIsActive() throws Exception {
    var ticked = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    ProviderPort provider = (turn, messages, cancellation, sink) -> {
      try {
        finish.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException exception) {
        java.lang.Thread.currentThread().interrupt();
      }
      sink.accept(new StreamEvent.Finished(StopReason.END_TURN));
    };
    try (var loop = new AgentLoop(AgentState.initial(thread()), reducer(PermissionVerdict.ALLOW),
        provider, call -> new ToolCompletion.Success("unused"),
        call -> new PermissionPort.Decision(true, false), thread -> {},
        state -> {
          if (!(state.phase() instanceof SessionPhase.Idle) && state.lastTickNanos() != 0)
            ticked.countDown();
        })) {
      loop.dispatch(new RuntimeMessage.Submit("wait", List.of()));
      assertThat(ticked.await(2, TimeUnit.SECONDS)).isTrue();
      finish.countDown();
    }
  }

  private static AgentReducer reducer(PermissionVerdict verdict) {
    var ids = new AtomicInteger();
    return new AgentReducer(new AgentReducer.Context(System::nanoTime,
        () -> Instant.parse("2026-07-17T00:00:00Z"),
        () -> new MessageId("loop-" + ids.incrementAndGet()), call -> verdict));
  }

  private static Thread thread() {
    Instant now = Instant.parse("2026-07-17T00:00:00Z");
    return new Thread(new ThreadId("loop"), "Loop", List.of(), now, now, List.of());
  }
}
