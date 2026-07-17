package com.github.skanga.ajent.runtime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Structured virtual-thread interpreter for {@link AgentReducer}'s effects. */
public final class AgentLoop implements AutoCloseable {
  private static final long HEADLESS_TICK_MILLIS = 100;
  private final Object lock = new Object();
  private final AgentReducer reducer;
  private final ProviderPort provider;
  private final ToolPort tools;
  private final PermissionPort permissions;
  private final PersistencePort persistence;
  private final BiConsumer<RuntimeMessage, AgentState> observer;
  private final OAuthRefreshPort oauthRefresh;
  private final ExecutorService tasks;
  private final ScheduledExecutorService scheduler;
  private AgentState state;
  private boolean closed;

  public AgentLoop(AgentState initial, AgentReducer reducer, ProviderPort provider, ToolPort tools,
                   PermissionPort permissions, PersistencePort persistence,
                   Consumer<AgentState> observer) {
    this(initial, reducer, provider, tools, permissions, persistence, states(observer),
        token -> new OAuthRefreshPort.Result.Failure("OAuth refresh is not configured"),
        Executors.newVirtualThreadPerTaskExecutor());
  }

  public AgentLoop(AgentState initial, AgentReducer reducer, ProviderPort provider, ToolPort tools,
                   PermissionPort permissions, PersistencePort persistence,
                   BiConsumer<RuntimeMessage, AgentState> observer) {
    this(initial, reducer, provider, tools, permissions, persistence, observer,
        token -> new OAuthRefreshPort.Result.Failure("OAuth refresh is not configured"),
        Executors.newVirtualThreadPerTaskExecutor());
  }

  public AgentLoop(AgentState initial, AgentReducer reducer, ProviderPort provider, ToolPort tools,
                   PermissionPort permissions, PersistencePort persistence,
                   Consumer<AgentState> observer, OAuthRefreshPort oauthRefresh) {
    this(initial, reducer, provider, tools, permissions, persistence, states(observer), oauthRefresh,
        Executors.newVirtualThreadPerTaskExecutor());
  }

  public AgentLoop(AgentState initial, AgentReducer reducer, ProviderPort provider, ToolPort tools,
                   PermissionPort permissions, PersistencePort persistence,
                   BiConsumer<RuntimeMessage, AgentState> observer,
                   OAuthRefreshPort oauthRefresh) {
    this(initial, reducer, provider, tools, permissions, persistence, observer, oauthRefresh,
        Executors.newVirtualThreadPerTaskExecutor());
  }

  AgentLoop(AgentState initial, AgentReducer reducer, ProviderPort provider, ToolPort tools,
            PermissionPort permissions, PersistencePort persistence, Consumer<AgentState> observer,
            ExecutorService tasks) {
    this(initial, reducer, provider, tools, permissions, persistence, states(observer),
        token -> new OAuthRefreshPort.Result.Failure("OAuth refresh is not configured"), tasks);
  }

  AgentLoop(AgentState initial, AgentReducer reducer, ProviderPort provider, ToolPort tools,
            PermissionPort permissions, PersistencePort persistence, Consumer<AgentState> observer,
            OAuthRefreshPort oauthRefresh, ExecutorService tasks) {
    this(initial, reducer, provider, tools, permissions, persistence, states(observer),
        oauthRefresh, tasks);
  }

  private AgentLoop(AgentState initial, AgentReducer reducer, ProviderPort provider, ToolPort tools,
            PermissionPort permissions, PersistencePort persistence,
            BiConsumer<RuntimeMessage, AgentState> observer,
            OAuthRefreshPort oauthRefresh, ExecutorService tasks) {
    state = Objects.requireNonNull(initial, "initial");
    this.reducer = Objects.requireNonNull(reducer, "reducer");
    this.provider = Objects.requireNonNull(provider, "provider");
    this.tools = Objects.requireNonNull(tools, "tools");
    this.permissions = Objects.requireNonNull(permissions, "permissions");
    this.persistence = Objects.requireNonNull(persistence, "persistence");
    this.observer = Objects.requireNonNull(observer, "observer");
    this.oauthRefresh = Objects.requireNonNull(oauthRefresh, "oauthRefresh");
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    scheduler = Executors.newSingleThreadScheduledExecutor(
        java.lang.Thread.ofVirtual().name("ajent-retry-", 0).factory());
    scheduler.scheduleWithFixedDelay(this::dispatchTickIfActive, HEADLESS_TICK_MILLIS,
        HEADLESS_TICK_MILLIS, TimeUnit.MILLISECONDS);
  }

  public AgentState dispatch(RuntimeMessage message) {
    AgentReducer.Step step;
    synchronized (lock) {
      if (closed) throw new IllegalStateException("agent loop is closed");
      step = reducer.update(state, message);
      state = step.state();
    }
    observer.accept(message, step.state());
    step.effects().forEach(this::execute);
    return step.state();
  }

  private static BiConsumer<RuntimeMessage, AgentState> states(Consumer<AgentState> observer) {
    Objects.requireNonNull(observer, "observer");
    return (ignored, state) -> observer.accept(state);
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
      case RuntimeEffect.RefreshOAuth refresh -> tasks.submit(() -> {
        OAuthRefreshPort.Result result;
        try {
          result = oauthRefresh.refreshAndInstall(refresh.refreshToken());
        } catch (RuntimeException exception) {
          result = new OAuthRefreshPort.Result.Failure("refresh threw: "
              + exception.getMessage());
        }
        dispatchIfOpen(new RuntimeMessage.TokenRefreshed(refresh.turnId(), result));
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

  private void dispatchTickIfActive() {
    synchronized (lock) {
      if (closed || state.phase() instanceof com.github.skanga.ajent.domain.SessionPhase.Idle)
        return;
    }
    dispatchIfOpen(new RuntimeMessage.Tick());
  }

  @Override public void close() {
    synchronized (lock) {
      if (closed) return;
      closed = true;
      state.phase().active().ifPresent(active -> active.cancellation().cancel());
    }
    scheduler.shutdownNow();
    try {
      tasks.close();
    } finally {
      persistence.close();
    }
  }
}
