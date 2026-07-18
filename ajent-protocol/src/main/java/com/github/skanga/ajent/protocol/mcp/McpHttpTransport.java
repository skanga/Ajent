package com.github.skanga.ajent.protocol.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/** MCP Streamable HTTP transport over the JDK HTTP client. */
public final class McpHttpTransport implements McpClientSession.Transport {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int TRANSPORT_ERROR = -32003;
  private static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;
  private static final String PROTOCOL_VERSION = "2025-11-25";

  private final McpConfigLoader.Server.Http configuration;
  private final URI endpoint;
  private final HttpClient client;
  private final AtomicLong ids = new AtomicLong();
  private final AtomicBoolean open = new AtomicBoolean(true);
  private final AtomicReference<String> sessionId = new AtomicReference<>("");
  private final AtomicReference<String> protocolVersion = new AtomicReference<>("");
  private final Set<CompletableFuture<?>> inflight = ConcurrentHashMap.newKeySet();
  private volatile BiConsumer<String, JsonNode> notifications = (method, parameters) -> {};

  public McpHttpTransport(McpConfigLoader.Server.Http configuration) {
    this(configuration, HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build());
  }

  McpHttpTransport(McpConfigLoader.Server.Http configuration, HttpClient client) {
    this.configuration = Objects.requireNonNull(configuration, "configuration");
    this.client = Objects.requireNonNull(client, "client");
    try {
      endpoint = URI.create(configuration.url());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("invalid MCP HTTP URL: " + configuration.url(), exception);
    }
    String scheme = endpoint.getScheme();
    if (!endpoint.isAbsolute() || endpoint.getHost() == null
        || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
      throw new IllegalArgumentException("MCP endpoint must be an absolute http(s) URL: "
          + configuration.url());
    }
  }

  @Override public JsonNode request(String method, ObjectNode parameters, Duration timeout) {
    requireOpen();
    long id = ids.incrementAndGet();
    ObjectNode frame = JSON.createObjectNode();
    frame.put("jsonrpc", "2.0"); frame.put("id", id); frame.put("method", method);
    if (!parameters.isEmpty()) frame.set("params", parameters);
    List<JsonNode> frames = post(frame, timeout);
    JsonNode response = frames.stream()
        .filter(candidate -> candidate.path("id").canConvertToLong()
            && candidate.path("id").longValue() == id)
        .findFirst().orElseThrow(() -> new McpTransportException(
            -32603, "empty HTTP response to request"));
    routeOtherFrames(frames, response);
    if (response.has("error")) {
      JsonNode error = response.path("error");
      throw new McpTransportException(error.path("code").asInt(),
          error.path("message").asText("MCP error"), error.path("data"));
    }
    if (!response.has("result")) {
      throw new McpTransportException(-32600, "MCP response has neither result nor error");
    }
    if ("initialize".equals(method)) protocolVersion.set(PROTOCOL_VERSION);
    return response.path("result");
  }

  @Override public void notify(String method, ObjectNode parameters) {
    requireOpen();
    ObjectNode frame = JSON.createObjectNode();
    frame.put("jsonrpc", "2.0"); frame.put("method", method);
    if (!parameters.isEmpty()) frame.set("params", parameters);
    routeOtherFrames(post(frame, Duration.ofSeconds(60)), null);
  }

  @Override public void onNotification(BiConsumer<String, JsonNode> handler) {
    notifications = Objects.requireNonNull(handler, "handler");
  }

  private List<JsonNode> post(ObjectNode frame, Duration timeout) {
    HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
        .timeout(timeout)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(encode(frame), StandardCharsets.UTF_8));
    String session = sessionId.get();
    if (!session.isEmpty()) request.header("Mcp-Session-Id", session);
    String version = protocolVersion.get();
    if (!version.isEmpty()) request.header("MCP-Protocol-Version", version);
    configuration.headers().forEach(request::header);

    CompletableFuture<HttpResponse<byte[]>> call = client.sendAsync(
        request.build(), info -> new LimitedBodySubscriber());
    inflight.add(call);
    HttpResponse<byte[]> response;
    try {
      response = call.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException exception) {
      call.cancel(true);
      throw new McpTransportException(TRANSPORT_ERROR,
          "MCP HTTP request timed out after " + timeout.toMillis() + " ms", exception);
    } catch (InterruptedException exception) {
      call.cancel(true);
      Thread.currentThread().interrupt();
      throw new McpTransportException(TRANSPORT_ERROR, "MCP HTTP request interrupted", exception);
    } catch (ExecutionException exception) {
      Throwable cause = rootCause(exception);
      String message = cause == null ? "MCP HTTP request failed" : cause.getMessage();
      throw new McpTransportException(TRANSPORT_ERROR,
          message == null ? "MCP HTTP request failed" : message, cause);
    } finally {
      inflight.remove(call);
    }

    response.headers().firstValue("Mcp-Session-Id")
        .filter(value -> !value.isEmpty()).ifPresent(value -> sessionId.compareAndSet("", value));
    int status = response.statusCode();
    if (status == 404 || status == 410) sessionId.set("");
    if (status < 200 || status >= 300) {
      throw new McpTransportException(TRANSPORT_ERROR,
          "MCP HTTP request failed with status " + status);
    }
    byte[] body = response.body();
    if (body.length == 0) return List.of();
    String contentType = response.headers().firstValue("Content-Type")
        .orElse("application/json").toLowerCase(Locale.ROOT);
    return contentType.contains("text/event-stream")
        ? decodeSse(new String(body, StandardCharsets.UTF_8))
        : decodeJson(body);
  }

  private void routeOtherFrames(List<JsonNode> frames, JsonNode selected) {
    for (JsonNode frame : frames) {
      if (frame == selected || !frame.has("method") || frame.has("id")) continue;
      notifications.accept(frame.path("method").asText(), frame.path("params"));
    }
  }

  private static List<JsonNode> decodeJson(byte[] body) {
    try {
      JsonNode decoded = JSON.readTree(body);
      if (decoded == null) return List.of();
      if (!decoded.isArray()) return List.of(decoded);
      var frames = new ArrayList<JsonNode>(); decoded.forEach(frames::add); return frames;
    } catch (IOException exception) {
      throw new McpTransportException(-32700, "invalid MCP HTTP JSON response", exception);
    }
  }

  private static List<JsonNode> decodeSse(String body) {
    var frames = new ArrayList<JsonNode>();
    String normalized = body.replace("\r\n", "\n").replace('\r', '\n');
    for (String event : normalized.split("\n\n", -1)) {
      var data = new StringBuilder();
      for (String line : event.split("\n", -1)) if (line.startsWith("data:")) {
        String value = line.substring(5);
        if (value.startsWith(" ")) value = value.substring(1);
        if (!data.isEmpty()) data.append('\n');
        data.append(value);
      }
      if (!data.isEmpty()) frames.addAll(decodeJson(data.toString()
          .getBytes(StandardCharsets.UTF_8)));
    }
    return frames;
  }

  private static String encode(JsonNode frame) {
    try { return JSON.writeValueAsString(frame); }
    catch (JsonProcessingException exception) {
      throw new McpTransportException(TRANSPORT_ERROR, "could not encode MCP HTTP frame", exception);
    }
  }

  private static Throwable rootCause(Throwable failure) {
    Throwable current = failure;
    while (current.getCause() != null && current.getCause() != current) current = current.getCause();
    return current;
  }

  private void requireOpen() {
    if (!open.get()) throw new McpTransportException(TRANSPORT_ERROR, "MCP transport is closed");
  }

  @Override public boolean alive() { return open.get(); }

  @Override public void close() {
    if (!open.compareAndSet(true, false)) return;
    inflight.forEach(future -> future.cancel(true));
    inflight.clear();
  }

  private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    private int size;

    @Override public CompletionStage<byte[]> getBody() { return body; }
    @Override public void onSubscribe(Flow.Subscription value) {
      subscription = value; value.request(1);
    }
    @Override public void onNext(List<ByteBuffer> buffers) {
      try {
        for (ByteBuffer buffer : buffers) {
          int remaining = buffer.remaining();
          if ((long) size + remaining > MAX_RESPONSE_BYTES) {
            subscription.cancel();
            body.completeExceptionally(new IllegalStateException(
                "MCP HTTP response exceeded 64 MiB cap"));
            return;
          }
          byte[] chunk = new byte[remaining]; buffer.get(chunk); bytes.writeBytes(chunk);
          size += remaining;
        }
        subscription.request(1);
      } catch (RuntimeException exception) {
        subscription.cancel(); body.completeExceptionally(exception);
      }
    }
    @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }
    @Override public void onComplete() { body.complete(bytes.toByteArray()); }
  }
}
