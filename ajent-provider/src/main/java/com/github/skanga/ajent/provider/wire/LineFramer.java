package com.github.skanga.ajent.provider.wire;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/** Incrementally splits an arbitrary byte stream into CRLF/LF-delimited UTF-8 lines. */
public final class LineFramer {
  public static final int DEFAULT_COMPACT_THRESHOLD = 64 * 1024;

  private byte[] buffer = new byte[8 * 1024];
  private int size;
  private int readPosition;
  private final int compactThreshold;

  public LineFramer() {
    this(DEFAULT_COMPACT_THRESHOLD);
  }

  public LineFramer(int compactThreshold) {
    if (compactThreshold < 1) {
      throw new IllegalArgumentException("compactThreshold must be positive");
    }
    this.compactThreshold = compactThreshold;
  }

  public void feed(byte[] bytes, Consumer<String> onLine) {
    Objects.requireNonNull(bytes, "bytes");
    Objects.requireNonNull(onLine, "onLine");
    ensureCapacity(size + bytes.length);
    System.arraycopy(bytes, 0, buffer, size, bytes.length);
    size += bytes.length;
    while (true) {
      int newline = findNewline();
      if (newline < 0) break;
      int lineEnd = newline > readPosition && buffer[newline - 1] == '\r'
          ? newline - 1 : newline;
      onLine.accept(new String(
          buffer, readPosition, lineEnd - readPosition, StandardCharsets.UTF_8));
      readPosition = newline + 1;
    }
    if (readPosition >= compactThreshold) compact();
  }

  private int findNewline() {
    for (int index = readPosition; index < size; index++) if (buffer[index] == '\n') return index;
    return -1;
  }

  private void ensureCapacity(int required) {
    if (required <= buffer.length) return;
    int capacity = Math.max(required, Math.multiplyExact(buffer.length, 2));
    buffer = Arrays.copyOf(buffer, capacity);
  }

  private void compact() {
    int remaining = size - readPosition;
    System.arraycopy(buffer, readPosition, buffer, 0, remaining);
    size = remaining;
    readPosition = 0;
  }
}
