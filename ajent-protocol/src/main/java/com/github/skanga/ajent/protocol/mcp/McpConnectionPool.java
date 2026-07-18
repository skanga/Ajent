package com.github.skanga.ajent.protocol.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Builds all configured MCP sessions in parallel behind one startup deadline. */
public final class McpConnectionPool {
  @FunctionalInterface
  interface Connector {
    McpClientSession connect(McpConfigLoader.Server server, Duration callTimeout,
                             Duration connectTimeout, String version);
  }

  public record ConnectResult(McpRegistry registry, List<String> diagnostics) {
    public ConnectResult {
      registry = Objects.requireNonNull(registry, "registry");
      diagnostics = List.copyOf(diagnostics);
    }
  }

  private McpConnectionPool() {}

  public static ConnectResult connect(McpConfigLoader.LoadResult configuration,
                                      String version, Consumer<String> stderr) {
    Objects.requireNonNull(stderr, "stderr");
    return connect(configuration, version, (server, callTimeout, connectTimeout, clientVersion) -> {
      McpClientSession.Transport transport = switch (server) {
        case McpConfigLoader.Server.Stdio stdio -> McpStdioTransport.spawn(stdio, stderr);
        case McpConfigLoader.Server.Http http -> new McpHttpTransport(http);
      };
      var session = new McpClientSession(server.name(), transport, callTimeout,
          connectTimeout, clientVersion);
      try {
        session.connect();
        return session;
      } catch (RuntimeException failure) {
        session.close();
        throw failure;
      }
    });
  }

  static ConnectResult connect(McpConfigLoader.LoadResult configuration, String version,
                               Connector connector) {
    Objects.requireNonNull(configuration, "configuration");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(connector, "connector");
    var registry = new McpRegistry();
    var diagnostics = new ArrayList<>(configuration.diagnostics());
    if (!configuration.permitted() || configuration.servers().isEmpty())
      return new ConnectResult(registry, diagnostics);

    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var pending = new ArrayList<Pending>();
    for (McpConfigLoader.Server server : configuration.servers()) {
      CompletableFuture<McpClientSession> future = CompletableFuture.supplyAsync(() ->
          connector.connect(server, configuration.callTimeout(),
              configuration.connectTimeout(), version), executor);
      pending.add(new Pending(server.name(), future));
    }
    long deadline = System.nanoTime() + configuration.connectTimeout().toNanos();
    for (Pending candidate : pending) {
      long remaining = Math.max(0, deadline - System.nanoTime());
      try {
        McpClientSession session = candidate.future().get(remaining, TimeUnit.NANOSECONDS);
        if (session != null) registry.add(candidate.name(), session);
        else diagnostics.add("MCP server '" + candidate.name() + "' returned no session");
      } catch (TimeoutException failure) {
        diagnostics.add("MCP server '" + candidate.name()
            + "' did not connect within the global deadline; skipping");
        candidate.future().whenComplete((late, ignored) -> close(late));
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        diagnostics.add("MCP connection interrupted while waiting for '" + candidate.name() + "'");
        candidate.future().whenComplete((late, ignored) -> close(late));
      } catch (ExecutionException failure) {
        Throwable cause = rootCause(failure);
        diagnostics.add("MCP server '" + candidate.name() + "' failed: "
            + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
      }
    }
    executor.shutdown();
    return new ConnectResult(registry, diagnostics);
  }

  private static Throwable rootCause(Throwable failure) {
    Throwable current = failure;
    while (current.getCause() != null && current.getCause() != current) current = current.getCause();
    return current;
  }

  private static void close(McpClientSession session) {
    if (session != null) session.close();
  }

  private record Pending(String name, CompletableFuture<McpClientSession> future) {}
}
