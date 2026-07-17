package com.github.skanga.ajent.provider.anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.domain.ImageContent;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Anthropic message-array serialization with AgenTTY-compatible tool-result budgeting. */
public final class AnthropicMessages {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int TOOL_RESULT_WIRE_BUDGET = 64 * 1024;
  private static final String INCOMPLETE_RESULT =
      "(tool call did not complete — previous turn ended before this tool produced a result)";

  private AnthropicMessages() {}

  public static String toJson(Thread thread) {
    return toJson(thread, false);
  }

  public static String toJson(Thread thread, boolean includeThinking) {
    ArrayNode wire = JSON.createArrayNode();
    for (Message message : thread.messages()) {
      boolean hasImages = message.role() == Role.USER && message.images().stream()
          .anyMatch(image -> !image.isEmpty());
      boolean hasTools = message.role() == Role.ASSISTANT && !message.toolCalls().isEmpty();
      if (!message.text().isEmpty() || hasImages || hasTools) {
        wire.add(primaryMessage(message, hasImages, hasTools, includeThinking));
      }
      if (hasTools) wire.add(toolResults(message.toolCalls()));
    }
    pinLastBlocks(wire);
    try {
      return JSON.writeValueAsString(wire);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize Anthropic messages", exception);
    }
  }

  private static ObjectNode primaryMessage(Message message, boolean hasImages, boolean hasTools,
                                           boolean includeThinking) {
    ObjectNode wireMessage = JSON.createObjectNode();
    wireMessage.put("role", message.role() == Role.USER ? "user" : "assistant");
    ArrayNode content = wireMessage.putArray("content");
    boolean hasThinking = includeThinking && message.role() == Role.ASSISTANT
        && !message.thinkingSignature().isEmpty()
        && (!message.text().isEmpty() || hasTools);
    if (hasThinking) {
      ObjectNode thinking = content.addObject();
      thinking.put("type", "thinking");
      thinking.put("thinking", message.thinking());
      thinking.put("signature", message.thinkingSignature());
    }
    if (hasImages) {
      for (ImageContent image : message.images()) if (!image.isEmpty()) content.add(imageBlock(image));
    }
    if (!message.text().isEmpty()) {
      ObjectNode text = content.addObject();
      text.put("type", "text");
      text.put("text", message.text());
    }
    if (hasTools) for (ToolUse tool : message.toolCalls()) content.add(toolUseBlock(tool));
    return wireMessage;
  }

  private static ObjectNode imageBlock(ImageContent image) {
    ObjectNode block = JSON.createObjectNode();
    block.put("type", "image");
    ObjectNode source = block.putObject("source");
    source.put("type", "base64");
    source.put("media_type", image.mediaType().isEmpty() ? "image/png" : image.mediaType());
    source.put("data", Base64.getEncoder().encodeToString(image.bytes()));
    return block;
  }

  private static ObjectNode toolUseBlock(ToolUse tool) {
    ObjectNode block = JSON.createObjectNode();
    block.put("type", "tool_use");
    block.put("id", tool.id().value());
    block.put("name", tool.name().value());
    block.set("input", JSON.valueToTree(tool.arguments()));
    return block;
  }

  private static ObjectNode toolResults(List<ToolUse> tools) {
    ObjectNode message = JSON.createObjectNode();
    message.put("role", "user");
    ArrayNode content = message.putArray("content");
    for (ToolUse tool : tools) {
      ObjectNode result = content.addObject();
      result.put("type", "tool_result");
      result.put("tool_use_id", tool.id().value());
      ToolStatus status = tool.status();
      String output = status.output();
      if (!status.isTerminal()) result.put("content", INCOMPLETE_RESULT);
      else if (output.isEmpty()) result.put("content", "(no output)");
      else result.put("content", capToolResult(output, TOOL_RESULT_WIRE_BUDGET));
      result.put("is_error", status.isError());
    }
    return message;
  }

  private static void pinLastBlocks(ArrayNode messages) {
    int firstPinned = Math.max(0, messages.size() - 2);
    for (int index = firstPinned; index < messages.size(); index++) {
      JsonNode content = messages.get(index).path("content");
      if (content.isArray() && !content.isEmpty() && content.get(content.size() - 1) instanceof ObjectNode last) {
        ObjectNode cache = last.putObject("cache_control");
        cache.put("type", "ephemeral");
      }
    }
  }

  static String capToolResult(String input, int budget) {
    byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= budget) return input;
    int elided = bytes.length - budget;
    byte[] marker = ("\n\n...[" + elided
        + " bytes elided to fit wire budget; full output is in the transcript]...\n\n")
        .getBytes(StandardCharsets.UTF_8);
    if (marker.length + 16 >= budget) {
      return new String(bytes, 0, utf8Floor(bytes, budget), StandardCharsets.UTF_8);
    }
    int bodyBudget = budget - marker.length;
    int headLength = bodyBudget * 7 / 10;
    int tailLength = bodyBudget - headLength;
    int headCut = utf8Floor(bytes, headLength);
    int tailFrom = utf8Ceil(bytes, bytes.length - tailLength);
    var output = new ByteArrayOutputStream(headCut + marker.length + bytes.length - tailFrom);
    output.write(bytes, 0, headCut);
    output.writeBytes(marker);
    output.write(bytes, tailFrom, bytes.length - tailFrom);
    return output.toString(StandardCharsets.UTF_8);
  }

  private static int utf8Floor(byte[] bytes, int index) {
    if (index >= bytes.length) return bytes.length;
    int steps = 0;
    while (index > 0 && isContinuation(bytes[index]) && steps < 3) { index--; steps++; }
    return index;
  }

  private static int utf8Ceil(byte[] bytes, int index) {
    int steps = 0;
    while (index < bytes.length && isContinuation(bytes[index]) && steps < 3) { index++; steps++; }
    return index;
  }

  private static boolean isContinuation(byte value) { return (value & 0xc0) == 0x80; }
}
