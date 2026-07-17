package com.github.skanga.ajent.provider.ollama;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure dedicated Ollama /api/chat NDJSON parser. */
public final class OllamaStreamParser {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern THINK_BLOCK = Pattern.compile("(?s)<think>.*?</think>");
  private static final Set<String> SWALLOWED_TOOLS =
      Set.of("remember", "forget", "wipe_memory", "skill");

  private OllamaStreamParser() {}

  public static List<StreamEvent> parseNdjson(
      String bytes, Set<String> knownTools, boolean jsonProtocol) {
    var events = new ArrayList<StreamEvent>();
    var chunks = new ArrayList<String>();
    boolean done = false;
    StopReason stopReason = StopReason.UNSPECIFIED;
    int nativeSequence = 0;
    boolean toolEmitted = false;
    boolean sawError = false;

    for (String line : bytes.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
      if (line.isBlank()) continue;
      JsonNode root;
      try {
        root = JSON.readTree(line);
      } catch (JsonProcessingException exception) {
        events.add(new StreamEvent.Error("invalid provider JSON: " + exception.getOriginalMessage()));
        sawError = true;
        continue;
      }
      JsonNode error = root.path("error");
      if (!error.isMissingNode()) {
        events.add(new StreamEvent.Error(
            error.path("message").asText(error.isTextual() ? error.textValue() : "provider error")));
        sawError = true;
      }
      JsonNode message = root.path("message");
      JsonNode content = message.path("content");
      if (content.isTextual() && !content.textValue().isEmpty()) chunks.add(content.textValue());
      JsonNode calls = message.path("tool_calls");
      if (calls.isArray()) {
        for (JsonNode call : calls) {
          JsonNode function = call.path("function");
          String id = call.path("id").asText("call_native_" + nativeSequence++);
          String name = function.path("name").asText();
          JsonNode arguments = repairArguments(name, function.path("arguments"));
          emitTool(events, id, name, arguments);
          toolEmitted = true;
        }
      }
      if (root.path("prompt_eval_count").canConvertToInt()
          || root.path("eval_count").canConvertToInt()) {
        events.add(new StreamEvent.Usage(
            root.path("prompt_eval_count").asInt(), root.path("eval_count").asInt()));
      }
      if (root.path("done").asBoolean(false)) {
        done = true;
        stopReason = "length".equals(root.path("done_reason").asText())
            ? StopReason.MAX_TOKENS : StopReason.END_TURN;
      }
    }

    String content = stripThinkBlocks(String.join("", chunks));
    ContentResult contentResult = jsonProtocol
        ? consumeJsonProtocol(content, chunks.size(), knownTools, events)
        : consumeNativeContent(content, knownTools, events);
    toolEmitted |= contentResult.toolEmitted();
    if (!contentResult.visibleContent() && !toolEmitted && !sawError
        && (!content.isEmpty() || jsonProtocol)) {
      events.add(new StreamEvent.TextDelta("(empty response)"));
    }
    if (done && !sawError) {
      events.add(new StreamEvent.Finished(toolEmitted ? StopReason.TOOL_USE : stopReason));
    }
    return List.copyOf(events);
  }

  private static ContentResult consumeNativeContent(
      String content, Set<String> knownTools, List<StreamEvent> events) {
    if (content.isEmpty()) return ContentResult.NONE;
    if (knownTools.isEmpty()) {
      events.add(new StreamEvent.TextDelta(content));
      return ContentResult.VISIBLE;
    }
    ToolCandidate candidate = findToolCandidate(content);
    if (candidate != null) {
      if (SWALLOWED_TOOLS.contains(candidate.name())) return ContentResult.NONE;
      if (knownTools.contains(candidate.name())) {
        emitTool(events, "call_salvaged_0", candidate.name(),
            repairArguments(candidate.name(), candidate.arguments()));
        return ContentResult.TOOL;
      }
    }
    events.add(new StreamEvent.TextDelta(content));
    return ContentResult.VISIBLE;
  }

  private static ContentResult consumeJsonProtocol(
      String content, int chunkCount, Set<String> knownTools, List<StreamEvent> events) {
    if (content.isBlank()) return ContentResult.NONE;
    JsonNode object = firstJsonObject(content);
    if (object == null || !object.isObject()) {
      events.add(new StreamEvent.TextDelta(content));
      return ContentResult.VISIBLE;
    }
    String rawName = firstText(object, "tool_name", "tool", "name");
    JsonNode rawArguments = firstNode(object, "tool_args", "args", "arguments");
    if (rawName.isEmpty() || rawArguments == null) {
      events.add(new StreamEvent.TextDelta(content));
      return ContentResult.VISIBLE;
    }
    String name = rawName;
    String action = "";
    int separator = rawName.indexOf(':');
    if (separator >= 0) {
      name = rawName.substring(0, separator);
      action = rawName.substring(separator + 1);
    }
    if ("response".equals(name)) {
      String response = firstText(rawArguments, "text", "response");
      if (response.isEmpty()) response = thoughts(object.path("thoughts"));
      if (response.isEmpty()) return ContentResult.NONE;
      emitProgressiveText(events, response, chunkCount);
      return ContentResult.VISIBLE;
    }
    if (!knownTools.contains(name) || SWALLOWED_TOOLS.contains(name)) return ContentResult.NONE;
    JsonNode arguments = repairArguments(name, rawArguments);
    if (!action.isEmpty() && arguments instanceof ObjectNode objectArguments
        && !objectArguments.has("action")) {
      objectArguments.put("action", action);
    }
    emitTool(events, "call_salvaged_0", name, arguments);
    return ContentResult.TOOL;
  }

  private static void emitProgressiveText(
      List<StreamEvent> events, String response, int chunkCount) {
    if (chunkCount > 1 && response.length() > 1) {
      int split = response.offsetByCodePoints(0, response.codePointCount(0, response.length()) / 2);
      events.add(new StreamEvent.TextDelta(response.substring(0, split)));
      events.add(new StreamEvent.TextDelta(response.substring(split)));
    } else {
      events.add(new StreamEvent.TextDelta(response));
    }
  }

  private static void emitTool(
      List<StreamEvent> events, String id, String name, JsonNode arguments) {
    events.add(new StreamEvent.ToolUseStart(id, name));
    events.add(new StreamEvent.ToolUseDelta(compact(arguments)));
    events.add(new StreamEvent.ToolUseEnd());
  }

  private static ToolCandidate findToolCandidate(String content) {
    for (JsonNode object : jsonObjects(content)) {
      String name = firstText(object, "name", "function", "tool_name", "tool");
      JsonNode arguments = firstNode(object, "arguments", "tool_args", "args");
      if (!name.isEmpty() && arguments != null) return new ToolCandidate(name, arguments);
    }
    return null;
  }

  private static JsonNode firstJsonObject(String content) {
    List<JsonNode> objects = jsonObjects(content);
    return objects.isEmpty() ? null : objects.getFirst();
  }

  private static List<JsonNode> jsonObjects(String content) {
    var objects = new ArrayList<JsonNode>();
    for (int start = content.indexOf('{'); start >= 0; start = content.indexOf('{', start + 1)) {
      int end = balancedObjectEnd(content, start);
      if (end < 0) continue;
      try {
        objects.add(JSON.readTree(content.substring(start, end)));
        start = end - 1;
      } catch (JsonProcessingException ignored) {
        // Try the next opening brace; narration and code may contain braces.
      }
    }
    return objects;
  }

  private static int balancedObjectEnd(String text, int start) {
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (int index = start; index < text.length(); index++) {
      char value = text.charAt(index);
      if (inString) {
        if (escaped) escaped = false;
        else if (value == '\\') escaped = true;
        else if (value == '"') inString = false;
        continue;
      }
      if (value == '"') inString = true;
      else if (value == '{') depth++;
      else if (value == '}' && --depth == 0) return index + 1;
    }
    return -1;
  }

  private static JsonNode repairArguments(String tool, JsonNode original) {
    ObjectNode arguments = original instanceof ObjectNode object
        ? object.deepCopy() : JSON.createObjectNode();
    Map<String, List<String>> aliases = switch (tool) {
      case "bash", "diagnostics" -> Map.of("command", List.of("cmd", "shell", "script", "run", "cmdline"));
      case "read", "list_dir", "find_definition" -> Map.of(
          "path", List.of("file", "filepath", "file_path", "filename", "dir", "directory", "target"));
      case "write" -> orderedAliases(
          "file_path", List.of("path", "file", "filepath", "filename", "target"),
          "content", List.of("text", "body", "data", "contents", "code"));
      case "edit" -> Map.of(
          "path", List.of("file", "filepath", "file_path", "filename", "target"),
          "old_text", List.of("old", "old_string", "search", "find", "from"),
          "new_text", List.of("new", "new_string", "replace", "replacement", "to"));
      case "grep" -> Map.of(
          "pattern", List.of("query", "q", "regex", "search", "text", "term"),
          "path", List.of("dir", "directory", "root", "file"));
      case "glob" -> Map.of(
          "pattern", List.of("query", "q", "glob", "pat", "match"),
          "path", List.of("dir", "directory", "root"));
      case "web_fetch" -> Map.of("url", List.of("uri", "link", "address", "href"));
      case "web_search", "search_docs" -> Map.of(
          "query", List.of("q", "search", "term", "text", "prompt", "question"));
      default -> Map.of();
    };
    for (var entry : aliases.entrySet()) {
      if (arguments.has(entry.getKey())) continue;
      for (String alias : entry.getValue()) {
        if (!arguments.has(alias)) continue;
        arguments.set(entry.getKey(), arguments.get(alias));
        arguments.remove(alias);
        break;
      }
    }
    return arguments;
  }

  private static Map<String, List<String>> orderedAliases(
      String firstKey, List<String> firstAliases, String secondKey, List<String> secondAliases) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    result.put(firstKey, firstAliases);
    result.put(secondKey, secondAliases);
    return result;
  }

  private static String firstText(JsonNode object, String... keys) {
    for (String key : keys) {
      JsonNode value = object.path(key);
      if (value.isTextual()) return value.textValue();
    }
    return "";
  }

  private static JsonNode firstNode(JsonNode object, String... keys) {
    for (String key : keys) if (object.has(key)) return object.get(key);
    return null;
  }

  private static String thoughts(JsonNode thoughts) {
    if (!thoughts.isArray()) return "";
    var values = new ArrayList<String>();
    thoughts.forEach(value -> { if (value.isTextual()) values.add(value.textValue()); });
    return String.join("\n", values);
  }

  private static String stripThinkBlocks(String content) {
    return THINK_BLOCK.matcher(content).replaceAll("");
  }

  private static String compact(JsonNode node) {
    try {
      return JSON.writeValueAsString(node);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize Ollama arguments", exception);
    }
  }

  private record ToolCandidate(String name, JsonNode arguments) {}
  private record ContentResult(boolean visibleContent, boolean toolEmitted) {
    private static final ContentResult NONE = new ContentResult(false, false);
    private static final ContentResult VISIBLE = new ContentResult(true, false);
    private static final ContentResult TOOL = new ContentResult(false, true);
  }
}
