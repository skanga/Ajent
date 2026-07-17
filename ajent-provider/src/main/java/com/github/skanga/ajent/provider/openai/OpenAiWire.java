package com.github.skanga.ajent.provider.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.provider.ToolSpecification;
import java.util.Base64;
import java.util.List;

public final class OpenAiWire {
  private static final ObjectMapper JSON = new ObjectMapper();

  private OpenAiWire() {}

  public static ArrayNode buildTools(List<ToolSpecification> tools) {
    ArrayNode result = JSON.createArrayNode();
    for (var tool : tools) {
      ObjectNode item = result.addObject();
      item.put("type", "function");
      ObjectNode function = item.putObject("function");
      function.put("name", tool.name());
      function.put("description", tool.description());
      function.set("parameters", tool.inputSchema());
    }
    return result;
  }

  public static ArrayNode buildMessages(Thread thread) {
    ArrayNode result = JSON.createArrayNode();
    for (Message message : thread.messages()) {
      boolean hasText = !message.text().isEmpty();
      boolean hasImages = message.role() == Role.USER
          && message.images().stream().anyMatch(image -> !image.isEmpty());
      boolean hasTools = message.role() == Role.ASSISTANT && !message.toolCalls().isEmpty();
      if (hasText || hasImages || hasTools) {
        result.add(primaryMessage(message, hasImages, hasTools));
      }
      if (hasTools) {
        for (ToolUse tool : message.toolCalls()) {
          result.add(toolResult(tool));
        }
      }
    }
    return result;
  }

  private static ObjectNode primaryMessage(Message message, boolean hasImages, boolean hasTools) {
    ObjectNode result = JSON.createObjectNode();
    result.put("role", message.role() == Role.USER ? "user" : "assistant");
    if (hasImages) {
      ArrayNode content = result.putArray("content");
      if (!message.text().isEmpty()) {
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", message.text());
      }
      for (var image : message.images()) {
        if (image.isEmpty()) continue;
        ObjectNode part = content.addObject();
        part.put("type", "image_url");
        part.putObject("image_url").put(
            "url", "data:" + image.mediaType() + ";base64,"
                + Base64.getEncoder().encodeToString(image.bytes()));
      }
    } else {
      result.put("content", message.text());
    }
    if (hasTools) {
      ArrayNode calls = result.putArray("tool_calls");
      for (ToolUse tool : message.toolCalls()) {
        ObjectNode call = calls.addObject();
        call.put("id", tool.id().value());
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", tool.name().value());
        try {
          function.put("arguments", JSON.writeValueAsString(tool.arguments()));
        } catch (JsonProcessingException exception) {
          throw new IllegalStateException("Unable to serialize OpenAI tool arguments", exception);
        }
      }
    }
    return result;
  }

  private static ObjectNode toolResult(ToolUse tool) {
    ObjectNode result = JSON.createObjectNode();
    result.put("role", "tool");
    result.put("tool_call_id", tool.id().value());
    String output = tool.status().output();
    if (output.isEmpty()) {
      if (tool.status() instanceof ToolStatus.Rejected) output = "(rejected by user)";
      else if (!tool.status().isTerminal()) output = "(no output)";
    }
    result.put("content", output);
    return result;
  }
}
