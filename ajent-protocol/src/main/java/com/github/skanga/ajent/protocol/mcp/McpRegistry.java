package com.github.skanga.ajent.protocol.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.provider.ToolSpecification;
import com.github.skanga.ajent.tools.policy.Effect;
import com.github.skanga.ajent.tools.policy.EffectSet;
import com.github.skanga.ajent.tools.runtime.ToolError;
import com.github.skanga.ajent.tools.runtime.ToolErrorKind;
import com.github.skanga.ajent.tools.runtime.ToolOutput;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Live fan-in and Ajent tool projection for connected MCP sessions. */
public final class McpRegistry implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final EffectSet READ_EFFECTS = EffectSet.of(Effect.READ_FS, Effect.NET);
  private static final EffectSet FULL_EFFECTS = EffectSet.of(
      Effect.EXEC, Effect.WRITE_FS, Effect.NET, Effect.READ_FS);
  private static final String READ_RESOURCE = "mcp_read_resource";
  private static final String GET_PROMPT = "mcp_get_prompt";

  public record ProjectedTool(ToolSpecification specification, EffectSet effects) {
    public ProjectedTool {
      specification = Objects.requireNonNull(specification, "specification");
      effects = Objects.requireNonNull(effects, "effects");
    }
  }
  public record ResourceInfo(String uri, String name, String title, String description,
                             String mimeType, String server) {}
  public record PromptInfo(String name, String title, String description,
                           List<McpClientSession.PromptArgument> arguments, String server) {
    public PromptInfo { arguments = List.copyOf(arguments); }
  }

  private record Entry(String origin, McpClientSession session) {}
  private record ToolRoute(Entry entry, McpClientSession.RemoteTool tool) {}
  private record PromptRoute(Entry entry, McpClientSession.Prompt prompt) {}

  private final List<Entry> entries = new ArrayList<>();
  private final AtomicLong generation = new AtomicLong();
  private final AtomicBoolean closed = new AtomicBoolean();

  public synchronized void add(String configuredName, McpClientSession session) {
    requireOpen();
    Objects.requireNonNull(configuredName, "configuredName");
    Objects.requireNonNull(session, "session");
    session.onListChanged(generation::incrementAndGet);
    entries.add(new Entry("mcp:" + configuredName, session));
    generation.incrementAndGet();
  }

  public synchronized int providerCount() { return entries.size(); }
  public long generation() { return generation.get(); }

  public synchronized List<ProjectedTool> tools() {
    requireOpen();
    var routes = toolRoutes();
    var result = new ArrayList<ProjectedTool>();
    routes.forEach((exposed, route) -> result.add(project(exposed, route.tool())));
    if (hasResources()) result.add(resourceTool());
    if (hasPrompts()) result.add(promptTool());
    return List.copyOf(result);
  }

  public synchronized ToolResult execute(String exposedName, ObjectNode arguments) {
    requireOpen();
    Objects.requireNonNull(exposedName, "exposedName");
    Objects.requireNonNull(arguments, "arguments");
    try {
      if (READ_RESOURCE.equals(exposedName)) return executeResource(arguments);
      if (GET_PROMPT.equals(exposedName)) return executePrompt(arguments);
      ToolRoute route = toolRoutes().get(exposedName);
      if (route == null) return failure("capability not found: '" + exposedName + "'");
      McpClientSession.CallResult called = route.entry().session().call(
          route.tool().name(), arguments);
      if (called.error()) return failure(called.text().isEmpty()
          ? "MCP tool reported an error" : called.text());
      String rendered = render(called);
      return success(rendered.isEmpty() ? "(no output)" : rendered);
    } catch (RuntimeException exception) {
      return failure("MCP call failed: " + message(exception));
    }
  }

  public synchronized List<ResourceInfo> resources() {
    requireOpen();
    var result = new ArrayList<ResourceInfo>();
    for (Entry entry : entries) for (var resource : entry.session().resources()) {
      result.add(new ResourceInfo(resource.uri(), resource.name(), resource.title(),
          resource.description(), resource.mimeType(), entry.origin()));
    }
    return List.copyOf(result);
  }

  public synchronized String readResource(String uri) {
    requireOpen();
    for (Entry entry : entries) for (var resource : entry.session().resources()) {
      if (resource.uri().equals(uri)) return entry.session().readResource(uri);
    }
    RuntimeException last = null;
    for (Entry entry : entries) try {
      return entry.session().readResource(uri);
    } catch (RuntimeException exception) {
      last = exception;
    }
    if (last != null) throw last;
    throw new McpTransportException(-32602, "resource not found: '" + uri + "'");
  }

  public synchronized List<PromptInfo> prompts() {
    requireOpen();
    var result = new ArrayList<PromptInfo>();
    promptRoutes().forEach((name, route) -> result.add(new PromptInfo(name,
        route.prompt().title(), route.prompt().description(), route.prompt().arguments(),
        route.entry().origin())));
    return List.copyOf(result);
  }

  public synchronized String getPrompt(String exposedName, Map<String, String> arguments) {
    requireOpen();
    PromptRoute route = promptRoutes().get(exposedName);
    if (route == null) throw new McpTransportException(
        -32602, "prompt not found: '" + exposedName + "'");
    return route.entry().session().getPrompt(route.prompt().name(), arguments);
  }

  private LinkedHashMap<String, ToolRoute> toolRoutes() {
    var counts = new HashMap<String, Integer>();
    for (Entry entry : entries) for (var tool : entry.session().tools())
      counts.merge(tool.name(), 1, Integer::sum);
    var routes = new LinkedHashMap<String, ToolRoute>();
    for (Entry entry : entries) for (var tool : entry.session().tools()) {
      String exposed = counts.get(tool.name()) > 1
          ? entry.origin() + "__" + tool.name() : tool.name();
      routes.putIfAbsent(exposed, new ToolRoute(entry, tool));
    }
    return routes;
  }

  private LinkedHashMap<String, PromptRoute> promptRoutes() {
    var counts = new HashMap<String, Integer>();
    for (Entry entry : entries) for (var prompt : entry.session().prompts())
      counts.merge(prompt.name(), 1, Integer::sum);
    var routes = new LinkedHashMap<String, PromptRoute>();
    for (Entry entry : entries) for (var prompt : entry.session().prompts()) {
      String exposed = counts.get(prompt.name()) > 1
          ? entry.origin() + "__" + prompt.name() : prompt.name();
      routes.putIfAbsent(exposed, new PromptRoute(entry, prompt));
    }
    return routes;
  }

  private static ProjectedTool project(String name, McpClientSession.RemoteTool tool) {
    ObjectNode schema = tool.inputSchema().isObject()
        ? (ObjectNode) tool.inputSchema() : JSON.createObjectNode();
    if (!schema.has("type")) schema.put("type", "object");
    if (!schema.has("properties")) schema.putObject("properties");
    String description = "[MCP] " + (tool.description().isEmpty()
        ? "Remote MCP tool '" + name + "'." : tool.description());
    EffectSet effects = tool.readOnly() && !tool.destructive() ? READ_EFFECTS : FULL_EFFECTS;
    return new ProjectedTool(new ToolSpecification(name, description, schema, false), effects);
  }

  private ProjectedTool resourceTool() {
    ObjectNode schema = JSON.createObjectNode(); schema.put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    properties.putObject("uri").put("type", "string").put("description",
        "Resource URI to read. Omit to list all resources.");
    properties.putObject("list").put("type", "boolean").put("description",
        "List all available resources instead of reading.");
    return new ProjectedTool(new ToolSpecification(READ_RESOURCE,
        "[MCP] Read the contents of an MCP resource by URI. Call with no args to list.",
        schema, false), READ_EFFECTS);
  }

  private ProjectedTool promptTool() {
    ObjectNode schema = JSON.createObjectNode(); schema.put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    properties.putObject("name").put("type", "string");
    properties.putObject("arguments").put("type", "object");
    properties.putObject("list").put("type", "boolean");
    return new ProjectedTool(new ToolSpecification(GET_PROMPT,
        "[MCP] Render an MCP prompt template. Call with no args to list.", schema, false),
        READ_EFFECTS);
  }

  private ToolResult executeResource(ObjectNode arguments) {
    String uri = arguments.path("uri").asText();
    if (uri.isEmpty() || arguments.path("list").asBoolean()) {
      var listed = resources();
      var templates = new ArrayList<Map.Entry<Entry, McpClientSession.ResourceTemplate>>();
      for (Entry entry : entries) for (var template : entry.session().resourceTemplates())
        templates.add(Map.entry(entry, template));
      if (listed.isEmpty() && templates.isEmpty()) return success("(no resources advertised)");
      var output = new StringBuilder("Available MCP resources:\n");
      for (ResourceInfo resource : listed) {
        output.append("  ").append(resource.uri());
        String title = resource.title().isEmpty() ? resource.name() : resource.title();
        if (!title.isEmpty()) output.append("  — ").append(title);
        if (!resource.mimeType().isEmpty()) output.append(" [").append(resource.mimeType()).append(']');
        output.append('\n');
      }
      for (var owned : templates) {
        var template = owned.getValue();
        output.append("  ").append(template.uriTemplate()).append("  (template");
        if (!template.name().isEmpty()) output.append(": ").append(template.name());
        output.append(")\n");
      }
      return success(output.toString());
    }
    String output = readResource(uri);
    return success(output.isEmpty() ? "(empty resource)" : output);
  }

  private ToolResult executePrompt(ObjectNode arguments) {
    String name = arguments.path("name").asText();
    if (name.isEmpty() || arguments.path("list").asBoolean()) {
      var listed = prompts();
      if (listed.isEmpty()) return success("(no prompts advertised)");
      var output = new StringBuilder("Available MCP prompts:\n");
      for (PromptInfo prompt : listed) {
        output.append("  ").append(prompt.name());
        if (!prompt.description().isEmpty()) output.append("  — ").append(prompt.description());
        output.append('\n');
        for (var argument : prompt.arguments()) {
          output.append("      - ").append(argument.name());
          if (argument.required()) output.append(" (required)");
          if (!argument.description().isEmpty()) output.append(": ").append(argument.description());
          output.append('\n');
        }
      }
      return success(output.toString());
    }
    var values = new LinkedHashMap<String, String>();
    JsonNode supplied = arguments.path("arguments");
    if (supplied.isObject()) supplied.properties().forEach(entry -> values.put(entry.getKey(),
        entry.getValue().isTextual() ? entry.getValue().textValue() : entry.getValue().toString()));
    String output = getPrompt(name, values);
    return success(output.isEmpty() ? "(empty prompt)" : output);
  }

  private boolean hasResources() {
    for (Entry entry : entries) if (!entry.session().resources().isEmpty()
        || !entry.session().resourceTemplates().isEmpty()) return true;
    return false;
  }

  private boolean hasPrompts() {
    for (Entry entry : entries) if (!entry.session().prompts().isEmpty()) return true;
    return false;
  }

  private static String render(McpClientSession.CallResult result) {
    var output = new StringBuilder(result.text());
    if (result.raw().isArray()) for (JsonNode block : result.raw()) {
      String type = block.path("type").asText();
      if (!("image".equals(type) || "audio".equals(type))) continue;
      if (!output.isEmpty() && output.charAt(output.length() - 1) != '\n') output.append('\n');
      output.append('[').append(type);
      String mime = block.path("mimeType").asText();
      if (!mime.isEmpty()) output.append(' ').append(mime);
      output.append(", ~").append(block.path("data").asText().length()).append("B base64]\n");
    }
    JsonNode structured = result.structured();
    if (!structured.isNull() && !(structured.isObject() && structured.isEmpty())) {
      if (!output.isEmpty() && output.charAt(output.length() - 1) != '\n') output.append('\n');
      try {
        output.append("```json\n").append(JSON.writerWithDefaultPrettyPrinter()
            .writeValueAsString(structured)).append("\n```\n");
      } catch (JsonProcessingException exception) {
        output.append("```json\n").append(structured).append("\n```\n");
      }
    }
    return output.toString();
  }

  private static ToolResult success(String text) {
    return new ToolResult.Success(new ToolOutput(text));
  }
  private static ToolResult failure(String detail) {
    return new ToolResult.Failure(new ToolError(ToolErrorKind.SUBPROCESS, detail));
  }
  private static String message(RuntimeException exception) {
    return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
  }
  private void requireOpen() {
    if (closed.get()) throw new IllegalStateException("MCP registry is closed");
  }

  @Override public synchronized void close() {
    if (!closed.compareAndSet(false, true)) return;
    entries.forEach(entry -> entry.session().close());
    entries.clear();
  }
}
