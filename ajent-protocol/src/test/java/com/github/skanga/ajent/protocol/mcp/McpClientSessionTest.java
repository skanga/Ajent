package com.github.skanga.ajent.protocol.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

final class McpClientSessionTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test void handshakesPaginatesDiscoversCallsAndRefreshesOnNotifications() throws Exception {
    var transport = new FakeTransport();
    try (var session = new McpClientSession("demo", transport, Duration.ofSeconds(2), "test")) {
      session.connect();

      assertThat(transport.methods).startsWith("initialize", "notifications/initialized",
          "tools/list", "tools/list", "resources/list", "resources/templates/list",
          "prompts/list");
      assertThat(session.serverName()).isEqualTo("remote");
      assertThat(session.protocolVersion()).isEqualTo("2025-11-25");
      assertThat(session.tools()).extracting(McpClientSession.RemoteTool::name)
          .containsExactly("first", "second");
      assertThat(session.resources()).singleElement().satisfies(resource -> {
        assertThat(resource.uri()).isEqualTo("mem://note");
        assertThat(resource.title()).isEqualTo("Note");
      });
      assertThat(session.resourceTemplates()).singleElement().satisfies(template ->
          assertThat(template.uriTemplate()).isEqualTo("mem://{id}"));
      assertThat(session.prompts()).singleElement().satisfies(prompt -> {
        assertThat(prompt.name()).isEqualTo("greet");
        assertThat(prompt.arguments()).singleElement().satisfies(argument ->
            assertThat(argument.required()).isTrue());
      });

      McpClientSession.CallResult called = session.call("first",
          (ObjectNode) JSON.readTree("{\"value\":7}"));
      assertThat(called.text()).isEqualTo("seven\n[image]\n");
      assertThat(called.structured()).isEqualTo(JSON.readTree("{\"value\":7}"));
      assertThat(called.error()).isFalse();
      assertThat(session.readResource("mem://note")).isEqualTo("body\n[blob image/png, ~8B base64]\n");
      assertThat(session.getPrompt("greet", java.util.Map.of("name", "Ada")))
          .isEqualTo("# Greeting\n\nuser: Hello Ada\n\n");

      long generation = session.generation();
      transport.toolsChanged = true;
      transport.emit("notifications/tools/list_changed");
      assertThat(session.generation()).isEqualTo(generation + 1);
      assertThat(session.tools()).extracting(McpClientSession.RemoteTool::name)
          .containsExactly("changed");
    }
    assertThat(transport.closed).isTrue();
  }

  @Test void progressNotificationCannotBlockTheResponseReaderDuringAnActiveCall() {
    var transport = new FakeTransport();
    transport.progressBeforeCallResult = true;
    try (var session = new McpClientSession("demo", transport, Duration.ofSeconds(2), "test")) {
      session.connect();

      McpClientSession.CallResult called = session.call(
          "first", JSON.createObjectNode().put("value", 7));

      assertThat(called.text()).isEqualTo("seven\n[image]\n");
      assertThat(transport.progressDelivered).isTrue();
    }
  }

  private static final class FakeTransport implements McpClientSession.Transport {
    private final List<String> methods = new ArrayList<>();
    private BiConsumer<String, JsonNode> notifications = (method, params) -> {};
    private boolean toolsChanged;
    private boolean progressBeforeCallResult;
    private boolean progressDelivered;
    private boolean closed;

    @Override public JsonNode request(String method, ObjectNode parameters, Duration timeout) {
      methods.add(method);
      return switch (method) {
        case "initialize" -> json("""
            {"protocolVersion":"2025-11-25","capabilities":{
              "tools":{"listChanged":true},"resources":{"listChanged":true},
              "prompts":{"listChanged":true}},
             "serverInfo":{"name":"remote","version":"1"}}
            """);
        case "tools/list" -> tools(parameters);
        case "resources/list" -> json("""
            {"resources":[{"uri":"mem://note","name":"note","title":"Note",
              "mimeType":"text/plain"}]}
            """);
        case "resources/templates/list" -> json("""
            {"resourceTemplates":[{"uriTemplate":"mem://{id}","name":"item"}]}
            """);
        case "prompts/list" -> json("""
            {"prompts":[{"name":"greet","description":"Greeting",
              "arguments":[{"name":"name","required":true}]}]}
            """);
        case "tools/call" -> callResult();
        case "resources/read" -> json("""
            {"contents":[{"uri":"mem://note","text":"body"},
              {"uri":"mem://note","blob":"abcdefgh","mimeType":"image/png"}]}
            """);
        case "prompts/get" -> json("""
            {"description":"Greeting","messages":[{"role":"user",
              "content":{"type":"text","text":"Hello Ada"}}]}
            """);
        default -> throw new AssertionError(method);
      };
    }

    private JsonNode callResult() {
      if (progressBeforeCallResult) {
        java.lang.Thread notification = java.lang.Thread.ofVirtual().start(() -> {
          emit("notifications/progress");
          progressDelivered = true;
        });
        try {
          notification.join(Duration.ofMillis(250));
        } catch (InterruptedException exception) {
          java.lang.Thread.currentThread().interrupt();
          throw new AssertionError(exception);
        }
        if (notification.isAlive()) {
          throw new AssertionError("progress notification blocked the response reader");
        }
      }
      return json("""
          {"content":[{"type":"text","text":"seven"},{"type":"image",
            "mimeType":"image/png","data":"abcdefgh"}],
           "structuredContent":{"value":7}}
          """);
    }

    private JsonNode tools(ObjectNode parameters) {
      if (toolsChanged) return json("""
          {"tools":[{"name":"changed","inputSchema":{"type":"object"}}]}
          """);
      if (!parameters.has("cursor")) return json("""
          {"tools":[{"name":"first","description":"First",
            "inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}}],
           "nextCursor":"page-2"}
          """);
      return json("""
          {"tools":[{"name":"second","inputSchema":{"type":"object"}}]}
          """);
    }

    @Override public void notify(String method, ObjectNode parameters) {
      methods.add(method);
    }

    @Override public void onNotification(BiConsumer<String, JsonNode> handler) {
      notifications = handler;
    }

    private void emit(String method) {
      notifications.accept(method, JSON.createObjectNode());
    }

    @Override public boolean alive() {
      return !closed;
    }

    @Override public void close() {
      closed = true;
    }

    private static JsonNode json(String value) {
      try {
        return JSON.readTree(value);
      } catch (java.io.IOException exception) {
        throw new AssertionError(exception);
      }
    }
  }
}
