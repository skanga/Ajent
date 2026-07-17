package com.github.skanga.ajent.provider.anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.domain.ModelCapabilities;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.provider.ToolSpecification;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Exact Anthropic Messages API request and header construction. */
public final class AnthropicWire {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String ANTHROPIC_VERSION = "2023-06-01";
  private static final String USER_AGENT = "ajent/0.2.8";

  private AnthropicWire() {}

  public static HttpRequest buildHttpRequest(AnthropicRequest request) {
    var builder = HttpRequest.newBuilder(request.endpoint())
        .header("accept", "application/json")
        .header("content-type", "application/json")
        .header("user-agent", USER_AGENT)
        .header("x-app", "ajent")
        .header("anthropic-version", ANTHROPIC_VERSION)
        .header("anthropic-dangerous-direct-browser-access", "true")
        .POST(HttpRequest.BodyPublishers.ofString(body(request), StandardCharsets.UTF_8));
    String betas = betas(request);
    if (!betas.isEmpty()) builder.header("anthropic-beta", betas);
    switch (request.auth()) {
      case ProviderAuth.ApiKey key -> builder.header("x-api-key", key.value());
      case ProviderAuth.Bearer bearer -> builder.header("authorization", "Bearer " + bearer.token());
      case ProviderAuth.Empty ignored -> {
        // The transport reports the missing-auth error without sending this request.
      }
    }
    return builder.build();
  }

  public static String body(AnthropicRequest request) {
    ObjectNode root = JSON.createObjectNode();
    root.put("model", request.model());
    root.put("max_tokens", request.maxTokens());
    root.put("stream", true);
    root.set("system", system(request));
    Instant epoch = Instant.EPOCH;
    var thread = new Thread(new ThreadId(""), "", request.messages(), epoch, epoch, List.of());
    try {
      root.set("messages", JSON.readTree(
          AnthropicMessages.toJson(thread, !request.effort().isEmpty())));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to build Anthropic messages", exception);
    }
    if (!request.tools().isEmpty()) root.set("tools", tools(request.tools()));
    root.putObject("metadata").put("user_id", request.userId());
    if (!request.effort().isEmpty()) {
      root.putObject("thinking").put("type", "adaptive");
      root.putObject("output_config").put("effort", request.effort());
    }
    try {
      return JSON.writeValueAsString(root);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize Anthropic request", exception);
    }
  }

  static String betas(AnthropicRequest request) {
    ModelCapabilities capabilities = ModelCapabilities.fromId(request.model());
    var values = new ArrayList<String>();
    if (!capabilities.isHaiku()) values.add("claude-code-20250219");
    if (request.auth() instanceof ProviderAuth.Bearer) values.add("oauth-2025-04-20");
    if (capabilities.extendedContext1m()) values.add("context-1m-2025-08-07");
    if (capabilities.generation4OrLater()) values.add("context-management-2025-06-27");
    values.add("prompt-caching-scope-2026-01-05");
    if (request.tools().stream().anyMatch(ToolSpecification::eagerInputStreaming)) {
      values.add("fine-grained-tool-streaming-2025-05-14");
    }
    return String.join(",", values);
  }

  private static ArrayNode system(AnthropicRequest request) {
    ArrayNode system = JSON.createArrayNode();
    if (request.auth() instanceof ProviderAuth.Bearer) {
      ObjectNode preamble = system.addObject();
      preamble.put("type", "text");
      preamble.put("text", "You are Claude Code, Anthropic's official CLI for Claude.");
    }
    ObjectNode prompt = system.addObject();
    prompt.put("type", "text");
    prompt.put("text", request.systemPrompt());
    prompt.putObject("cache_control").put("type", "ephemeral");
    return system;
  }

  private static ArrayNode tools(List<ToolSpecification> specifications) {
    ArrayNode tools = JSON.createArrayNode();
    for (ToolSpecification specification : specifications) {
      ObjectNode tool = tools.addObject();
      tool.put("name", specification.name());
      tool.put("description", specification.description());
      tool.set("input_schema", specification.inputSchema());
      if (specification.eagerInputStreaming()) tool.put("eager_input_streaming", true);
    }
    ((ObjectNode) tools.get(tools.size() - 1))
        .putObject("cache_control").put("type", "ephemeral");
    return tools;
  }
}
