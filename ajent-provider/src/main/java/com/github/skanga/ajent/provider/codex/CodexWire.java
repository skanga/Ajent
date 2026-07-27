package com.github.skanga.ajent.provider.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import java.util.Base64;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;

/** ChatGPT Codex Responses API serialization, kept separate from OpenAI API-key requests. */
public final class CodexWire {
  public static final URI RESPONSES_URI =
      URI.create("https://chatgpt.com/backend-api/codex/responses");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ObjectMapper ARGUMENTS = new ObjectMapper()
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  private CodexWire() {}

  public static ObjectNode buildRequestBody(CodexRequest request) {
    ObjectNode body = JSON.createObjectNode();
    body.put("model", request.model());
    if (!request.instructions().isBlank()) body.put("instructions", request.instructions());
    body.put("stream", true);
    body.put("store", false);
    body.put("parallel_tool_calls", true);
    if (!request.reasoningEffort().isBlank()) {
      body.putObject("reasoning").put("effort", request.reasoningEffort());
    }
    body.set("input", buildInput(request));
    if (!request.tools().isEmpty()) {
      ArrayNode tools = body.putArray("tools");
      request.tools().forEach(specification -> {
        ObjectNode tool = tools.addObject();
        tool.put("type", "function");
        tool.put("name", specification.name());
        tool.put("description", specification.description());
        tool.set("parameters", specification.inputSchema());
      });
      body.put("tool_choice", "auto");
    }
    return body;
  }

  public static HttpRequest buildHttpRequest(
      CodexRequest request, CodexAuthManager.Headers headers) {
    return buildHttpRequest(request, headers, RESPONSES_URI);
  }

  public static HttpRequest buildHttpRequest(
      CodexRequest request, CodexAuthManager.Headers headers, URI endpoint) {
    return HttpRequest.newBuilder(endpoint)
        .header("accept", "text/event-stream")
        .header("content-type", "application/json")
        .header("authorization", headers.authorization())
        .header("chatgpt-account-id", headers.accountId())
        .header("openai-beta", "responses=experimental")
        .header("user-agent", "ajent/0.2.8")
        .POST(HttpRequest.BodyPublishers.ofString(
            buildRequestBody(request).toString(), StandardCharsets.UTF_8))
        .build();
  }

  private static ArrayNode buildInput(CodexRequest request) {
    ArrayNode input = JSON.createArrayNode();
    for (Message message : request.messages()) {
      if (!message.text().isBlank() || !message.images().isEmpty()) {
        ObjectNode item = input.addObject();
        item.put("type", "message");
        item.put("role", message.role() == Role.USER ? "user" : "assistant");
        ArrayNode content = item.putArray("content");
        if (!message.text().isBlank()) {
          ObjectNode text = content.addObject();
          text.put("type", message.role() == Role.USER ? "input_text" : "output_text");
          text.put("text", message.text());
        }
        if (message.role() == Role.USER) {
          message.images().stream().filter(image -> !image.isEmpty()).forEach(image -> {
            ObjectNode part = content.addObject();
            part.put("type", "input_image");
            part.put("image_url", "data:" + image.mediaType() + ";base64,"
                + Base64.getEncoder().encodeToString(image.bytes()));
          });
        }
      }
      if (message.role() == Role.ASSISTANT) {
        for (ToolUse tool : message.toolCalls()) {
          ObjectNode call = input.addObject();
          call.put("type", "function_call");
          call.put("call_id", tool.id().value());
          call.put("name", tool.name().value());
          call.put("arguments", arguments(tool));
        }
        for (ToolUse tool : message.toolCalls()) {
          if (tool.status().isTerminal()) {
            ObjectNode output = input.addObject();
            output.put("type", "function_call_output");
            output.put("call_id", tool.id().value());
            output.put("output", output(tool));
          }
        }
      }
    }
    return input;
  }

  private static String arguments(ToolUse tool) {
    try {
      return ARGUMENTS.writeValueAsString(tool.arguments());
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Unable to serialize Codex tool arguments", exception);
    }
  }

  private static String output(ToolUse tool) {
    String value = tool.status().output();
    if (!value.isBlank()) return value;
    if (tool.status() instanceof ToolStatus.Rejected) return "(rejected by user)";
    return "(no output)";
  }
}
