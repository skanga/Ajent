package com.github.skanga.ajent.runtime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Structured virtual-thread interpreter for {@link AgentReducer}'s effects. */
public final class AgentLoop implements AutoCloseable {
  private final Object lock = new Object();
  private final AgentReducer reducer;
  private final ProviderPort provider;
  private final ToolPort tools;
  private final PermissionPort permissions;
  private final PersistencePort persistence;
  private final Consumer<AgentState> observer;
  private final ExecutorService tasks;
  private final ScheduledExecutorService scheduler;
  private AgentState state;
  private boolean closed;

  public AgentLoop(AgentState initial, AgentReducer reducer, ProviderPort provider, ToolPort tools,
                   PermissionPort permissions, PersistencePort persistence,
                   Consumer<AgentState> observer) {
    this(initial, reducer, provider, tools, permissions, persistence, observer,
        Executors.newVirtualThreadPerTaskExecutor());
  }

  AgentLoop(AgentState initial, AgentReducer reducer, ProviderPort provider, ToolPort tools,
            PermissionPort permissions, PersistencePort persistence, Consumer<AgentState> observer,
            ExecutorService tasks) {
    state = Objects.requireNonNull(initial, "initial");
    this.reducer = Objects.requireNonNull(reducer, "reducer");
    this.provider = Objects.requireNonNull(provider, "provider");
    this.tools = Objects.requireNonNull(tools, "tools");
    this.permissions = Objects.requireNonNull(permissions, "permissions");
    this.persistence = Objects.requireNonNull(persistence, "persistence");
    this.observer = Objects.requireNonNull(observer, "observer");
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    scheduler = Executors.newSingleThreadScheduledExecutor(
        java.lang.Thread.ofVirtual().name("ajent-retry-", 0).factory());
  }

  public AgentState dispatch(RuntimeMessage message) {
    AgentReducer.Step step;
    synchronized (lock) {
      if (closed) throw new IllegalStateException("agent loop is closed");
      step = reducer.update(state, message);
      state = step.state();
    }
    observer.accept(step.state());
    step.effects().forEach(this::execute);
    return step.state();
  }

  public AgentState state() {
    synchronized (lock) {
      return state;
    }
  }

  private void execute(RuntimeEffect effect) {
    switch (effect) {
      case RuntimeEffect.Persist persist -> tasks.submit(() -> persistence.save(persist.thread()));
      case RuntimeEffect.StartStream stream -> tasks.submit(() -> {
        try {
          provider.stream(stream.turnId(), stream.messages(), stream.cancellation(), event ->
              dispatchIfOpen(new RuntimeMessage.ProviderEvent(stream.turnId(), event)));
        } catch (RuntimeException exception) {
          dispatchIfOpen(new RuntimeMessage.ProviderEvent(stream.turnId(),
              new com.github.skanga.ajent.provider.stream.StreamEvent.Error(
                  "provider: " + exception.getMessage())));
        }
      });
      case RuntimeEffect.ExecuteTool execute -> tasks.submit(() -> {
        ToolCompletion result;
        try {
          result = tools.execute(execute.call());
        } catch (RuntimeException exception) {
          result = new ToolCompletion.Failure("[INTERNAL] " + exception.getMessage());
        }
        dispatchIfOpen(new RuntimeMessage.ToolCompleted(execute.turnId(),
            execute.call().id().value(), result));
      });
      case RuntimeEffect.RequestPermission request -> tasks.submit(() -> {
        PermissionPort.Decision decision;
        try {
          decision = permissions.request(request.call());
        } catch (RuntimeException exception) {
          decision = new PermissionPort.Decision(false, false);
        }
        dispatchIfOpen(new RuntimeMessage.PermissionResolved(request.call().id().value(),
            decision.approved(), decision.always()));
      });
      case RuntimeEffect.Schedule schedule -> scheduler.schedule(
          () -> dispatchIfOpen(schedule.message()), schedule.delay().toNanos(),
          TimeUnit.NANOSECONDS);
    }
  }

  private void dispatchIfOpen(RuntimeMessage message) {
    synchronized (lock) {
      if (closed) return;
    }
    dispatch(message);
  }

  @Override public void close() {
    synchronized (lock) {
      if (closed) return;
      closed = true;
      state.phase().active().ifPresent(active -> active.cancellation().cancel());
    }
    scheduler.shutdownNow();
    tasks.close();
  }
}
