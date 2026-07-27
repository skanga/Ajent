package com.github.skanga.ajent.provider.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import com.github.skanga.ajent.provider.wire.SseFramer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Incremental decoder for the OpenAI Responses SSE protocol used by ChatGPT Codex. */
public final class CodexStreamDecoder {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final SseFramer framer = new SseFramer();
  private final List<StreamEvent> pending = new ArrayList<>();
  private final Map<String, ToolState> tools = new HashMap<>();
  private final Map<String, String> callIdsByItem = new HashMap<>();
  private boolean started;
  private boolean terminal;
  private boolean sawTool;

  public List<StreamEvent> feed(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes");
    if (terminal) return List.of();
    framer.feed(bytes, event -> {
      if ("[DONE]".equals(event.data())) {
        finish(StopReason.UNSPECIFIED);
      } else if (!event.data().isBlank()) {
        consume(event.data(), event.name());
      }
    });
    return drain();
  }

  public List<StreamEvent> end() {
    if (!terminal) finish(StopReason.UNSPECIFIED);
    return drain();
  }

  private void consume(String payload, String eventName) {
    try {
      JsonNode root = JSON.readTree(payload);
      String type = root.path("type").asText(eventName);
      switch (type) {
        case "response.created", "response.queued", "response.in_progress" -> start();
        case "response.output_text.delta" ->
            addText(root.path("delta").asText(), false);
        case "response.output_text.done" -> pending.add(new StreamEvent.TextBlockClosed());
        case "response.content_part.done" -> {
          if ("output_text".equals(root.path("part").path("type").asText())) {
            pending.add(new StreamEvent.TextBlockClosed());
          }
        }
        case "response.reasoning_summary_text.delta" ->
            addText(root.path("delta").asText(), true);
        case "response.output_item.added" -> startTool(root.path("item"));
        case "response.function_call_arguments.delta" ->
            toolDelta(callId(root), root.path("delta").asText());
        case "response.function_call_arguments.done" ->
            reconcile(callId(root), root.path("arguments").asText());
        case "response.output_item.done" -> endTool(root.path("item"));
        case "response.completed" -> complete(root.path("response"));
        case "response.failed", "response.cancelled" -> fail(root.path("response"));
        case "error" -> failRoot(root);
        default -> { }
      }
    } catch (JsonProcessingException exception) {
      terminal = true;
      pending.add(new StreamEvent.Error("Codex: invalid Responses API event"));
    }
  }

  private void start() {
    if (!started) {
      started = true;
      pending.add(new StreamEvent.Started());
    }
  }

  private void addText(String value, boolean thinking) {
    if (value.isEmpty()) return;
    start();
    pending.add(thinking
        ? new StreamEvent.ThinkingDelta(value, "") : new StreamEvent.TextDelta(value));
  }

  private void startTool(JsonNode item) {
    if (!"function_call".equals(item.path("type").asText())) return;
    String callId = item.path("call_id").asText();
    String itemId = item.path("id").asText();
    if (!itemId.isBlank() && !callId.isBlank()) callIdsByItem.put(itemId, callId);
    if (callId.isBlank() || tools.containsKey(callId)) return;
    start();
    sawTool = true;
    tools.put(callId, new ToolState());
    pending.add(new StreamEvent.ToolUseStart(callId, item.path("name").asText()));
  }

  private String callId(JsonNode root) {
    String callId = root.path("call_id").asText();
    return callId.isBlank()
        ? callIdsByItem.getOrDefault(root.path("item_id").asText(), "") : callId;
  }

  private void toolDelta(String callId, String delta) {
    if (callId.isBlank() || delta.isEmpty()) return;
    ToolState state = tools.get(callId);
    if (state == null) return;
    state.arguments.append(delta);
    pending.add(new StreamEvent.ToolUseDelta(delta));
  }

  private void reconcile(String callId, String complete) {
    ToolState state = tools.get(callId);
    if (state == null || complete.isEmpty()) return;
    String emitted = state.arguments.toString();
    String suffix = complete.startsWith(emitted) ? complete.substring(emitted.length()) : complete;
    if (!suffix.isEmpty()) {
      state.arguments.append(suffix);
      pending.add(new StreamEvent.ToolUseDelta(suffix));
    }
  }

  private void endTool(JsonNode item) {
    if (!"function_call".equals(item.path("type").asText())) return;
    String callId = item.path("call_id").asText();
    if (!item.path("id").asText().isBlank() && !callId.isBlank()) {
      callIdsByItem.put(item.path("id").asText(), callId);
    }
    if (!tools.containsKey(callId)) startTool(item);
    reconcile(callId, item.path("arguments").asText());
    ToolState state = tools.get(callId);
    if (state != null && !state.closed) {
      state.closed = true;
      pending.add(new StreamEvent.ToolUseEnd());
    }
  }

  private void complete(JsonNode response) {
    for (JsonNode item : response.path("output")) {
      if ("function_call".equals(item.path("type").asText())) endTool(item);
    }
    JsonNode usage = response.path("usage");
    if (!usage.isMissingNode()) {
      pending.add(new StreamEvent.Usage(
          usage.path("input_tokens").asInt(),
          usage.path("output_tokens").asInt(),
          0,
          usage.path("input_tokens_details").path("cached_tokens").asInt()));
    }
    StopReason reason = "incomplete".equals(response.path("status").asText())
        ? StopReason.MAX_TOKENS : sawTool ? StopReason.TOOL_USE : StopReason.END_TURN;
    finish(reason);
  }

  private void fail(JsonNode response) {
    String message = response.path("error").path("message").asText("request failed");
    terminal = true;
    pending.add(new StreamEvent.Error("Codex: " + message));
  }

  private void failRoot(JsonNode root) {
    String message = root.path("error").path("message").asText(
        root.path("message").asText("request failed"));
    terminal = true;
    pending.add(new StreamEvent.Error("Codex: " + message));
  }

  private void finish(StopReason reason) {
    if (terminal) return;
    terminal = true;
    pending.add(new StreamEvent.Finished(reason));
  }

  private List<StreamEvent> drain() {
    List<StreamEvent> result = List.copyOf(pending);
    pending.clear();
    return result;
  }

  private static final class ToolState {
    private final StringBuilder arguments = new StringBuilder();
    private boolean closed;
  }
}
