package com.github.skanga.ajent.tools.args;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Alias-aware, coercing JSON argument reader matching mcp-cpp. */
public final class ArgReader {
  private static final Pattern INTEGER_PREFIX = Pattern.compile("^[+-]?\\d+");
  private final JsonNode arguments;

  public ArgReader(JsonNode arguments) {
    this.arguments = arguments;
  }

  public boolean has(String key) { return raw(key) != null; }

  public String string(String key, String fallback) {
    JsonNode value = raw(key);
    if (value == null || value.isNull()) return fallback;
    if (value.isTextual()) return value.textValue();
    if (value.isArray()) {
      var result = new StringBuilder();
      for (JsonNode element : value) {
        if (!result.isEmpty()) result.append('\n');
        result.append(element.isTextual() ? element.textValue() : element.toString());
      }
      return result.toString();
    }
    return value.toString();
  }

  public Optional<String> requiredString(String key) {
    JsonNode value = raw(key);
    if (value == null || value.isNull()) return Optional.empty();
    String result = value.isTextual() ? value.textValue() : value.toString();
    return result.isEmpty() ? Optional.empty() : Optional.of(result);
  }

  public int integer(String key, int fallback) {
    JsonNode value = raw(key);
    if (value == null || value.isNull()) return fallback;
    if (value.isIntegralNumber() || value.isFloatingPointNumber()) return value.intValue();
    if (!value.isTextual()) return fallback;
    var match = INTEGER_PREFIX.matcher(value.textValue());
    if (!match.find()) return fallback;
    try {
      return Integer.parseInt(match.group());
    } catch (NumberFormatException exception) {
      return fallback;
    }
  }

  public boolean bool(String key, boolean fallback) {
    JsonNode value = raw(key);
    if (value == null || value.isNull()) return fallback;
    if (value.isBoolean()) return value.booleanValue();
    if (value.isIntegralNumber()) return value.intValue() != 0;
    if (!value.isTextual()) return fallback;
    return switch (value.textValue().toLowerCase(Locale.ROOT)) {
      case "true", "1", "yes" -> true;
      case "false", "0", "no" -> false;
      default -> fallback;
    };
  }

  public JsonNode raw(String key) {
    if (arguments == null || !arguments.isObject()) return null;
    JsonNode exact = arguments.get(key);
    if (exact != null) return exact;
    for (String alias : aliases(key)) {
      JsonNode value = arguments.get(alias);
      if (value != null) return value;
    }
    return null;
  }

  private static String[] aliases(String key) {
    return switch (key) {
      case "path" -> new String[] {"file_path", "filepath", "filename", "file", "dir",
          "directory", "target", "pathname"};
      case "file_path" -> new String[] {"path", "filepath", "filename", "file", "target",
          "pathname"};
      case "old_string" -> new String[] {"old_text", "old_str", "oldStr", "old", "search",
          "find", "from"};
      case "new_string" -> new String[] {"new_text", "new_str", "newStr", "new", "replace",
          "replacement", "to"};
      case "content" -> new String[] {"file_text", "text", "file_content", "contents", "body",
          "data", "code"};
      case "offset" -> new String[] {"start_line", "start", "from_line"};
      case "limit" -> new String[] {"end_line", "num_lines", "max_lines", "count", "line_count"};
      case "cd" -> new String[] {"cwd", "workdir", "working_directory", "directory"};
      case "command" -> new String[] {"cmd", "shell_command", "shell", "script", "run", "cmdline"};
      case "pattern" -> new String[] {"query", "q", "regex", "search", "term", "glob", "match", "pat"};
      case "query" -> new String[] {"q", "search", "term", "text", "prompt", "question"};
      case "url" -> new String[] {"uri", "link", "address", "href"};
      default -> new String[0];
    };
  }
}
