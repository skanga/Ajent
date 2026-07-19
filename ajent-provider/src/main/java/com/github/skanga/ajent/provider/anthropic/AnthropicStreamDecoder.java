package com.github.skanga.ajent.provider.anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import com.github.skanga.ajent.provider.wire.SseFramer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Incremental Anthropic SSE decoder with exactly-once terminal semantics. */
public final class AnthropicStreamDecoder {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final SseFramer framer = new SseFramer();
  private final Consumer<SseFramer.Event> observer;
  private boolean toolBlockOpen;
  private boolean textBlockOpen;
  private boolean terminal;
  private StopReason stopReason = StopReason.UNSPECIFIED;

  public AnthropicStreamDecoder() {
    this(ignored -> { });
  }

  public AnthropicStreamDecoder(Consumer<SseFramer.Event> observer) {
    this.observer = Objects.requireNonNull(observer, "observer");
  }

  public List<StreamEvent> feed(byte[] bytes) {
    if (terminal) return List.of();
    var events = new ArrayList<StreamEvent>();
    framer.feed(bytes, event -> consume(event, events));
    return List.copyOf(events);
  }

  public List<StreamEvent> end() {
    if (terminal) return List.of();
    terminal = true;
    var events = new ArrayList<StreamEvent>();
    closeTool(events);
    events.add(new StreamEvent.Finished(stopReason));
    return List.copyOf(events);
  }

  private void consume(SseFramer.Event event, List<StreamEvent> events) {
    observer.accept(event);
    if (terminal || event.data().isEmpty() || "[DONE]".equals(event.data())) return;
    if ("ping".equals(event.name())) {
      events.add(new StreamEvent.Heartbeat());
      return;
    }
    JsonNode root;
    try {
      root = JSON.readTree(event.data());
    } catch (JsonProcessingException exception) {
      return;
    }
    switch (event.name()) {
      case "message_start" -> messageStart(root, events);
      case "content_block_start" -> blockStart(root, events);
      case "content_block_delta" -> blockDelta(root, events);
      case "content_block_stop" -> blockStop(events);
      case "message_delta" -> messageDelta(root, events);
      case "message_stop" -> messageStop(events);
      case "error" -> error(root, events);
      default -> {
        // Anthropic may add event types; unknown frames are forward-compatible no-ops.
      }
    }
  }

  private static void messageStart(JsonNode root, List<StreamEvent> events) {
    events.add(new StreamEvent.Started());
    JsonNode usage = root.path("message").path("usage");
    if (usage.isObject()) events.add(usage(usage));
  }

  private void blockStart(JsonNode root, List<StreamEvent> events) {
    JsonNode block = root.path("content_block");
    switch (block.path("type").asText()) {
      case "tool_use" -> {
        toolBlockOpen = true;
        textBlockOpen = false;
        events.add(new StreamEvent.ToolUseStart(
            block.path("id").asText(), block.path("name").asText()));
      }
      case "text" -> textBlockOpen = true;
      default -> {
        // Thinking/redacted/unknown blocks do not need block-close events.
      }
    }
  }

  private static void blockDelta(JsonNode root, List<StreamEvent> events) {
    JsonNode delta = root.path("delta");
    switch (delta.path("type").asText()) {
      case "text_delta" -> events.add(new StreamEvent.TextDelta(delta.path("text").asText()));
      case "input_json_delta" -> events.add(
          new StreamEvent.ToolUseDelta(delta.path("partial_json").asText()));
      case "thinking_delta" -> events.add(
          new StreamEvent.ThinkingDelta(delta.path("thinking").asText(), ""));
      case "signature_delta" -> events.add(
          new StreamEvent.ThinkingDelta("", delta.path("signature").asText()));
      default -> {
        // Unknown delta kinds are intentionally ignored.
      }
    }
  }

  private void blockStop(List<StreamEvent> events) {
    if (toolBlockOpen) {
      closeTool(events);
    } else if (textBlockOpen) {
      textBlockOpen = false;
      events.add(new StreamEvent.TextBlockClosed());
    }
  }

  private void messageDelta(JsonNode root, List<StreamEvent> events) {
    JsonNode usage = root.path("usage");
    if (usage.isObject()) events.add(usage(usage));
    JsonNode reason = root.path("delta").path("stop_reason");
    if (reason.isTextual()) stopReason = stopReason(reason.textValue());
  }

  private void messageStop(List<StreamEvent> events) {
    closeTool(events);
    events.add(new StreamEvent.Finished(stopReason));
    terminal = true;
  }

  private void error(JsonNode root, List<StreamEvent> events) {
    String message = root.path("error").path("message").asText("unknown error");
    events.add(new StreamEvent.Error(message));
    terminal = true;
  }

  private void closeTool(List<StreamEvent> events) {
    if (!toolBlockOpen) return;
    toolBlockOpen = false;
    events.add(new StreamEvent.ToolUseEnd());
  }

  private static StreamEvent.Usage usage(JsonNode usage) {
    return new StreamEvent.Usage(
        usage.path("input_tokens").asInt(),
        usage.path("output_tokens").asInt(),
        usage.path("cache_creation_input_tokens").asInt(),
        usage.path("cache_read_input_tokens").asInt());
  }

  private static StopReason stopReason(String value) {
    return switch (value) {
      case "end_turn" -> StopReason.END_TURN;
      case "tool_use" -> StopReason.TOOL_USE;
      case "max_tokens" -> StopReason.MAX_TOKENS;
      case "stop_sequence" -> StopReason.STOP_SEQUENCE;
      default -> StopReason.UNSPECIFIED;
    };
  }
}
