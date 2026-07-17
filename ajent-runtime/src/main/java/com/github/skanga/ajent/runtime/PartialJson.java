package com.github.skanga.ajent.runtime;

import java.util.ArrayDeque;

/** Safe closer and truncation probe for streamed tool-argument JSON. */
final class PartialJson {
  private PartialJson() {}

  static String close(String raw) {
    var output = new StringBuilder(raw.length() + 16);
    var containers = new ArrayDeque<Character>();
    boolean inString = false;
    boolean escape = false;
    for (int index = 0; index < raw.length(); index++) {
      char value = raw.charAt(index);
      if (inString) {
        if (escape) {
          output.append(value);
          escape = false;
        } else if (value == '\\') {
          output.append(value);
          escape = true;
        } else if (value == '"') {
          output.append(value);
          inString = false;
        } else {
          output.append(value);
        }
        continue;
      }
      switch (value) {
        case '"' -> {
          output.append(value);
          inString = true;
        }
        case '{', '[' -> {
          containers.push(value);
          output.append(value);
        }
        case '}' -> {
          if (!containers.isEmpty() && containers.peek() == '{') containers.pop();
          output.append(value);
        }
        case ']' -> {
          if (!containers.isEmpty() && containers.peek() == '[') containers.pop();
          output.append(value);
        }
        default -> output.append(value);
      }
    }
    if (inString) {
      if (escape && !output.isEmpty()) output.deleteCharAt(output.length() - 1);
      output.append('"');
    }
    while (!containers.isEmpty()) {
      char open = containers.pop();
      if (awaitingValue(output)) output.append("null");
      else stripTrailingComma(output);
      output.append(open == '{' ? '}' : ']');
    }
    if (output.isEmpty()) return "null";
    if (awaitingValue(output)) output.append("null");
    return output.toString();
  }

  static boolean endedInsideString(String raw) {
    boolean inString = false;
    boolean escape = false;
    for (int index = 0; index < raw.length(); index++) {
      char value = raw.charAt(index);
      if (inString) {
        if (escape) escape = false;
        else if (value == '\\') escape = true;
        else if (value == '"') inString = false;
      } else if (value == '"') {
        inString = true;
      }
    }
    return inString;
  }

  private static boolean awaitingValue(StringBuilder value) {
    for (int index = value.length() - 1; index >= 0; index--) {
      char current = value.charAt(index);
      if (!isJsonWhitespace(current)) return current == ':';
    }
    return false;
  }

  private static void stripTrailingComma(StringBuilder value) {
    for (int index = value.length() - 1; index >= 0; index--) {
      char current = value.charAt(index);
      if (isJsonWhitespace(current)) continue;
      if (current == ',') value.deleteCharAt(index);
      return;
    }
  }

  private static boolean isJsonWhitespace(char value) {
    return value == ' ' || value == '\t' || value == '\n' || value == '\r';
  }
}
