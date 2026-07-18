package com.github.skanga.ajent.protocol.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Correlated newline-delimited MCP JSON-RPC transport over a spawned process's stdio. */
public final class McpStdioTransport implements McpClientSession.Transport {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int TRANSPORT_ERROR = -32003;
  private final Consumer<String> outbound;
  private final BooleanSupplier backingAlive;
  private final Runnable closeAction;
  private final Object writeLock = new Object();
  private final AtomicLong ids = new AtomicLong();
  private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending =
      new ConcurrentHashMap<>();
  private final AtomicBoolean open = new AtomicBoolean(true);
  private volatile BiConsumer<String, JsonNode> notifications = (method, parameters) -> {};

  McpStdioTransport(
      Consumer<String> outbound, BooleanSupplier backingAlive, Runnable closeAction) {
    this.outbound = Objects.requireNonNull(outbound, "outbound");
    this.backingAlive = Objects.requireNonNull(backingAlive, "backingAlive");
    this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
  }

  public static McpStdioTransport spawn(
      McpConfigLoader.Server.Stdio configuration, Consumer<String> stderr) {
    Objects.requireNonNull(configuration, "configuration");
    Objects.requireNonNull(stderr, "stderr");
    try {
      var command = new java.util.ArrayList<String>();
      command.add(configuration.command());
      command.addAll(configuration.arguments());
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.environment().putAll(configuration.environment());
      Process process = builder.start();
      var writer = new BufferedWriter(new OutputStreamWriter(
          process.getOutputStream(), StandardCharsets.UTF_8));
      var transport = new McpStdioTransport(line -> write(writer, line), process::isAlive,
          () -> terminate(process, writer));
      java.lang.Thread.startVirtualThread(() -> read(
          process.getInputStream(), transport::acceptLine, transport::remoteClosed));
      java.lang.Thread.startVirtualThread(() -> read(process.getErrorStream(), stderr, () -> {}));
      return transport;
    } catch (IOException exception) {
      throw new McpTransportException(TRANSPORT_ERROR,
          "could not start MCP server '" + configuration.name() + "': "
              + exception.getMessage(), exception);
    }
  }

  @Override public JsonNode request(String method, ObjectNode parameters, Duration timeout) {
    requireOpen();
    long id = ids.incrementAndGet();
    var result = new CompletableFuture<JsonNode>();
    pending.put(id, result);
    ObjectNode request = JSON.createObjectNode();
    request.put("jsonrpc", "2.0"); request.put("id", id); request.put("method", method);
    if (!parameters.isEmpty()) request.set("params", parameters);
    try {
      send(request);
      return result.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException exception) {
      pending.remove(id);
      cancel(id, "MCP request timed out after " + timeout.toMillis() + " ms");
      throw new McpTransportException(TRANSPORT_ERROR,
          "MCP request timed out after " + timeout.toMillis() + " ms", exception);
    } catch (InterruptedException exception) {
      pending.remove(id);
      java.lang.Thread.currentThread().interrupt();
      cancel(id, "MCP request interrupted");
      throw new McpTransportException(TRANSPORT_ERROR, "MCP request interrupted", exception);
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof McpTransportException transport) throw transport;
      throw new McpTransportException(TRANSPORT_ERROR,
          cause == null ? "MCP request failed" : cause.getMessage(), cause);
    } catch (RuntimeException exception) {
      pending.remove(id);
      throw exception;
    }
  }

  @Override public void notify(String method, ObjectNode parameters) {
    requireOpen();
    ObjectNode notification = JSON.createObjectNode();
    notification.put("jsonrpc", "2.0"); notification.put("method", method);
    if (!parameters.isEmpty()) notification.set("params", parameters);
    send(notification);
  }

  @Override public void onNotification(BiConsumer<String, JsonNode> handler) {
    notifications = Objects.requireNonNull(handler, "handler");
  }

  void acceptLine(String line) {
    JsonNode frame;
    try {
      frame = JSON.readTree(line);
    } catch (JsonProcessingException exception) {
      return;
    }
    if (frame == null || !frame.isObject()) return;
    if (frame.has("method")) {
      if (frame.has("id")) rejectInboundRequest(frame);
      else notifications.accept(frame.path("method").asText(), frame.path("params"));
      return;
    }
    if (!frame.path("id").canConvertToLong()) return;
    CompletableFuture<JsonNode> waiting = pending.remove(frame.path("id").longValue());
    if (waiting == null) return;
    if (frame.has("error")) {
      JsonNode error = frame.path("error");
      waiting.completeExceptionally(new McpTransportException(error.path("code").asInt(),
          error.path("message").asText("MCP error"), error.path("data")));
    } else if (frame.has("result")) {
      waiting.complete(frame.path("result"));
    } else {
      waiting.completeExceptionally(new McpTransportException(
          -32600, "MCP response has neither result nor error"));
    }
  }

  void remoteClosed() {
    if (!open.compareAndSet(true, false)) return;
    failPending("MCP stdio server closed its output");
  }

  private void rejectInboundRequest(JsonNode frame) {
    ObjectNode response = JSON.createObjectNode();
    response.put("jsonrpc", "2.0"); response.set("id", frame.path("id").deepCopy());
    ObjectNode error = response.putObject("error");
    error.put("code", -32601);
    error.put("message", "Method not found: " + frame.path("method").asText());
    send(response);
  }

  private void cancel(long id, String reason) {
    if (!alive()) return;
    ObjectNode parameters = JSON.createObjectNode();
    parameters.put("requestId", id); parameters.put("reason", reason);
    notify("notifications/cancelled", parameters);
  }

  private void send(JsonNode frame) {
    String encoded;
    try {
      encoded = JSON.writeValueAsString(frame);
    } catch (JsonProcessingException exception) {
      throw new McpTransportException(TRANSPORT_ERROR, "could not encode MCP frame", exception);
    }
    synchronized (writeLock) {
      requireOpen();
      outbound.accept(encoded);
    }
  }

  private void requireOpen() {
    if (!alive()) throw new McpTransportException(TRANSPORT_ERROR, "MCP transport is closed");
  }

  @Override public boolean alive() {
    return open.get() && backingAlive.getAsBoolean();
  }

  @Override public void close() {
    if (!open.compareAndSet(true, false)) return;
    failPending("MCP transport closed");
    closeAction.run();
  }

  private void failPending(String message) {
    pending.forEach((id, future) -> future.completeExceptionally(
        new McpTransportException(TRANSPORT_ERROR, message)));
    pending.clear();
  }

  private static void write(BufferedWriter writer, String line) {
    try {
      writer.write(line); writer.newLine(); writer.flush();
    } catch (IOException exception) {
      throw new McpTransportException(TRANSPORT_ERROR,
          "could not write MCP stdio frame: " + exception.getMessage(), exception);
    }
  }

  private static void read(
      java.io.InputStream input, Consumer<String> lines, Runnable finished) {
    try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) lines.accept(line);
    } catch (IOException ignored) {
      // Process shutdown closes the stream.
    } finally {
      finished.run();
    }
  }

  private static void terminate(Process process, BufferedWriter writer) {
    try { writer.close(); } catch (IOException ignored) {}
    process.destroy();
    try {
      if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
    } catch (InterruptedException exception) {
      java.lang.Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }
}
