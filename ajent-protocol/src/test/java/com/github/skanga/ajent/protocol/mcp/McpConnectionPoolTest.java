package com.github.skanga.ajent.protocol.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

final class McpConnectionPoolTest {
  @Test void connectsInParallelUsesOneDeadlineAndDisposesLateSessions() throws Exception {
    var servers = List.<McpConfigLoader.Server>of(
        new McpConfigLoader.Server.Stdio("fast", "fast", List.of(), Map.of()),
        new McpConfigLoader.Server.Http("failed", "https://failed.test/mcp", Map.of()),
        new McpConfigLoader.Server.Stdio("slow", "slow", List.of(), Map.of()));
    var configuration = new McpConfigLoader.LoadResult(Optional.empty(), false, true, servers,
        Duration.ofSeconds(2), Duration.ofMillis(100), List.of("existing diagnostic"));
    var allStarted = new CountDownLatch(3);
    var lateClosed = new AtomicBoolean();
    long started = System.nanoTime();
    McpConnectionPool.ConnectResult result = McpConnectionPool.connect(configuration, "test",
        (server, callTimeout, connectTimeout, version) -> {
          allStarted.countDown();
          try { assertThat(allStarted.await(2, TimeUnit.SECONDS)).isTrue(); }
          catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
          if (server.name().equals("failed")) throw new IllegalStateException("handshake rejected");
          if (server.name().equals("slow")) {
            try { Thread.sleep(300); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
          }
          return session(server.name(), server.name().equals("slow") ? lateClosed : new AtomicBoolean());
        });
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

    try (McpRegistry registry = result.registry()) {
      assertThat(elapsedMillis).isLessThan(250);
      assertThat(registry.providerCount()).isEqualTo(1);
      assertThat(result.diagnostics()).contains("existing diagnostic");
      assertThat(result.diagnostics()).anySatisfy(value ->
          assertThat(value).contains("failed", "handshake rejected"));
      assertThat(result.diagnostics()).anySatisfy(value ->
          assertThat(value).contains("slow", "global deadline"));
      assertThat(waitUntil(lateClosed, Duration.ofSeconds(2))).isTrue();
    }
  }

  @Test void honorsDeniedAndEmptyConfigurationsWithoutCallingConnector() {
    var called = new AtomicBoolean();
    var denied = new McpConfigLoader.LoadResult(Optional.empty(), true, false,
        List.of(new McpConfigLoader.Server.Stdio("ignored", "ignored", List.of(), Map.of())),
        Duration.ofSeconds(1), Duration.ofSeconds(1), List.of("denied"));
    McpConnectionPool.ConnectResult result = McpConnectionPool.connect(denied, "test",
        (server, callTimeout, connectTimeout, version) -> {
          called.set(true); return session(server.name(), new AtomicBoolean());
        });
    try (McpRegistry registry = result.registry()) {
      assertThat(called).isFalse();
      assertThat(registry.providerCount()).isZero();
      assertThat(result.diagnostics()).containsExactly("denied");
    }
  }

  private static McpClientSession session(String name, AtomicBoolean closed) {
    return new McpClientSession(name, new McpClientSession.Transport() {
      @Override public JsonNode request(String method, ObjectNode parameters, Duration timeout) {
        throw new AssertionError("not called");
      }
      @Override public void notify(String method, ObjectNode parameters) {}
      @Override public void onNotification(BiConsumer<String, JsonNode> handler) {}
      @Override public boolean alive() { return !closed.get(); }
      @Override public void close() { closed.set(true); }
    }, Duration.ofSeconds(1), "test");
  }

  private static boolean waitUntil(AtomicBoolean value, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!value.get() && System.nanoTime() < deadline) Thread.sleep(10);
    return value.get();
  }
}
