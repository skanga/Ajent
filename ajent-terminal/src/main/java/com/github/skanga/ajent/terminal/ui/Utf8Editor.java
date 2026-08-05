package com.github.skanga.ajent.terminal.ui;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Immutable text field whose cursor uses Ajent's UTF-8 byte offsets. */
public record Utf8Editor(String text, int cursor) {
  public Utf8Editor {
    Objects.requireNonNull(text, "text");
    int bytes = text.getBytes(StandardCharsets.UTF_8).length;
    if (cursor < 0 || cursor > bytes || utf16Index(text, cursor) < 0) {
      throw new IllegalArgumentException("cursor is not on a UTF-8 boundary");
    }
  }

  public Utf8Editor() {
    this("", 0);
  }

  public Utf8Editor insertCodePoint(int codePoint) {
    return insert(new String(Character.toChars(codePoint)));
  }

  public Utf8Editor insert(String value) {
    Objects.requireNonNull(value, "value");
    int index = utf16Index(text, cursor);
    return new Utf8Editor(text.substring(0, index) + value + text.substring(index),
        cursor + value.getBytes(StandardCharsets.UTF_8).length);
  }

  public Utf8Editor backspace() {
    if (cursor == 0 || text.isEmpty()) return this;
    int index = utf16Index(text, cursor);
    int previous = text.offsetByCodePoints(index, -1);
    String removed = text.substring(previous, index);
    return new Utf8Editor(text.substring(0, previous) + text.substring(index),
        cursor - removed.getBytes(StandardCharsets.UTF_8).length);
  }

  public Utf8Editor left() {
    if (cursor == 0) return this;
    int index = utf16Index(text, cursor);
    int previous = text.offsetByCodePoints(index, -1);
    return new Utf8Editor(text,
        cursor - text.substring(previous, index).getBytes(StandardCharsets.UTF_8).length);
  }

  public Utf8Editor right() {
    int index = utf16Index(text, cursor);
    if (index == text.length()) return this;
    int next = text.offsetByCodePoints(index, 1);
    return new Utf8Editor(text,
        cursor + text.substring(index, next).getBytes(StandardCharsets.UTF_8).length);
  }

  private static int utf16Index(String value, int byteOffset) {
    int bytes = 0;
    for (int index = 0; index < value.length();) {
      if (bytes == byteOffset) return index;
      int codePoint = value.codePointAt(index);
      bytes += new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
      index += Character.charCount(codePoint);
      if (bytes > byteOffset) return -1;
    }
    return bytes == byteOffset ? value.length() : -1;
  }
}
