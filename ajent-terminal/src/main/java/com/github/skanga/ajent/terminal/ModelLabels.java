package com.github.skanga.ajent.terminal;

import java.util.Set;

/** Human-readable model labels matching Ajent's turn-header normalization. */
public final class ModelLabels {
  private static final Set<String> LOWERCASE_ACRONYMS = Set.of("gpt", "glm", "sql", "tts", "vl");

  private ModelLabels() {}

  public static String pretty(String modelId) {
    String id = modelId == null ? "" : modelId;
    int extendedMarker = id.indexOf("[1m]");
    if (extendedMarker >= 0) {
      return pretty(id.substring(0, extendedMarker) + id.substring(extendedMarker + 4));
    }
    int slash = id.lastIndexOf('/');
    if (slash >= 0) id = id.substring(slash + 1);
    String tag = "";
    int colon = id.indexOf(':');
    if (colon >= 0) {
      tag = id.substring(colon + 1);
      id = id.substring(0, colon);
    }
    if (tag.equals("latest")) tag = "";

    StringBuilder output = new StringBuilder(id.length() + tag.length() + 1);
    int wordStart = 0;
    for (int index = 0; index <= id.length(); index++) {
      boolean boundary = index == id.length() || id.charAt(index) == '-'
          || id.charAt(index) == '_' || id.charAt(index) == ' ';
      if (!boundary) continue;
      if (index > wordStart) {
        if (!output.isEmpty()) output.append(' ');
        appendWord(output, id.substring(wordStart, index));
      }
      wordStart = index + 1;
    }
    if (output.isEmpty()) output.append(id);
    if (!tag.isEmpty()) output.append(' ').append(tag);
    return output.toString();
  }

  private static void appendWord(StringBuilder output, String word) {
    boolean allCaps = true;
    boolean hasLetter = false;
    for (int index = 0; index < word.length(); index++) {
      char value = word.charAt(index);
      if (isAsciiLetter(value)) {
        hasLetter = true;
        if (!isUpper(value)) allCaps = false;
      }
    }
    if (hasLetter && allCaps && word.length() <= 4) {
      output.append(word);
      return;
    }
    if (LOWERCASE_ACRONYMS.contains(word.toLowerCase(java.util.Locale.ROOT))) {
      for (int index = 0; index < word.length(); index++) {
        char value = word.charAt(index);
        output.append(isLower(value) ? (char) (value - 'a' + 'A') : value);
      }
      return;
    }
    if (isDigit(word.charAt(0))) {
      for (int index = 0; index < word.length(); index++) {
        char value = word.charAt(index);
        output.append(isUpper(value) ? (char) (value - 'A' + 'a') : value);
      }
      return;
    }
    if (word.length() >= 2 && (word.charAt(0) == 'o' || word.charAt(0) == 'O')
        && isDigit(word.charAt(1))) {
      output.append('o').append(word, 1, word.length());
      return;
    }
    boolean cased = false;
    for (int index = 0; index < word.length(); index++) {
      char value = word.charAt(index);
      if (!cased && isLower(value)) {
        value = (char) (value - 'a' + 'A');
        cased = true;
      } else if (isAsciiLetter(value)) {
        cased = true;
      }
      output.append(value);
    }
  }

  private static boolean isAsciiLetter(char value) { return isLower(value) || isUpper(value); }
  private static boolean isUpper(char value) { return value >= 'A' && value <= 'Z'; }
  private static boolean isLower(char value) { return value >= 'a' && value <= 'z'; }
  private static boolean isDigit(char value) { return value >= '0' && value <= '9'; }
}
