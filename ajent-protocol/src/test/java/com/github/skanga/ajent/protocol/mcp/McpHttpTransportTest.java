package com.github.skanga.ajent.protocol.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class McpHttpTransportTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test void postsJsonAndSseWhileReplayingSessionVersionAndExtraHeaders() throws Exception {
    var requests = new CopyOnWriteArrayList<CapturedRequest>();
    try (var server = server(exchange -> {
      JsonNode request = read(exchange);
      requests.add(new CapturedRequest(request,
          exchange.getRequestHeaders().getFirst("Mcp-Session-Id"),
          exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"),
          exchange.getRequestHeaders().getFirst("Authorization"),
          exchange.getRequestHeaders().getFirst("Accept"),
          exchange.getRequestHeaders().getFirst("User-Agent")));
      String method = request.path("method").asText();
      if ("initialize".equals(method)) {
        respond(exchange, 200, "application/json", "test-session", response(request,
            "{\"protocolVersion\":\"2025-11-25\",\"serverInfo\":{\"name\":\"demo\"}}"));
      } else if ("notifications/initialized".equals(method)) {
        respond(exchange, 202, null, "test-session", "");
      } else if ("resource".equals(method)) {
        String json = response(request, "{\"text\":\"remote note body\"}");
        int split = json.indexOf("\"remote");
        String sse = ": keepalive\r\nevent: message\r\ndata: " + json.substring(0, split)
            + "\r\ndata: " + json.substring(split) + "\r\n\r\n";
        respond(exchange, 200, "text/event-stream; charset=utf-8", "test-session", sse);
      } else {
        respond(exchange, 200, "application/json", "test-session",
            response(request, "{\"ok\":true}"));
      }
    })) {
      var configuration = new McpConfigLoader.Server.Http("demo", endpoint(server),
          Map.of("Authorization", "Bearer secret"));
      try (var transport = new McpHttpTransport(configuration)) {
        JsonNode initialized = transport.request("initialize", JSON.createObjectNode(),
            Duration.ofSeconds(5));
        assertThat(initialized.path("serverInfo").path("name").asText()).isEqualTo("demo");
        transport.notify("notifications/initialized", JSON.createObjectNode());
        JsonNode resource = transport.request("resource", JSON.createObjectNode(),
            Duration.ofSeconds(5));
        assertThat(resource.path("text").asText()).isEqualTo("remote note body");
        assertThat(transport.alive()).isTrue();
      }
    }

    assertThat(requests).hasSize(3);
    assertThat(requests.getFirst().session()).isNull();
    assertThat(requests).allSatisfy(request -> {
      assertThat(request.version()).isEqualTo("2025-11-25");
      assertThat(request.authorization()).isEqualTo("Bearer secret");
      assertThat(request.accept()).contains("application/json", "text/event-stream");
      assertThat(request.userAgent()).isNullOrEmpty();
      assertThat(request.body().path("params").isObject()).isTrue();
    });
    assertThat(requests.subList(1, 3)).allSatisfy(request ->
        assertThat(request.session()).isEqualTo("test-session"));
  }

  @Test void portsTheLiveHttpCapabilityBridgeEndToEnd() throws Exception {
    var sentListChange = new AtomicBoolean();
    try (var server = server(exchange -> {
      JsonNode request = read(exchange);
      String method = request.path("method").asText();
      switch (method) {
        case "initialize" -> respond(exchange, 200, "application/json", "bridge-session",
            response(request, """
                {"protocolVersion":"2025-11-25","capabilities":{
                  "tools":{},"resources":{"listChanged":true},"prompts":{}},
                 "serverInfo":{"name":"http-demo"}}
                """));
        case "notifications/initialized" -> respond(exchange, 202, null, null, "");
        case "tools/list" -> respond(exchange, 200, "application/json", null,
            response(request, """
                {"tools":[{"name":"echo","description":"Echo text",
                  "inputSchema":{"type":"object"}}]}
                """));
        case "resources/list" -> respond(exchange, 200, "application/json", null,
            response(request, """
                {"resources":[{"uri":"mem://note","name":"note","title":"Note"}]}
                """));
        case "resources/templates/list" -> respond(exchange, 200, "application/json", null,
            response(request, "{\"resourceTemplates\":[]}"));
        case "prompts/list" -> respond(exchange, 200, "application/json", null,
            response(request, """
                {"prompts":[{"name":"greet","description":"Greeting"}]}
                """));
        case "tools/call" -> respond(exchange, 200, "application/json", null,
            response(request, """
                {"content":[{"type":"text","text":"echo: ping"}]}
                """));
        case "resources/read" -> {
          String notification = "{\"jsonrpc\":\"2.0\",\"method\":"
              + "\"notifications/resources/list_changed\",\"params\":{}}";
          String reply = response(request,
              "{\"contents\":[{\"uri\":\"mem://note\",\"text\":\"remote note body\"}]}");
          respond(exchange, 200, "text/event-stream", null,
              "data: " + notification + "\n\ndata: " + reply + "\n\n");
        }
        case "prompts/get" -> respond(exchange, 200, "application/json", null,
            response(request, """
                {"messages":[{"role":"user","content":{"type":"text",
                  "text":"Hello Ada"}}]}
                """));
        default -> respond(exchange, 500, "text/plain", null, method);
      }
    })) {
      var configuration = new McpConfigLoader.Server.Http("demo", endpoint(server), Map.of());
      try (var session = new McpClientSession("demo", new McpHttpTransport(configuration),
          Duration.ofSeconds(5), "test")) {
        session.onListChanged(() -> sentListChange.set(true));
        session.connect();
        assertThat(session.serverName()).isEqualTo("http-demo");
        assertThat(session.tools()).extracting(McpClientSession.RemoteTool::name)
            .containsExactly("echo");
        assertThat(session.call("echo", JSON.createObjectNode().put("text", "ping")).text())
            .contains("echo: ping");
        assertThat(session.resources()).extracting(McpClientSession.Resource::uri)
            .containsExactly("mem://note");
        assertThat(session.readResource("mem://note")).contains("remote note body");
        assertThat(sentListChange).isTrue();
        assertThat(session.prompts()).extracting(McpClientSession.Prompt::name)
            .containsExactly("greet");
        assertThat(session.getPrompt("greet", Map.of("name", "Ada")))
            .contains("Hello Ada");
      }
    }
  }

  @Test void mapsProtocolHttpTimeoutAndCloseFailuresAndClearsExpiredSession() throws Exception {
    var calls = new AtomicInteger();
    var sessions = new CopyOnWriteArrayList<String>();
    try (var server = server(exchange -> {
      JsonNode request = read(exchange);
      sessions.add(exchange.getRequestHeaders().getFirst("Mcp-Session-Id") == null
          ? "<none>" : exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
      String method = request.path("method").asText();
      switch (method) {
        case "initialize" -> respond(exchange, 200, "application/json", "old-session",
            response(request, "{\"protocolVersion\":\"2025-11-25\"}"));
        case "rpc-error" -> respond(exchange, 200, "application/json", null,
            error(request, -32042, "authorize", "{\"url\":\"https://x\"}"));
        case "empty" -> respond(exchange, 202, null, null, "");
        case "expired" -> respond(exchange, 410, "text/plain", null, "gone");
        case "after-expired" -> {
          calls.incrementAndGet();
          respond(exchange, 200, "application/json", null, response(request, "{}"));
        }
        case "slow" -> {
          try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
          respond(exchange, 200, "application/json", null, response(request, "{}"));
        }
        default -> respond(exchange, 500, "text/plain", null, "broken");
      }
    })) {
      var configuration = new McpConfigLoader.Server.Http("demo", endpoint(server), Map.of());
      var transport = new McpHttpTransport(configuration);
      transport.request("initialize", JSON.createObjectNode(), Duration.ofSeconds(5));
      assertThatThrownBy(() -> transport.request("rpc-error", JSON.createObjectNode(),
          Duration.ofSeconds(5))).isInstanceOf(McpTransportException.class)
          .hasMessage("authorize").satisfies(failure -> {
            var rpc = (McpTransportException) failure;
            assertThat(rpc.code()).isEqualTo(-32042);
            assertThat(rpc.data().path("url").asText()).isEqualTo("https://x");
          });
      assertThatThrownBy(() -> transport.request("empty", JSON.createObjectNode(),
          Duration.ofSeconds(5))).isInstanceOf(McpTransportException.class)
          .hasMessageContaining("empty HTTP response");
      assertThatThrownBy(() -> transport.request("expired", JSON.createObjectNode(),
          Duration.ofSeconds(5))).isInstanceOf(McpTransportException.class)
          .hasMessageContaining("410");
      transport.request("after-expired", JSON.createObjectNode(), Duration.ofSeconds(5));
      assertThat(calls).hasValue(1);
      assertThat(sessions.getLast()).isEqualTo("<none>");
      assertThatThrownBy(() -> transport.request("slow", JSON.createObjectNode(),
          Duration.ofMillis(25))).isInstanceOf(McpTransportException.class)
          .hasMessageContaining("timed out");
      transport.close();
      assertThat(transport.alive()).isFalse();
      assertThatThrownBy(() -> transport.notify("notification", JSON.createObjectNode()))
          .isInstanceOf(McpTransportException.class).hasMessageContaining("closed");
    }
  }

  @Test void rejectsUnsupportedUrlsAndOversizedResponses() throws Exception {
    assertThatThrownBy(() -> new McpHttpTransport(
        new McpConfigLoader.Server.Http("bad", "http://[", Map.of())))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("invalid");
    assertThatThrownBy(() -> new McpHttpTransport(
        new McpConfigLoader.Server.Http("bad", "file:///tmp/mcp", Map.of())))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("http");
    try (var server = server(exchange -> {
      JsonNode request = read(exchange);
      byte[] body = new byte[64 * 1024 * 1024 + 1];
      exchange.sendResponseHeaders(200, body.length);
      try { exchange.getResponseBody().write(body); } finally { exchange.close(); }
    })) {
      try (var transport = new McpHttpTransport(
          new McpConfigLoader.Server.Http("large", endpoint(server), Map.of()))) {
        assertThatThrownBy(() -> transport.request("large", JSON.createObjectNode(),
            Duration.ofSeconds(10))).isInstanceOf(McpTransportException.class)
            .hasMessageContaining("64 MiB");
      }
    }
  }

  @Test @SuppressWarnings("try") // explicit double close() verifies close() is idempotent.
  void handlesBatchesNotificationsMalformedJsonAndMissingResults() throws Exception {
    var session = new AtomicReference<>("fresh-session");
    try (var server = server(exchange -> {
      JsonNode request = read(exchange);
      String method = request.path("method").asText();
      switch (method) {
        case "batch" -> respond(exchange, 200, null, session.get(),
            "[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\","
                + "\"params\":{\"changed\":true}},{\"jsonrpc\":\"2.0\",\"id\":\"other\","
                + "\"result\":{}}," + response(request, "{\"ok\":true}") + "]");
        case "malformed" -> respond(exchange, 200, "application/json", null, "not-json");
        case "missing-result" -> respond(exchange, 200, "application/json", null,
            "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id") + "}");
        case "not-found" -> respond(exchange, 404, "text/plain", null, "missing");
        case "after" -> {
          session.set(null);
          respond(exchange, 200, "application/json", null, response(request, "{}"));
        }
        default -> respond(exchange, 202, null, null, "");
      }
    })) {
      var transport = new McpHttpTransport(
          new McpConfigLoader.Server.Http("branches", endpoint(server), Map.of()));
      try {
        var changed = new AtomicReference<JsonNode>();
        transport.onNotification((method, parameters) -> changed.set(parameters));
        var parameters = JSON.createObjectNode().put("present", true);
        assertThat(transport.request("batch", parameters, Duration.ofSeconds(5))
            .path("ok").asBoolean()).isTrue();
        assertThat(changed.get().path("changed").asBoolean()).isTrue();
        assertThatThrownBy(() -> transport.request("malformed", JSON.createObjectNode(),
            Duration.ofSeconds(5))).isInstanceOf(McpTransportException.class)
            .hasMessageContaining("invalid MCP HTTP JSON");
        assertThatThrownBy(() -> transport.request("missing-result", JSON.createObjectNode(),
            Duration.ofSeconds(5))).isInstanceOf(McpTransportException.class)
            .hasMessageContaining("neither result nor error");
        assertThatThrownBy(() -> transport.request("not-found", JSON.createObjectNode(),
            Duration.ofSeconds(5))).isInstanceOf(McpTransportException.class)
            .hasMessageContaining("404");
        transport.request("after", JSON.createObjectNode(), Duration.ofSeconds(5));
        transport.notify("empty-notification", JSON.createObjectNode().put("present", true));
        transport.close();
        transport.close();
      } finally {
        transport.close();
      }
    }
  }

  private static TestServer server(Handler handler) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/mcp", exchange -> handler.handle(exchange));
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    return new TestServer(server);
  }

  private static JsonNode read(HttpExchange exchange) throws IOException {
    try (var input = exchange.getRequestBody()) { return JSON.readTree(input); }
  }

  private static void respond(HttpExchange exchange, int status, String contentType,
                              String session, String body) throws IOException {
    if (contentType != null) exchange.getResponseHeaders().set("Content-Type", contentType);
    if (session != null) exchange.getResponseHeaders().set("Mcp-Session-Id", session);
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static String response(JsonNode request, String result) {
    return "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id")
        + ",\"result\":" + result + "}";
  }

  private static String error(JsonNode request, int code, String message, String data) {
    return "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id")
        + ",\"error\":{\"code\":" + code + ",\"message\":" + quote(message)
        + ",\"data\":" + data + "}}";
  }

  private static String quote(String value) {
    try { return JSON.writeValueAsString(value); }
    catch (Exception exception) { throw new AssertionError(exception); }
  }

  private static String endpoint(TestServer server) {
    return "http://127.0.0.1:" + server.server().getAddress().getPort() + "/mcp";
  }

  private record CapturedRequest(JsonNode body, String session, String version,
                                 String authorization, String accept, String userAgent) {}
  @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws IOException; }
  private record TestServer(HttpServer server) implements AutoCloseable {
    @Override public void close() { server.stop(0); }
  }
}
