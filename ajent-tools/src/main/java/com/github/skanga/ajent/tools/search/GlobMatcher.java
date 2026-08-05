package com.github.skanga.ajent.tools.search;

/** Small Ajent-compatible filename glob matcher. */
public final class GlobMatcher {
  private GlobMatcher() {}

  public static boolean matches(String pattern, String name) {
    return matches(pattern, 0, name, 0);
  }

  private static boolean matches(String pattern, int patternIndex, String name, int nameIndex) {
    while (nameIndex < name.length()) {
      if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
          patternIndex++;
        }
        if (patternIndex == pattern.length()) return true;
        for (int index = nameIndex; index <= name.length(); index++) {
          if (matches(pattern, patternIndex, name, index)) return true;
        }
        return false;
      }
      if (patternIndex >= pattern.length()) return false;
      char token = pattern.charAt(patternIndex);
      if (token == '?') {
        patternIndex++;
        nameIndex++;
      } else if (token == '[') {
        int close = pattern.indexOf(']', patternIndex + 1);
        if (close < 0) {
          if (!equal(token, name.charAt(nameIndex))) return false;
          patternIndex++;
          nameIndex++;
          continue;
        }
        boolean negated = patternIndex + 1 < close && pattern.charAt(patternIndex + 1) == '!';
        boolean hit = inClass(pattern, patternIndex + 1 + (negated ? 1 : 0), close,
            name.charAt(nameIndex));
        if (hit == negated) return false;
        patternIndex = close + 1;
        nameIndex++;
      } else {
        if (!equal(token, name.charAt(nameIndex))) return false;
        patternIndex++;
        nameIndex++;
      }
    }
    while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') patternIndex++;
    return patternIndex == pattern.length();
  }

  private static boolean inClass(String pattern, int start, int end, char value) {
    int index = start;
    while (index < end) {
      if (index + 2 < end && pattern.charAt(index + 1) == '-') {
        if (value >= pattern.charAt(index) && value <= pattern.charAt(index + 2)) return true;
        index += 3;
      } else {
        if (equal(pattern.charAt(index), value)) return true;
        index++;
      }
    }
    return false;
  }

  private static boolean equal(char left, char right) {
    return System.getProperty("os.name", "").startsWith("Windows")
        ? Character.toLowerCase(left) == Character.toLowerCase(right) : left == right;
  }
}
