package com.github.skanga.ajent.provider.ollama;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OllamaWire {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int CONTEXT_FLOOR = 8_192;
  private static final int CONTEXT_CEILING = 32_768;

  private OllamaWire() {}

  public static ArrayNode buildMessages(List<Message> messages, boolean jsonProtocol) {
    ArrayNode result = JSON.createArrayNode();
    for (Message message : messages) {
      boolean hasText = !message.text().isEmpty();
      boolean hasImages = message.role() == Role.USER
          && message.images().stream().anyMatch(image -> !image.isEmpty());
      boolean hasTools = message.role() == Role.ASSISTANT && !message.toolCalls().isEmpty();
      if (jsonProtocol && hasTools) {
        addJsonProtocolHistory(result, message, hasText);
        continue;
      }
      if (hasText || hasImages || hasTools) {
        ObjectNode wire = result.addObject();
        wire.put("role", message.role() == Role.USER ? "user" : "assistant");
        wire.put("content", message.text());
        if (hasImages) {
          ArrayNode images = wire.putArray("images");
          message.images().stream().filter(image -> !image.isEmpty())
              .map(image -> Base64.getEncoder().encodeToString(image.bytes()))
              .forEach(images::add);
        }
        if (hasTools) {
          ArrayNode calls = wire.putArray("tool_calls");
          for (ToolUse tool : message.toolCalls()) {
            ObjectNode call = calls.addObject();
            call.put("id", tool.id().value());
            ObjectNode function = call.putObject("function");
            function.put("name", tool.name().value());
            function.set("arguments", JSON.valueToTree(tool.arguments()));
          }
        }
      }
      if (hasTools) {
        for (ToolUse tool : message.toolCalls()) {
          ObjectNode toolResult = result.addObject();
          toolResult.put("role", "tool");
          toolResult.put("tool_name", tool.name().value());
          toolResult.put("content", toolOutput(tool));
        }
      }
    }
    return result;
  }

  public static Map<String, Object> buildOptions(OllamaRequestOptions request) {
    int context = request.contextWindow() > 0
        ? Math.clamp(request.contextWindow(), CONTEXT_FLOOR, CONTEXT_CEILING)
        : CONTEXT_FLOOR;
    int prediction = request.maxTokens() > 0 ? request.maxTokens() : 4_096;
    prediction = Math.min(prediction, context / 2);
    prediction = Math.max(prediction, Math.min(2_048, context));
    Map<String, Object> options = new LinkedHashMap<>();
    options.put("num_ctx", context);
    options.put("num_predict", prediction);
    if (request.jsonProtocol()) {
      options.put("temperature", 0.2);
      options.put("top_p", 0.9);
    }
    return Map.copyOf(options);
  }

  public static String systemPrompt() {
    String operatingSystem = System.getProperty("os.name", "unknown");
    String shell = operatingSystem.toLowerCase().contains("win") ? "cmd.exe" : "sh";
    return """
        You are Ajent, an agentty-compatible terminal coding assistant. You are helpful,
        direct, and act on requests instead of asking which option to pick. Keep replies concise.

        CONVERSATION MEMORY
        - The full conversation so far is in the messages above. Use earlier messages for follow-ups.

        TOOLS
        - Call a tool only when the request needs an action. Never claim an action ran without a result.

        ENVIRONMENT
        - Working directory: %s
        - Operating system: %s
        - Shell: %s
        """.formatted(Path.of("").toAbsolutePath(), operatingSystem, shell);
  }

  private static void addJsonProtocolHistory(
      ArrayNode result, Message message, boolean hasText) {
    if (hasText) {
      ObjectNode prose = result.addObject();
      prose.put("role", "assistant");
      prose.put("content", message.text());
    }
    for (ToolUse tool : message.toolCalls()) {
      ObjectNode callObject = JSON.createObjectNode();
      callObject.put("tool_name", tool.name().value());
      callObject.set("tool_args", JSON.valueToTree(tool.arguments()));
      ObjectNode callMessage = result.addObject();
      callMessage.put("role", "assistant");
      try {
        callMessage.put("content", JSON.writeValueAsString(callObject));
      } catch (JsonProcessingException exception) {
        throw new IllegalStateException("Unable to serialize Ollama JSON protocol history", exception);
      }
      ObjectNode toolResult = result.addObject();
      toolResult.put("role", "user");
      toolResult.put("content", "TOOL RESULT (" + tool.name().value() + "):\n" + toolOutput(tool));
    }
  }

  private static String toolOutput(ToolUse tool) {
    String output = tool.status().output();
    if (!output.isEmpty()) return output;
    if (tool.status() instanceof ToolStatus.Rejected) return "(rejected by user)";
    if (!tool.status().isTerminal()) return "(no output)";
    return output;
  }
}
