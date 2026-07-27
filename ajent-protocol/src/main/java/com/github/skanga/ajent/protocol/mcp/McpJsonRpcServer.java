package com.github.skanga.ajent.protocol.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.provider.ToolSpecification;
import com.github.skanga.ajent.tools.policy.Effect;
import com.github.skanga.ajent.tools.policy.EffectSet;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** MCP stdio server exposing the same injected tool catalog used by the agent runtime. */
public final class McpJsonRpcServer {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int PARSE_ERROR = -32700;
  private static final int INVALID_REQUEST = -32600;
  private static final int METHOD_NOT_FOUND = -32601;
  private static final int INVALID_PARAMS = -32602;
  private static final int INTERNAL_ERROR = -32603;
  private static final String INSTRUCTIONS =
      "ajent's native coding tools served over MCP: file read/edit/write, shell (bash), "
          + "code search (grep/glob/find_definition), web fetch/search, diagnostics, and git. "
          + "Filesystem tools are sandboxed to the workspace the ajent process was launched in.";

  public record PublishedTool(ToolSpecification specification, EffectSet effects) {
    public PublishedTool {
      specification = Objects.requireNonNull(specification, "specification");
      effects = Objects.requireNonNull(effects, "effects");
    }
  }

  @FunctionalInterface
  public interface ToolExecutor {
    ToolResult execute(String name, JsonNode arguments);
  }

  private final List<PublishedTool> tools;
  private final Map<String, PublishedTool> byName;
  private final ToolExecutor executor;
  private final String version;

  public McpJsonRpcServer(
      List<PublishedTool> tools, ToolExecutor executor, String version) {
    this.tools = List.copyOf(tools);
    this.executor = Objects.requireNonNull(executor, "executor");
    this.version = Objects.requireNonNull(version, "version");
    var indexed = new LinkedHashMap<String, PublishedTool>();
    for (PublishedTool tool : this.tools) {
      String name = tool.specification().name();
      if (indexed.putIfAbsent(name, tool) != null) {
        throw new IllegalArgumentException("duplicate MCP tool: " + name);
      }
    }
    byName = Map.copyOf(indexed);
  }

  public List<String> handleLine(String line) {
    JsonNode request;
    try {
      request = JSON.readTree(line);
    } catch (JsonProcessingException exception) {
      return List.of(encode(error(JSON.nullNode(), PARSE_ERROR, "Parse error")));
    }
    if (request == null || !request.isObject()
        || !"2.0".equals(request.path("jsonrpc").asText())
        || !request.path("method").isTextual()) {
      JsonNode id = request != null && request.isObject() && request.has("id")
          ? request.path("id") : JSON.nullNode();
      return List.of(encode(error(id, INVALID_REQUEST, "Invalid Request")));
    }
    boolean notification = !request.has("id");
    JsonNode id = notification ? JSON.nullNode() : request.path("id");
    JsonNode parameters = request.path("params");
    if (parameters.isMissingNode() || parameters.isNull()) parameters = JSON.createObjectNode();
    if (!parameters.isObject()) {
      return notification ? List.of()
          : List.of(encode(error(id, INVALID_PARAMS, "params must be an object")));
    }
    try {
      JsonNode result = dispatch(request.path("method").textValue(), parameters);
      return notification ? List.of() : List.of(encode(success(id, result)));
    } catch (RpcFailure failure) {
      return notification ? List.of()
          : List.of(encode(error(id, failure.code, failure.getMessage())));
    } catch (RuntimeException exception) {
      String message = exception.getMessage() == null ? exception.getClass().getSimpleName()
          : exception.getMessage();
      return notification ? List.of()
          : List.of(encode(error(id, INTERNAL_ERROR, message)));
    }
  }

  public void serve(BufferedReader input, PrintWriter output) throws IOException {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(output, "output");
    String line;
    while ((line = input.readLine()) != null) {
      handleLine(line).forEach(output::println);
      output.flush();
    }
  }

  private JsonNode dispatch(String method, JsonNode parameters) {
    return switch (method) {
      case "initialize" -> initialize(parameters);
      case "ping" -> JSON.createObjectNode();
      case "notifications/initialized" -> JSON.createObjectNode();
      case "tools/list" -> listTools();
      case "tools/call" -> callTool(parameters);
      default -> throw new RpcFailure(METHOD_NOT_FOUND, "Method not found: " + method);
    };
  }

  private JsonNode initialize(JsonNode parameters) {
    String protocolVersion = requiredText(parameters, "protocolVersion");
    ObjectNode result = JSON.createObjectNode();
    result.put("protocolVersion", protocolVersion.isEmpty() ? "2025-11-25" : protocolVersion);
    result.putObject("capabilities").putObject("tools");
    ObjectNode info = result.putObject("serverInfo");
    info.put("name", "ajent");
    info.put("version", version);
    info.put("title", "ajent native tools");
    result.put("instructions", INSTRUCTIONS);
    return result;
  }

  private JsonNode listTools() {
    ArrayNode listed = JSON.createArrayNode();
    for (PublishedTool published : tools) {
      ToolSpecification specification = published.specification();
      ObjectNode tool = listed.addObject();
      tool.put("name", specification.name());
      tool.set("inputSchema", specification.inputSchema());
      if (!specification.description().isEmpty()) {
        tool.put("description", specification.description());
      }
      EffectSet effects = published.effects();
      boolean destructive = effects.has(Effect.WRITE_FS) || effects.has(Effect.EXEC);
      ObjectNode annotations = tool.putObject("annotations");
      annotations.put("readOnlyHint", !destructive);
      annotations.put("destructiveHint", destructive);
      annotations.put("openWorldHint", effects.has(Effect.NET));
    }
    return JSON.createObjectNode().set("tools", listed);
  }

  private JsonNode callTool(JsonNode parameters) {
    String name = requiredText(parameters, "name");
    if (!byName.containsKey(name)) {
      throw new RpcFailure(INVALID_PARAMS, "unknown tool: " + name);
    }
    JsonNode arguments = parameters.path("arguments");
    if (arguments.isMissingNode() || arguments.isNull()) arguments = JSON.createObjectNode();
    ToolResult executed = Objects.requireNonNull(
        executor.execute(name, arguments), "tool executor result");
    ObjectNode result = JSON.createObjectNode();
    switch (executed) {
      case ToolResult.Success success -> {
        String text = success.output().text().isEmpty() ? "(no output)" : success.output().text();
        textContent(result.putArray("content"), text);
        success.output().change().ifPresent(change -> {
          ObjectNode structured = result.putObject("structuredContent");
          structured.put("path", change.path());
          structured.put("added", change.added());
          structured.put("removed", change.removed());
        });
      }
      case ToolResult.Failure failure -> {
        textContent(result.putArray("content"), failure.error().render());
        result.put("isError", true);
      }
    }
    return result;
  }

  private static void textContent(ArrayNode content, String text) {
    ObjectNode block = content.addObject();
    block.put("type", "text");
    block.put("text", text);
  }

  private static String requiredText(JsonNode parameters, String field) {
    JsonNode value = parameters.path(field);
    if (!value.isTextual()) {
      throw new RpcFailure(INVALID_PARAMS, "missing required field: " + field);
    }
    return value.textValue();
  }

  private static ObjectNode success(JsonNode id, JsonNode result) {
    ObjectNode response = JSON.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id.deepCopy());
    response.set("result", result);
    return response;
  }

  private static ObjectNode error(JsonNode id, int code, String message) {
    ObjectNode response = JSON.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id.deepCopy());
    ObjectNode error = response.putObject("error");
    error.put("code", code);
    error.put("message", message);
    return response;
  }

  private static String encode(JsonNode frame) {
    try {
      return JSON.writeValueAsString(frame);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to encode MCP frame", exception);
    }
  }

  @SuppressWarnings("serial")
  private static final class RpcFailure extends RuntimeException {
    private final int code;

    private RpcFailure(int code, String message) {
      super(message);
      this.code = code;
    }
  }
}
