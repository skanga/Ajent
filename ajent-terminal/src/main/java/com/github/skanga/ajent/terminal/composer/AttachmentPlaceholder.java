package com.github.skanga.ajent.terminal.composer;

public final class AttachmentPlaceholder {
  public static final char SENTINEL = '\u0001';

  private AttachmentPlaceholder() {}

  public static String make(long index) {
    if (index < 0) {
      throw new IllegalArgumentException("index must not be negative");
    }
    return SENTINEL + "ATT:" + index + SENTINEL;
  }

  public static int lengthAt(String text, int position) {
    if (position < 0 || position >= text.length() || text.charAt(position) != SENTINEL) {
      return 0;
    }
    if (position + 7 > text.length() || !text.startsWith("\u0001ATT:", position)) {
      return 0;
    }
    int cursor = position + 5;
    if (cursor >= text.length() || !isDigit(text.charAt(cursor))) {
      return 0;
    }
    while (cursor < text.length() && isDigit(text.charAt(cursor))) {
      cursor++;
    }
    if (cursor >= text.length() || text.charAt(cursor) != SENTINEL) {
      return 0;
    }
    return cursor + 1 - position;
  }

  public static int lengthEndingAt(String text, int position) {
    if (position <= 0 || position > text.length() || text.charAt(position - 1) != SENTINEL
        || position < 2) {
      return 0;
    }
    int cursor = position - 2;
    while (cursor > 0 && isDigit(text.charAt(cursor))) {
      cursor--;
    }
    if (text.charAt(cursor) != ':' || cursor < 4
        || !text.regionMatches(cursor - 4, "\u0001ATT", 0, 4)) {
      return 0;
    }
    int start = cursor - 4;
    int forwardLength = lengthAt(text, start);
    return forwardLength == position - start ? forwardLength : 0;
  }

  private static boolean isDigit(char value) {
    return value >= '0' && value <= '9';
  }
}
