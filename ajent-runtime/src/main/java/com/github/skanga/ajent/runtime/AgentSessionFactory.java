package com.github.skanga.ajent.runtime;

import com.github.skanga.ajent.domain.MessageId;
import com.github.skanga.ajent.domain.Profile;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.tools.catalog.ToolCatalog;
import com.github.skanga.ajent.tools.policy.PermissionDecision;
import com.github.skanga.ajent.tools.policy.PermissionPolicy;
import com.github.skanga.ajent.tools.runtime.ToolRuntimeFactory;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/** Composes one independent live agent loop for an ACP or terminal session. */
public final class AgentSessionFactory {
  private final ToolRuntimeFactory.Components tools;
  private final LiveProviderFactory.Configuration baseProvider;
  private final HttpClient client;
  private final Path dataDirectory;
  private final BiFunction<LiveProviderFactory.Configuration, HttpClient, ProviderPort> providers;

  public AgentSessionFactory(
      ToolRuntimeFactory.Components tools,
      LiveProviderFactory.Configuration baseProvider,
      HttpClient client,
      Path dataDirectory) {
    this(tools, baseProvider, client, dataDirectory, LiveProviderFactory::create);
  }

  AgentSessionFactory(
      ToolRuntimeFactory.Components tools,
      LiveProviderFactory.Configuration baseProvider,
      HttpClient client,
      Path dataDirectory,
      BiFunction<LiveProviderFactory.Configuration, HttpClient, ProviderPort> providers) {
    this.tools = Objects.requireNonNull(tools, "tools");
    this.baseProvider = Objects.requireNonNull(baseProvider, "baseProvider");
    this.client = Objects.requireNonNull(client, "client");
    this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
        .toAbsolutePath().normalize();
    this.providers = Objects.requireNonNull(providers, "providers");
  }

  public AgentLoop create(
      Thread thread,
      Profile profile,
      String model,
      PermissionPort permissions,
      BiConsumer<RuntimeMessage, AgentState> observer) {
    Objects.requireNonNull(thread, "thread");
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(permissions, "permissions");
    Objects.requireNonNull(observer, "observer");
    LiveProviderFactory.Configuration provider = withModel(model);
    var reducer = new AgentReducer(new AgentReducer.Context(
        System::nanoTime, Instant::now, MessageId::random,
        call -> permission(call, profile),
        () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble(0.80, 1.20),
        () -> contextMax(provider)));
    return new AgentLoop(
        AgentState.initial(thread), reducer, providers.apply(provider, client),
        new DispatcherToolPort(tools.dispatcher()), permissions,
        new FilePersistencePort(dataDirectory), observer);
  }

  public int contextMax() {
    return contextMax(baseProvider);
  }

  private LiveProviderFactory.Configuration withModel(String model) {
    return new LiveProviderFactory.Configuration(
        baseProvider.provider(), model, baseProvider.auth(), baseProvider.effort(),
        baseProvider.systemPrompt(), baseProvider.contextWindow(), baseProvider.environment());
  }

  static PermissionVerdict permission(ToolUse call, Profile profile) {
    return ToolCatalog.byName(call.name().value())
        .map(spec -> PermissionPolicy.permission(spec.effects(), profile)
            == PermissionDecision.ALLOW ? PermissionVerdict.ALLOW : PermissionVerdict.PROMPT)
        .orElse(PermissionVerdict.DENY);
  }

  private static int contextMax(LiveProviderFactory.Configuration provider) {
    return provider.contextWindow() > 0 ? provider.contextWindow() : 200_000;
  }
}
