package com.github.skanga.ajent.provider.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Pure OpenAI SSE and native Ollama NDJSON translation. */
public final class OpenAiStreamParser {
  static final ObjectMapper JSON = new ObjectMapper();
  private static final String EMPTY_RESPONSE = "(empty response)";
  private static final Set<String> SWALLOWED_META_TOOLS =
      Set.of("remember", "forget", "wipe_memory", "skill");

  private OpenAiStreamParser() {}

  public static List<StreamEvent> parseSse(String bytes, Set<String> knownTools) {
    var context = new ParseContext(knownTools);
    String normalized = bytes.replace("\r\n", "\n").replace('\r', '\n');
    for (String frame : normalized.split("\n\n", -1)) {
      String payload = ssePayload(frame);
      if (payload == null || payload.isEmpty()) continue;
      if ("[DONE]".equals(payload)) {
        context.done = true;
        continue;
      }
      try {
        consumeOpenAiFrame(JSON.readTree(payload), context);
      } catch (JsonProcessingException exception) {
        context.events.add(new StreamEvent.Error("invalid provider JSON: " + exception.getOriginalMessage()));
      }
    }
    context.complete();
    return context.allEvents();
  }

  public static List<StreamEvent> parseNdjson(String bytes, Set<String> knownTools) {
    var context = new ParseContext(knownTools);
    for (String line : bytes.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
      if (line.isBlank()) continue;
      try {
        consumeNativeFrame(JSON.readTree(line), context);
      } catch (JsonProcessingException exception) {
        context.events.add(new StreamEvent.Error("invalid provider JSON: " + exception.getOriginalMessage()));
      }
    }
    context.complete();
    return context.allEvents();
  }

  private static String ssePayload(String frame) {
    StringBuilder payload = new StringBuilder();
    for (String line : frame.split("\n")) {
      if (!line.startsWith("data:")) continue;
      if (!payload.isEmpty()) payload.append('\n');
      String value = line.substring(5);
      payload.append(value.startsWith(" ") ? value.substring(1) : value);
    }
    return payload.isEmpty() ? null : payload.toString();
  }

  static void consumeOpenAiFrame(JsonNode root, ParseContext context) {
    JsonNode error = root.path("error");
    if (!error.isMissingNode()) {
      context.events.add(new StreamEvent.Error(
          error.path("message").asText(error.isTextual() ? error.textValue() : "provider error")));
      return;
    }
    JsonNode usage = root.path("usage");
    if (usage.isObject()) {
      context.events.add(new StreamEvent.Usage(
          usage.path("prompt_tokens").asInt(), usage.path("completion_tokens").asInt()));
    }
    JsonNode choices = root.path("choices");
    if (!choices.isArray() || choices.isEmpty()) return;
    JsonNode choice = choices.get(0);
    JsonNode delta = choice.path("delta");
    JsonNode content = delta.path("content");
    if (content.isTextual() && !content.textValue().isEmpty()) {
      context.consumeContentChunk(content.textValue());
    }
    JsonNode toolCalls = delta.path("tool_calls");
    if (toolCalls.isArray()) {
      for (JsonNode call : toolCalls) context.consumeStructuredTool(call);
    }
    JsonNode finishReason = choice.path("finish_reason");
    if (finishReason.isTextual()) context.stopReason = mapStopReason(finishReason.textValue());
  }

  static void consumeNativeFrame(JsonNode root, ParseContext context) {
    JsonNode error = root.path("error");
    if (!error.isMissingNode()) {
      context.events.add(new StreamEvent.Error(
          error.path("message").asText(error.isTextual() ? error.textValue() : "provider error")));
    }
    JsonNode message = root.path("message");
    JsonNode content = message.path("content");
    if (content.isTextual() && !content.textValue().isEmpty()) {
      context.consumeContentChunk(content.textValue());
    }
    JsonNode calls = message.path("tool_calls");
    if (calls.isArray()) {
      for (JsonNode call : calls) {
        JsonNode function = call.path("function");
        String id = "call_native_" + context.nativeSequence++;
        String name = function.path("name").asText();
        context.events.add(new StreamEvent.ToolUseStart(id, name));
        JsonNode arguments = function.path("arguments");
        context.events.add(new StreamEvent.ToolUseDelta(compact(arguments.isMissingNode()
            ? JSON.createObjectNode() : arguments)));
        context.events.add(new StreamEvent.ToolUseEnd());
        context.toolEmitted = true;
      }
    }
    if (root.path("prompt_eval_count").canConvertToInt()
        || root.path("eval_count").canConvertToInt()) {
      context.events.add(new StreamEvent.Usage(
          root.path("prompt_eval_count").asInt(), root.path("eval_count").asInt()));
    }
    if (root.path("done").asBoolean(false)) {
      context.done = true;
      context.stopReason = "length".equals(root.path("done_reason").asText())
          ? StopReason.MAX_TOKENS : StopReason.END_TURN;
    }
  }

  private static StopReason mapStopReason(String reason) {
    return switch (reason) {
      case "stop" -> StopReason.END_TURN;
      case "length" -> StopReason.MAX_TOKENS;
      case "tool_calls", "function_call" -> StopReason.TOOL_USE;
      default -> StopReason.UNSPECIFIED;
    };
  }

  private static String compact(JsonNode node) {
    try {
      return JSON.writeValueAsString(node);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize provider JSON", exception);
    }
  }

  static final class ParseContext {
    private final Set<String> knownTools;
    private final List<StreamEvent> events = new ArrayList<>();
    private final StringBuilder contentHold = new StringBuilder();
    private StopReason stopReason = StopReason.UNSPECIFIED;
    private Integer activeToolIndex;
    private int nativeSequence;
    private int salvagedSequence;
    private boolean toolEmitted;
    private boolean done;
    private boolean completed;
    private boolean salvageEligible = true;
    private boolean textEmitted;
    private boolean contentSeen;
    private int drainPosition;

    ParseContext(Set<String> knownTools) {
      this.knownTools = Set.copyOf(knownTools);
    }

    void addError(String message) {
      events.add(new StreamEvent.Error(message));
    }

    void markDone() {
      done = true;
    }

    private void consumeContentChunk(String chunk) {
      if (chunk.isEmpty()) return;
      contentSeen = true;
      if (knownTools.isEmpty() || !salvageEligible || toolEmitted) {
        events.add(new StreamEvent.TextDelta(chunk));
        textEmitted = true;
        return;
      }
      contentHold.append(chunk);
      if (!looksLikeHeldToolJson(contentHold.toString())) {
        events.add(new StreamEvent.TextDelta(contentHold.toString()));
        contentHold.setLength(0);
        textEmitted = true;
        salvageEligible = false;
      }
    }

    private void consumeStructuredTool(JsonNode call) {
      if (!toolEmitted && !contentHold.isEmpty()) {
        events.add(new StreamEvent.TextDelta(contentHold.toString()));
        contentHold.setLength(0);
        textEmitted = true;
      }
      salvageEligible = false;
      int index = call.path("index").asInt(0);
      JsonNode function = call.path("function");
      if (activeToolIndex == null || activeToolIndex != index) {
        closeActiveTool();
        String id = call.path("id").asText("call_native_" + nativeSequence++);
        String name = function.path("name").asText();
        events.add(new StreamEvent.ToolUseStart(id, name));
        activeToolIndex = index;
        toolEmitted = true;
      }
      JsonNode arguments = function.path("arguments");
      if (arguments.isTextual() && !arguments.textValue().isEmpty()) {
        events.add(new StreamEvent.ToolUseDelta(arguments.textValue()));
      }
    }

    void complete() {
      if (completed) return;
      completed = true;
      closeActiveTool();
      boolean contentProduced = consumeContent();
      if (!contentProduced && !toolEmitted && !textEmitted && contentSeen) {
        events.add(new StreamEvent.TextDelta(EMPTY_RESPONSE));
        textEmitted = true;
      }
      if ((done || stopReason != StopReason.UNSPECIFIED) && !hasFinishedOrError()) {
        events.add(new StreamEvent.Finished(
            toolEmitted ? StopReason.TOOL_USE : stopReason));
      }
    }

    private boolean consumeContent() {
      if (contentHold.isEmpty()) return textEmitted;
      String all = contentHold.toString();
      contentHold.setLength(0);
      if (!looksLikeHeldToolJson(all)) {
        events.add(new StreamEvent.TextDelta(all));
        textEmitted = true;
        return !all.isEmpty();
      }
      SalvageResult result = salvage(all);
      if (result == SalvageResult.PLAIN_TEXT) {
        events.add(new StreamEvent.TextDelta(all));
        textEmitted = true;
        return true;
      }
      return result == SalvageResult.TOOL_EMITTED;
    }

    List<StreamEvent> drain() {
      if (drainPosition == events.size()) return List.of();
      List<StreamEvent> result = List.copyOf(events.subList(drainPosition, events.size()));
      drainPosition = events.size();
      return result;
    }

    List<StreamEvent> allEvents() {
      return List.copyOf(events);
    }

    private SalvageResult salvage(String original) {
      String candidate = stripWrappers(original.trim());
      if (candidate.isBlank()) return SalvageResult.CONSUMED;
      List<JsonNode> roots;
      try {
        roots = readRoots(candidate);
      } catch (JsonProcessingException exception) {
        return SalvageResult.CONSUMED;
      }
      boolean sawToolShape = false;
      boolean emitted = false;
      for (JsonNode root : roots) {
        for (JsonNode item : flatten(root)) {
          String name = item.path("name").asText(item.path("function").asText(""));
          JsonNode arguments = item.path("arguments");
          if (name.isEmpty() || arguments.isMissingNode()) {
            return SalvageResult.PLAIN_TEXT;
          }
          sawToolShape = true;
          if (!knownTools.contains(name) || SWALLOWED_META_TOOLS.contains(name)) continue;
          events.add(new StreamEvent.ToolUseStart("call_salvaged_" + salvagedSequence++, name));
          events.add(new StreamEvent.ToolUseDelta(compact(arguments)));
          events.add(new StreamEvent.ToolUseEnd());
          toolEmitted = true;
          emitted = true;
        }
      }
      if (!sawToolShape) return SalvageResult.PLAIN_TEXT;
      return emitted ? SalvageResult.TOOL_EMITTED : SalvageResult.CONSUMED;
    }

    private void closeActiveTool() {
      if (activeToolIndex == null) return;
      events.add(new StreamEvent.ToolUseEnd());
      activeToolIndex = null;
    }

    private boolean hasFinishedOrError() {
      return events.stream().anyMatch(event ->
          event instanceof StreamEvent.Finished || event instanceof StreamEvent.Error);
    }
  }

  private enum SalvageResult { PLAIN_TEXT, TOOL_EMITTED, CONSUMED }

  private static boolean looksLikeHeldToolJson(String text) {
    String trimmed = text.stripLeading();
    return trimmed.startsWith("{") || trimmed.startsWith("[")
        || trimmed.startsWith("<tool_call>") || trimmed.startsWith("```json");
  }

  private static String stripWrappers(String value) {
    String result = value;
    if (result.startsWith("<tool_call>")) result = result.substring("<tool_call>".length()).trim();
    if (result.endsWith("</tool_call>")) {
      result = result.substring(0, result.length() - "</tool_call>".length()).trim();
    }
    if (result.startsWith("```json")) result = result.substring("```json".length()).trim();
    else if (result.startsWith("```")) result = result.substring(3).trim();
    if (result.endsWith("```")) result = result.substring(0, result.length() - 3).trim();
    return result;
  }

  private static List<JsonNode> readRoots(String input) throws JsonProcessingException {
    var roots = new ArrayList<JsonNode>();
    try (com.fasterxml.jackson.databind.MappingIterator<JsonNode> values =
        JSON.readerFor(JsonNode.class).readValues(input)) {
      while (values.hasNextValue()) roots.add(values.nextValue());
    } catch (java.io.IOException exception) {
      if (exception instanceof JsonProcessingException jsonException) throw jsonException;
      throw new IllegalStateException(exception);
    }
    return roots;
  }

  private static List<JsonNode> flatten(JsonNode root) {
    if (!root.isArray()) return List.of(root);
    var values = new ArrayList<JsonNode>();
    root.forEach(values::add);
    return Collections.unmodifiableList(values);
  }
}
