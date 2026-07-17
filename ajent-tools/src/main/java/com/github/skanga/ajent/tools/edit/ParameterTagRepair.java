package com.github.skanga.ajent.tools.edit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Repairs XML parameter tags leaked into otherwise valid JSON tool arguments. */
public final class ParameterTagRepair {
  private static final String OPEN = "<parameter name=\"";
  private static final String CLOSE = "</parameter>";

  private ParameterTagRepair() {}

  public static boolean repair(String toolName, ObjectNode arguments) {
    if (!("edit".equals(toolName) || "write".equals(toolName))) return false;
    boolean marker = false;
    var fields = arguments.fields();
    while (fields.hasNext()) {
      JsonNode value = fields.next().getValue();
      if (value.isTextual() && value.textValue().contains(OPEN)) { marker = true; break; }
    }
    if (!marker) return false;
    Map<String, String> tags = extractTags(arguments);
    if (tags.isEmpty()) return false;
    if ("edit".equals(toolName)) return repairEdit(arguments, tags);
    return repairWrite(arguments, tags);
  }

  private static boolean repairEdit(ObjectNode arguments, Map<String, String> tags) {
    Optional<String> oldText = pick(arguments, tags, "old_text", "old_string");
    if (oldText.isEmpty()) return false;
    Optional<String> newText = pick(arguments, tags, "new_text", "new_string");
    Optional<String> path = pick(arguments, tags, "path", "file_path", "filepath", "filename");
    ObjectNode edit = JsonNodeFactory.instance.objectNode();
    edit.put("old_text", oldText.orElseThrow());
    edit.put("new_text", newText.orElse(""));
    if (tags.containsKey("line")) {
      try { edit.put("line", Integer.parseInt(tags.get("line"))); }
      catch (NumberFormatException ignored) { /* Reference ignores malformed line hints. */ }
    } else if (arguments.path("line").isIntegralNumber()) {
      edit.set("line", arguments.path("line"));
    }
    ObjectNode rebuilt = commonFields(arguments, path);
    ArrayNode edits = rebuilt.putArray("edits");
    edits.add(edit);
    replace(arguments, rebuilt);
    return true;
  }

  private static boolean repairWrite(ObjectNode arguments, Map<String, String> tags) {
    Optional<String> content = pick(arguments, tags, "content", "file_text", "text",
        "file_content", "contents", "body", "data");
    if (content.isEmpty()) return false;
    Optional<String> path = pick(arguments, tags, "path", "file_path", "filepath", "filename");
    ObjectNode rebuilt = commonFields(arguments, path);
    rebuilt.put("content", content.orElseThrow());
    replace(arguments, rebuilt);
    return true;
  }

  private static ObjectNode commonFields(ObjectNode arguments, Optional<String> path) {
    ObjectNode rebuilt = JsonNodeFactory.instance.objectNode();
    path.ifPresent(value -> rebuilt.put("path", value));
    JsonNode description = arguments.get("display_description");
    if (description != null && description.isTextual()) rebuilt.set("display_description", description);
    return rebuilt;
  }

  private static Optional<String> pick(
      ObjectNode arguments, Map<String, String> tags, String... names) {
    for (String name : names) {
      JsonNode value = arguments.get(name);
      if (value != null && value.isTextual() && !value.textValue().isEmpty()
          && !value.textValue().contains(OPEN)) return Optional.of(value.textValue());
    }
    for (String name : names) {
      String value = tags.get(name);
      if (value != null && !value.isEmpty()) return Optional.of(value);
    }
    return Optional.empty();
  }

  private static Map<String, String> extractTags(ObjectNode arguments) {
    Map<String, String> tags = new LinkedHashMap<>();
    arguments.fields().forEachRemaining(field -> {
      if (!field.getValue().isTextual()) return;
      String value = field.getValue().textValue();
      int position = 0;
      while ((position = value.indexOf(OPEN, position)) >= 0) {
        int nameStart = position + OPEN.length();
        int nameEnd = value.indexOf('"', nameStart);
        if (nameEnd < 0) break;
        int greaterThan = value.indexOf('>', nameEnd);
        if (greaterThan < 0) break;
        int valueStart = greaterThan + 1;
        int next = value.indexOf(OPEN, valueStart);
        int close = value.indexOf(CLOSE, valueStart);
        int valueEnd = Math.min(next < 0 ? value.length() : next, close < 0 ? value.length() : close);
        tags.putIfAbsent(value.substring(nameStart, nameEnd), value.substring(valueStart, valueEnd));
        position = valueEnd;
      }
    });
    return tags;
  }

  private static void replace(ObjectNode destination, ObjectNode source) {
    destination.removeAll();
    destination.setAll(source);
  }
}
