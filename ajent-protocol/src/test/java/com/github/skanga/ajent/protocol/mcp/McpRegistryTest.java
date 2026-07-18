package com.github.skanga.ajent.protocol.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.tools.policy.Effect;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

final class McpRegistryTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test void namespacesOnlyCollisionsProjectsEffectsAndRendersRichCalls() {
    var alpha = new FakeTransport("alpha");
    alpha.tools.add(tool("ping", "alpha ping", true, false));
    alpha.tools.add(tool("inspect", "", true, false));
    var beta = new FakeTransport("beta");
    beta.tools.add(tool("ping", "beta ping", true, false));
    beta.tools.add(tool("mutate", "changes things", true, true));
    beta.tools.add(tool("empty", "empty", false, false));
    beta.tools.add(tool("array", "array", false, false));
    beta.tools.add(tool("fail-empty", "fail", false, false));
    beta.tools.add(tool("throw", "throw", false, false));
    try (var registry = new McpRegistry()) {
      registry.add("alpha", connected("alpha", alpha));
      registry.add("beta", connected("beta", beta));

      assertThat(registry.providerCount()).isEqualTo(2);
      assertThat(registry.tools()).extracting(tool -> tool.specification().name())
          .containsExactly("mcp:alpha__ping", "inspect", "mcp:beta__ping", "mutate",
              "empty", "array", "fail-empty", "throw");
      var inspect = registry.tools().stream()
          .filter(tool -> tool.specification().name().equals("inspect")).findFirst().orElseThrow();
      assertThat(inspect.effects().has(Effect.READ_FS)).isTrue();
      assertThat(inspect.effects().has(Effect.NET)).isTrue();
      assertThat(inspect.effects().has(Effect.EXEC)).isFalse();
      assertThat(inspect.specification().description()).contains("Remote MCP tool");
      assertThat(inspect.specification().inputSchema().path("properties").isObject()).isTrue();
      var mutate = registry.tools().stream()
          .filter(tool -> tool.specification().name().equals("mutate")).findFirst().orElseThrow();
      assertThat(mutate.effects().has(Effect.EXEC)).isTrue();
      assertThat(mutate.effects().has(Effect.WRITE_FS)).isTrue();

      ToolResult result = registry.execute("mcp:alpha__ping",
          JSON.createObjectNode().put("message", "hi"));
      assertThat(result).isInstanceOf(ToolResult.Success.class);
      String text = ((ToolResult.Success) result).output().text();
      assertThat(text).contains("alpha:hi", "[image image/png, ~4B base64]",
          "```json", "\"answer\" : 42");
      assertThat(registry.execute("ping", JSON.createObjectNode()))
          .isInstanceOf(ToolResult.Failure.class);
      assertThat(registry.execute("missing", JSON.createObjectNode()))
          .isInstanceOf(ToolResult.Failure.class);
      assertThat(successText(registry.execute("empty", JSON.createObjectNode())))
          .isEqualTo("(no output)");
      assertThat(successText(registry.execute("array", JSON.createObjectNode())))
          .contains("[audio, ~2B base64]", "```json", "1", "2");
      assertThat(registry.execute("fail-empty", JSON.createObjectNode()))
          .isInstanceOf(ToolResult.Failure.class)
          .satisfies(failure -> assertThat(((ToolResult.Failure) failure).error().detail())
              .isEqualTo("MCP tool reported an error"));
      assertThat(registry.execute("throw", JSON.createObjectNode()))
          .isInstanceOf(ToolResult.Failure.class)
          .satisfies(failure -> assertThat(((ToolResult.Failure) failure).error().detail())
              .contains("MCP call failed", "transport exploded"));
    }
    assertThat(alpha.closed).isTrue();
    assertThat(beta.closed).isTrue();
  }

  @Test void projectsResourcesPromptsAndTracksLiveListChanges() {
    var alpha = new FakeTransport("alpha");
    alpha.resources.add(object("uri", "mem://note", "name", "note", "title", "Note",
        "description", "A note", "mimeType", "text/plain"));
    alpha.templates.add(object("uriTemplate", "mem://{id}", "name", "memory"));
    alpha.prompts.add(prompt("summarize", "Summary", "Summarize text"));
    alpha.prompts.add(prompt("empty_prompt", "Empty", ""));
    var beta = new FakeTransport("beta");
    beta.prompts.add(prompt("summarize", "Other", "Other summary"));
    try (var registry = new McpRegistry()) {
      registry.add("alpha", connected("alpha", alpha));
      registry.add("beta", connected("beta", beta));
      assertThat(registry.tools()).extracting(tool -> tool.specification().name())
          .contains("mcp_read_resource", "mcp_get_prompt");
      assertThat(registry.resources()).singleElement().satisfies(resource -> {
        assertThat(resource.uri()).isEqualTo("mem://note");
        assertThat(resource.server()).isEqualTo("mcp:alpha");
      });
      assertThat(registry.prompts()).extracting(McpRegistry.PromptInfo::name)
          .containsExactly("mcp:alpha__summarize", "empty_prompt", "mcp:beta__summarize");

      ToolResult listing = registry.execute("mcp_read_resource", JSON.createObjectNode());
      assertThat(successText(listing)).contains("mem://note", "mem://{id}", "text/plain");
      assertThat(successText(registry.execute("mcp_read_resource",
          JSON.createObjectNode().put("uri", "ignored").put("list", true))))
          .contains("Available MCP resources");
      assertThat(successText(registry.execute("mcp_read_resource",
          JSON.createObjectNode().put("uri", "mem://note")))).isEqualTo("remote note body\n");
      assertThat(successText(registry.execute("mcp_get_prompt", JSON.createObjectNode())))
          .contains("mcp:alpha__summarize", "text (required)");
      assertThat(successText(registry.execute("mcp_get_prompt",
          JSON.createObjectNode().put("name", "ignored").put("list", true))))
          .contains("Available MCP prompts");
      ObjectNode promptArgs = JSON.createObjectNode().put("name", "mcp:alpha__summarize");
      promptArgs.putObject("arguments").put("text", "hello").put("count", 2);
      assertThat(successText(registry.execute("mcp_get_prompt", promptArgs)))
          .contains("# Summarize text", "user: hello:2");
      assertThat(successText(registry.execute("mcp_get_prompt",
          JSON.createObjectNode().put("name", "empty_prompt"))))
          .isEqualTo("(empty prompt)");

      long generation = registry.generation();
      alpha.tools.add(tool("new_tool", "new", false, false));
      alpha.fire("notifications/tools/list_changed");
      assertThat(registry.generation()).isGreaterThan(generation);
      assertThat(registry.tools()).extracting(tool -> tool.specification().name())
          .contains("new_tool");
    }
  }

  @Test void rejectsUseAfterCloseAndMapsRemoteToolErrors() {
    var remote = new FakeTransport("error");
    remote.tools.add(tool("fail", "fails", false, false));
    var registry = new McpRegistry();
    registry.add("error", connected("error", remote));
    assertThat(registry.execute("fail", JSON.createObjectNode()))
        .isInstanceOf(ToolResult.Failure.class)
        .satisfies(result -> assertThat(((ToolResult.Failure) result).error().detail())
            .contains("remote failure"));
    registry.close();
    registry.close();
    assertThatThrownBy(registry::tools).isInstanceOf(IllegalStateException.class);
    var late = new McpClientSession("late", new FakeTransport("late"), Duration.ofSeconds(2), "test");
    assertThatThrownBy(() -> registry.add("late", late))
        .isInstanceOf(IllegalStateException.class);
    try (var empty = new McpRegistry()) {
      assertThatThrownBy(() -> empty.readResource("missing://resource"))
          .isInstanceOf(McpTransportException.class).hasMessageContaining("not found");
    }
  }

  private static McpClientSession connected(String name, FakeTransport transport) {
    var session = new McpClientSession(name, transport, Duration.ofSeconds(2), "test");
    session.connect();
    return session;
  }

  private static ObjectNode tool(String name, String description,
                                 boolean readOnly, boolean destructive) {
    ObjectNode tool = object("name", name, "description", description);
    tool.putObject("inputSchema").put("type", "object");
    ObjectNode annotations = tool.putObject("annotations");
    annotations.put("readOnlyHint", readOnly); annotations.put("destructiveHint", destructive);
    return tool;
  }

  private static ObjectNode prompt(String name, String title, String description) {
    ObjectNode prompt = object("name", name, "title", title, "description", description);
    ObjectNode argument = JSON.createObjectNode();
    argument.put("name", "text"); argument.put("description", "input"); argument.put("required", true);
    prompt.putArray("arguments").add(argument);
    return prompt;
  }

  private static ObjectNode object(String... pairs) {
    ObjectNode result = JSON.createObjectNode();
    for (int index = 0; index < pairs.length; index += 2) result.put(pairs[index], pairs[index + 1]);
    return result;
  }

  private static String successText(ToolResult result) {
    assertThat(result).isInstanceOf(ToolResult.Success.class);
    return ((ToolResult.Success) result).output().text();
  }

  private static final class FakeTransport implements McpClientSession.Transport {
    private final String name;
    private final ArrayNode tools = JSON.createArrayNode();
    private final ArrayNode resources = JSON.createArrayNode();
    private final ArrayNode templates = JSON.createArrayNode();
    private final ArrayNode prompts = JSON.createArrayNode();
    private final AtomicBoolean closed = new AtomicBoolean();
    private BiConsumer<String, JsonNode> notifications = (method, parameters) -> {};

    private FakeTransport(String name) { this.name = name; }

    @Override public JsonNode request(String method, ObjectNode parameters, Duration timeout) {
      return switch (method) {
        case "initialize" -> object("protocolVersion", "2025-11-25")
            .set("serverInfo", object("name", name));
        case "tools/list" -> JSON.createObjectNode().set("tools", tools.deepCopy());
        case "resources/list" -> JSON.createObjectNode().set("resources", resources.deepCopy());
        case "resources/templates/list" -> JSON.createObjectNode()
            .set("resourceTemplates", templates.deepCopy());
        case "prompts/list" -> JSON.createObjectNode().set("prompts", prompts.deepCopy());
        case "resources/read" -> JSON.createObjectNode().set("contents", JSON.createArrayNode()
            .add(object("uri", parameters.path("uri").asText(), "text", "remote note body")));
        case "prompts/get" -> promptResult(parameters);
        case "tools/call" -> call(parameters);
        default -> throw new McpTransportException(-32601, "unsupported: " + method);
      };
    }

    private JsonNode call(ObjectNode parameters) {
      String toolName = parameters.path("name").asText();
      if ("throw".equals(toolName)) throw new McpTransportException(-32003, "transport exploded");
      if ("fail-empty".equals(toolName)) {
        ObjectNode failed = JSON.createObjectNode().put("isError", true);
        failed.putArray("content"); return failed;
      }
      if ("fail".equals(toolName)) return JSON.createObjectNode()
          .put("isError", true).set("content", JSON.createArrayNode()
              .add(object("type", "text", "text", "remote failure")));
      if ("empty".equals(toolName)) {
        ObjectNode empty = JSON.createObjectNode(); empty.putArray("content"); return empty;
      }
      if ("array".equals(toolName)) {
        ObjectNode array = JSON.createObjectNode();
        array.set("content", JSON.createArrayNode()
            .add(object("type", "audio", "mimeType", "", "data", "AA")));
        array.set("structuredContent", JSON.createArrayNode().add(1).add(2));
        return array;
      }
      ObjectNode result = JSON.createObjectNode();
      result.set("content", JSON.createArrayNode()
          .add(object("type", "text", "text", name + ":"
              + parameters.path("arguments").path("message").asText()))
          .add(object("type", "image", "mimeType", "image/png", "data", "AAAA")));
      result.set("structuredContent", JSON.createObjectNode().put("answer", 42));
      return result;
    }

    private JsonNode promptResult(ObjectNode parameters) {
      if ("empty_prompt".equals(parameters.path("name").asText())) {
        ObjectNode empty = JSON.createObjectNode(); empty.putArray("messages"); return empty;
      }
      return JSON.createObjectNode().put("description", "Summarize text")
          .set("messages", JSON.createArrayNode().add(JSON.createObjectNode().put("role", "user")
              .set("content", JSON.createObjectNode().put("type", "text").put("text",
                  parameters.path("arguments").path("text").asText() + ":"
                      + parameters.path("arguments").path("count").asText()))));
    }

    private void fire(String method) { notifications.accept(method, JSON.createObjectNode()); }
    @Override public void notify(String method, ObjectNode parameters) {}
    @Override public void onNotification(BiConsumer<String, JsonNode> handler) { notifications = handler; }
    @Override public boolean alive() { return !closed.get(); }
    @Override public void close() { closed.set(true); }
  }
}
