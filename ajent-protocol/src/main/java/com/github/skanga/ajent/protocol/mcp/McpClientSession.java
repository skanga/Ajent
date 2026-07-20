package com.github.skanga.ajent.protocol.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/** Transport-neutral MCP client session with paginated, live discovery snapshots. */
public final class McpClientSession implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_PAGES = 10_000;

  public interface Transport extends AutoCloseable {
    JsonNode request(String method, ObjectNode parameters, Duration timeout);
    void notify(String method, ObjectNode parameters);
    void onNotification(BiConsumer<String, JsonNode> handler);
    boolean alive();
    @Override void close();
  }

  public record RemoteTool(String name, String description, JsonNode inputSchema,
                           boolean readOnly, boolean destructive, boolean openWorld) {
    public RemoteTool {
      inputSchema = inputSchema.deepCopy();
    }
    @Override public JsonNode inputSchema() { return inputSchema.deepCopy(); }
  }
  public record Resource(String uri, String name, String title, String description,
                         String mimeType) {}
  public record ResourceTemplate(String uriTemplate, String name, String title,
                                 String description, String mimeType) {}
  public record PromptArgument(String name, String description, boolean required) {}
  public record Prompt(String name, String title, String description,
                       List<PromptArgument> arguments) {
    public Prompt { arguments = List.copyOf(arguments); }
  }
  public record CallResult(String text, JsonNode raw, JsonNode structured, boolean error) {
    public CallResult {
      raw = raw.deepCopy(); structured = structured.deepCopy();
    }
  }

  private final String configuredName;
  private final Transport transport;
  private final Duration timeout;
  private final Duration connectTimeout;
  private final String version;
  private final AtomicLong generation = new AtomicLong();
  private volatile List<RemoteTool> tools = List.of();
  private volatile List<Resource> resources = List.of();
  private volatile List<ResourceTemplate> templates = List.of();
  private volatile List<Prompt> prompts = List.of();
  private volatile String protocolVersion = "";
  private volatile String serverName = "";
  private volatile Runnable listChanged = () -> {};

  public McpClientSession(
      String configuredName, Transport transport, Duration timeout, String version) {
    this(configuredName, transport, timeout, timeout, version);
  }

  public McpClientSession(String configuredName, Transport transport, Duration timeout,
                          Duration connectTimeout, String version) {
    this.configuredName = Objects.requireNonNull(configuredName, "configuredName");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
    this.version = Objects.requireNonNull(version, "version");
  }

  public synchronized void connect() {
    transport.onNotification(this::notification);
    ObjectNode parameters = JSON.createObjectNode();
    parameters.put("protocolVersion", "2025-11-25");
    parameters.putObject("capabilities");
    ObjectNode info = parameters.putObject("clientInfo");
    info.put("name", "ajent"); info.put("version", version);
    JsonNode initialized = transport.request("initialize", parameters, connectTimeout);
    protocolVersion = initialized.path("protocolVersion").asText("2025-11-25");
    serverName = initialized.path("serverInfo").path("name").asText(configuredName);
    transport.notify("notifications/initialized", JSON.createObjectNode());
    refreshTools(); refreshResources(); refreshPrompts();
  }

  public String protocolVersion() { return protocolVersion; }
  public String serverName() { return serverName; }
  public long generation() { return generation.get(); }
  public List<RemoteTool> tools() { return tools; }
  public List<Resource> resources() { return resources; }
  public List<ResourceTemplate> resourceTemplates() { return templates; }
  public List<Prompt> prompts() { return prompts; }
  public boolean alive() { return transport.alive(); }
  public void onListChanged(Runnable handler) {
    listChanged = Objects.requireNonNull(handler, "handler");
  }

  public synchronized CallResult call(String name, ObjectNode arguments) {
    ObjectNode parameters = JSON.createObjectNode();
    parameters.put("name", name); parameters.set("arguments", arguments);
    JsonNode result = transport.request("tools/call", parameters, timeout);
    JsonNode content = result.path("content");
    var text = new StringBuilder();
    if (content.isArray()) for (JsonNode block : content) {
      if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText());
      else {
        String type = block.path("type").asText("content");
        text.append('[').append(type).append(']');
        if (block.path("uri").isTextual()) text.append(' ').append(block.path("uri").textValue());
      }
      if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') text.append('\n');
    }
    JsonNode structured = result.has("structuredContent")
        ? result.path("structuredContent") : JSON.createObjectNode();
    return new CallResult(text.toString(), content, structured, result.path("isError").asBoolean());
  }

  public synchronized String readResource(String uri) {
    ObjectNode parameters = JSON.createObjectNode(); parameters.put("uri", uri);
    JsonNode result = transport.request("resources/read", parameters, timeout);
    var output = new StringBuilder();
    for (JsonNode content : result.path("contents")) {
      if (content.path("text").isTextual()) output.append(content.path("text").textValue());
      else {
        String mime = content.path("mimeType").asText("application/octet-stream");
        output.append("[blob ").append(mime).append(", ~")
            .append(content.path("blob").asText().length()).append("B base64]");
      }
      if (!output.isEmpty() && output.charAt(output.length() - 1) != '\n') output.append('\n');
    }
    return output.toString();
  }

  public synchronized String getPrompt(String name, Map<String, String> arguments) {
    ObjectNode parameters = JSON.createObjectNode(); parameters.put("name", name);
    if (!arguments.isEmpty()) parameters.set("arguments", JSON.valueToTree(arguments));
    JsonNode result = transport.request("prompts/get", parameters, timeout);
    var output = new StringBuilder();
    if (result.path("description").isTextual())
      output.append("# ").append(result.path("description").textValue()).append("\n\n");
    for (JsonNode message : result.path("messages")) {
      JsonNode content = message.path("content");
      String body = "text".equals(content.path("type").asText())
          ? content.path("text").asText() : content.toString();
      output.append(message.path("role").asText()).append(": ").append(body).append("\n\n");
    }
    return output.toString();
  }

  private void notification(String method, JsonNode ignored) {
    if (!isListChanged(method)) return;
    synchronized (this) {
      refreshFromNotification(method);
    }
  }

  private void refreshFromNotification(String method) {
    try {
      switch (method) {
        case "notifications/tools/list_changed" -> refreshTools();
        case "notifications/resources/list_changed" -> refreshResources();
        case "notifications/prompts/list_changed" -> refreshPrompts();
        default -> throw new IllegalArgumentException("not a list-changed notification: " + method);
      }
      generation.incrementAndGet();
      listChanged.run();
    } catch (RuntimeException ignoredFailure) {
      // Keep the previous known-good snapshot.
    }
  }

  private static boolean isListChanged(String method) {
    return switch (method) {
      case "notifications/tools/list_changed", "notifications/resources/list_changed",
           "notifications/prompts/list_changed" -> true;
      default -> false;
    };
  }

  private void refreshTools() {
    List<JsonNode> values = pages("tools/list", "tools");
    var refreshed = new ArrayList<RemoteTool>();
    for (JsonNode value : values) {
      JsonNode annotations = value.path("annotations");
      refreshed.add(new RemoteTool(value.path("name").asText(),
          value.path("description").asText(), objectSchema(value.path("inputSchema")),
          annotations.path("readOnlyHint").asBoolean(),
          annotations.path("destructiveHint").asBoolean(),
          annotations.path("openWorldHint").asBoolean()));
    }
    tools = List.copyOf(refreshed);
  }

  private void refreshResources() {
    var listed = new ArrayList<Resource>();
    for (JsonNode value : pages("resources/list", "resources")) listed.add(new Resource(
        value.path("uri").asText(), value.path("name").asText(),
        value.path("title").asText(value.path("name").asText()),
        value.path("description").asText(), value.path("mimeType").asText()));
    resources = List.copyOf(listed);
    try {
      var found = new ArrayList<ResourceTemplate>();
      for (JsonNode value : pages("resources/templates/list", "resourceTemplates"))
        found.add(new ResourceTemplate(value.path("uriTemplate").asText(),
            value.path("name").asText(), value.path("title").asText(value.path("name").asText()),
            value.path("description").asText(), value.path("mimeType").asText()));
      templates = List.copyOf(found);
    } catch (RuntimeException ignored) { templates = List.of(); }
  }

  private void refreshPrompts() {
    var listed = new ArrayList<Prompt>();
    for (JsonNode value : pages("prompts/list", "prompts")) {
      var arguments = new ArrayList<PromptArgument>();
      for (JsonNode argument : value.path("arguments")) arguments.add(new PromptArgument(
          argument.path("name").asText(), argument.path("description").asText(),
          argument.path("required").asBoolean()));
      listed.add(new Prompt(value.path("name").asText(),
          value.path("title").asText(value.path("name").asText()),
          value.path("description").asText(), arguments));
    }
    prompts = List.copyOf(listed);
  }

  private List<JsonNode> pages(String method, String field) {
    var values = new ArrayList<JsonNode>();
    String cursor = "";
    for (int page = 0; page < MAX_PAGES; page++) {
      ObjectNode parameters = JSON.createObjectNode();
      if (!cursor.isEmpty()) parameters.put("cursor", cursor);
      JsonNode result = transport.request(method, parameters, timeout);
      result.path(field).forEach(values::add);
      cursor = result.path("nextCursor").asText();
      if (cursor.isEmpty()) return values;
    }
    throw new IllegalStateException("MCP pagination exceeded " + MAX_PAGES + " pages");
  }

  private static JsonNode objectSchema(JsonNode value) {
    if (value.isObject()) return value;
    return JSON.createObjectNode().put("type", "object");
  }

  @Override public void close() { transport.close(); }
}
