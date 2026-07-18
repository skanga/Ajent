package com.github.skanga.ajent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.github.skanga.ajent.domain.MessageId;
import com.github.skanga.ajent.domain.CheckpointId;
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

  @Test void transitionObserverReceivesTheCausalMessageWithItsReducedState() throws Exception {
    var finished = new CountDownLatch(1);
    var observedReason = new java.util.concurrent.atomic.AtomicReference<StopReason>();
    ProviderPort provider = (turn, messages, cancellation, sink) ->
        sink.accept(new StreamEvent.Finished(StopReason.MAX_TOKENS));

    try (var loop = new AgentLoop(AgentState.initial(thread()),
        reducer(PermissionVerdict.ALLOW), provider,
        call -> new ToolCompletion.Success("unused"),
        call -> new PermissionPort.Decision(true, false), thread -> {},
        (message, state) -> {
          if (message instanceof RuntimeMessage.ProviderEvent(
              long ignored, StreamEvent.Finished event)
              && state.phase() instanceof SessionPhase.Idle) {
            observedReason.set(event.stopReason());
            finished.countDown();
          }
        })) {
      loop.dispatch(new RuntimeMessage.Submit("question", List.of()));
      assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(observedReason.get()).isEqualTo(StopReason.MAX_TOKENS);
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

  @Test void closeDrainsPersistenceEffectsBeforeClosingTheirPort() throws Exception {
    var saveStarted = new CountDownLatch(1);
    var releaseSave = new CountDownLatch(1);
    var persistenceClosed = new CountDownLatch(1);
    var closeFinished = new CountDownLatch(1);
    PersistencePort persistence = new PersistencePort() {
      @Override public void save(Thread ignored) {
        saveStarted.countDown();
        await(releaseSave);
      }

      @Override public void close() {
        persistenceClosed.countDown();
      }
    };
    var loop = new AgentLoop(AgentState.initial(thread()), reducer(PermissionVerdict.ALLOW),
        (turn, messages, cancellation, sink) -> {},
        call -> new ToolCompletion.Success("unused"),
        call -> new PermissionPort.Decision(true, false), persistence, state -> {});

    loop.dispatch(new RuntimeMessage.Submit("persist me", List.of()));
    assertThat(saveStarted.await(5, TimeUnit.SECONDS)).isTrue();
    java.lang.Thread.startVirtualThread(() -> {
      loop.close();
      closeFinished.countDown();
    });
    assertThat(persistenceClosed.await(100, TimeUnit.MILLISECONDS)).isFalse();

    releaseSave.countDown();
    assertThat(closeFinished.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(persistenceClosed.getCount()).isZero();
  }

  @Test void closeWaitsForAnActiveDispatchToScheduleItsPersistenceEffect() throws Exception {
    var observerEntered = new CountDownLatch(1);
    var releaseObserver = new CountDownLatch(1);
    var closeFinished = new CountDownLatch(1);
    var saves = new AtomicInteger();
    var loop = new AgentLoop(AgentState.initial(thread()), reducer(PermissionVerdict.ALLOW),
        (turn, messages, cancellation, sink) -> {},
        call -> new ToolCompletion.Success("unused"),
        call -> new PermissionPort.Decision(true, false), ignored -> saves.incrementAndGet(),
        state -> {
          observerEntered.countDown();
          await(releaseObserver);
        });

    java.lang.Thread.startVirtualThread(
        () -> loop.dispatch(new RuntimeMessage.Submit("persist after observer", List.of())));
    assertThat(observerEntered.await(5, TimeUnit.SECONDS)).isTrue();
    java.lang.Thread.startVirtualThread(() -> {
      loop.close();
      closeFinished.countDown();
    });
    assertThat(closeFinished.await(100, TimeUnit.MILLISECONDS)).isFalse();

    releaseObserver.countDown();
    assertThat(closeFinished.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(saves).hasValue(1);
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

  @Test void createsCheckpointEffectsOnTheStructuredWorkerPool() throws Exception {
    var created = new CountDownLatch(1);
    var captured = new java.util.concurrent.atomic.AtomicReference<CheckpointId>();
    CheckpointPort checkpoints = new CheckpointPort() {
      @Override public boolean enabled() { return true; }
      @Override public boolean create(CheckpointId id) {
        captured.set(id);
        created.countDown();
        return true;
      }
    };
    try (var loop = new AgentLoop(AgentState.initial(thread()), reducer(PermissionVerdict.ALLOW),
        (turn, messages, cancellation, sink) -> {},
        call -> new ToolCompletion.Success("unused"),
        call -> new PermissionPort.Decision(true, false), thread -> {},
        (message, state) -> {}, checkpoints)) {
      loop.dispatch(new RuntimeMessage.Submit("checkpoint", List.of(),
          java.util.Optional.of(new CheckpointId("cp"))));
      assertThat(created.await(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(captured.get()).isEqualTo(new CheckpointId("cp"));
    assertThat(CheckpointPort.disabled().enabled()).isFalse();
    assertThat(CheckpointPort.disabled().create(new CheckpointId("ignored"))).isFalse();
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

  @Test void interpretsOAuthRefreshAndResumesTheParkedStream() throws Exception {
    var idle = new CountDownLatch(1);
    var providerCalls = new AtomicInteger();
    var refreshCalls = new AtomicInteger();
    ProviderPort provider = (turn, messages, cancellation, sink) -> {
      if (providerCalls.incrementAndGet() == 1) {
        sink.accept(new StreamEvent.Error("expired", java.util.Optional.empty(),
            ErrorClass.AUTH, false));
      } else {
        sink.accept(new StreamEvent.TextDelta("refreshed"));
        sink.accept(new StreamEvent.Finished(StopReason.END_TURN));
      }
    };
    OAuthRefreshPort refresh = token -> {
      refreshCalls.incrementAndGet();
      assertThat(token).isEqualTo("refresh-token");
      return new OAuthRefreshPort.Result.Success();
    };
    try (var loop = new AgentLoop(AgentState.initial(thread()), reducerWithRefreshToken(),
        provider, call -> new ToolCompletion.Success("unused"),
        call -> new PermissionPort.Decision(true, false), thread -> {}, state -> {
          if (state.phase() instanceof SessionPhase.Idle
              && state.thread().messages().getLast().text().equals("refreshed")) idle.countDown();
        }, refresh)) {
      loop.dispatch(new RuntimeMessage.Submit("question", List.of()));
      assertThat(idle.await(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(providerCalls).hasValue(2);
    assertThat(refreshCalls).hasValue(1);
  }

  @Test void propagatesToolProgressAndParentCancellationThroughTheExecutionPort()
      throws Exception {
    var toolEntered = new CountDownLatch(1);
    var progressObserved = new CountDownLatch(1);
    var cancellationObserved = new CountDownLatch(1);
    ProviderPort provider = (turn, messages, cancellation, sink) -> {
      sink.accept(new StreamEvent.ToolUseStart("task-1", "task"));
      sink.accept(new StreamEvent.ToolUseDelta("{\"prompt\":\"inspect\"}"));
      sink.accept(new StreamEvent.ToolUseEnd());
      sink.accept(new StreamEvent.Finished(StopReason.TOOL_USE));
    };
    ToolPort tools = new ToolPort() {
      @Override public ToolCompletion execute(com.github.skanga.ajent.domain.ToolUse call) {
        throw new AssertionError("context-aware execution overload was not used");
      }

      @Override public ToolCompletion execute(com.github.skanga.ajent.domain.ToolUse call,
          com.github.skanga.ajent.domain.CancellationSignal cancellation,
          java.util.function.Consumer<String> progress) {
        toolEntered.countDown();
        progress.accept("◆ explorer agent\n  ⚙ read README.md");
        while (!cancellation.isCancelled()) java.lang.Thread.onSpinWait();
        cancellationObserved.countDown();
        return new ToolCompletion.Failure("cancelled");
      }
    };
    try (var loop = new AgentLoop(AgentState.initial(thread()), reducer(PermissionVerdict.ALLOW),
        provider, tools, call -> new PermissionPort.Decision(true, false), ignored -> {},
        state -> state.thread().messages().stream()
            .flatMap(message -> message.toolCalls().stream())
            .filter(call -> call.status() instanceof com.github.skanga.ajent.domain.ToolStatus.Running)
            .map(call -> (com.github.skanga.ajent.domain.ToolStatus.Running) call.status())
            .filter(running -> running.progressText().contains("explorer agent"))
            .findAny().ifPresent(ignored -> progressObserved.countDown()))) {
      loop.dispatch(new RuntimeMessage.Submit("delegate", List.of()));
      assertThat(toolEntered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(progressObserved.await(5, TimeUnit.SECONDS)).isTrue();
      loop.dispatch(new RuntimeMessage.Cancel());
      assertThat(cancellationObserved.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(loop.state().phase()).isInstanceOf(SessionPhase.Idle.class);
      assertThat(loop.state().status()).isEqualTo("cancelled");
    }
  }

  private static AgentReducer reducer(PermissionVerdict verdict) {
    var ids = new AtomicInteger();
    return new AgentReducer(new AgentReducer.Context(System::nanoTime,
        () -> Instant.parse("2026-07-17T00:00:00Z"),
        () -> new MessageId("loop-" + ids.incrementAndGet()), call -> verdict));
  }

  private static AgentReducer reducerWithRefreshToken() {
    var ids = new AtomicInteger();
    return new AgentReducer(new AgentReducer.Context(System::nanoTime,
        () -> Instant.parse("2026-07-17T00:00:00Z"),
        () -> new MessageId("loop-refresh-" + ids.incrementAndGet()),
        call -> PermissionVerdict.ALLOW, () -> 1.0, () -> 200_000,
        () -> java.util.Optional.of("refresh-token")));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
    } catch (InterruptedException exception) {
      java.lang.Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", exception);
    }
  }

  private static Thread thread() {
    Instant now = Instant.parse("2026-07-17T00:00:00Z");
    return new Thread(new ThreadId("loop"), "Loop", List.of(), now, now, List.of());
  }
}
