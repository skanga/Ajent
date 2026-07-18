package com.github.skanga.ajent.terminal.input;

import java.util.Objects;

/** Events emitted by the incremental terminal byte decoder. */
public sealed interface TerminalEvent {
  record Key(TerminalKey value) implements TerminalEvent {
    public Key { Objects.requireNonNull(value, "value"); }
  }

  final class Paste implements TerminalEvent {
    private final byte[] content;

    public Paste(byte[] content) {
      this.content = Objects.requireNonNull(content, "content").clone();
    }

    public Paste(String content) {
      this(Objects.requireNonNull(content, "content")
          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public byte[] content() { return content.clone(); }
    public String text() { return new String(content, java.nio.charset.StandardCharsets.UTF_8); }

    @Override public boolean equals(Object other) {
      return other instanceof Paste paste && java.util.Arrays.equals(content, paste.content);
    }

    @Override public int hashCode() { return java.util.Arrays.hashCode(content); }

    @Override public String toString() { return "Paste[content=" + content.length + " bytes]"; }
  }

  record Focus(boolean focused) implements TerminalEvent {}

  record Mouse(Button button, Kind kind, int x, int y, TerminalKey.Modifiers modifiers)
      implements TerminalEvent {
    public Mouse {
      Objects.requireNonNull(button, "button");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(modifiers, "modifiers");
      if (x < 1 || y < 1) throw new IllegalArgumentException("mouse coordinates are one-based");
    }
  }

  enum Button { LEFT, MIDDLE, RIGHT, NONE, SCROLL_UP, SCROLL_DOWN }
  enum Kind { PRESS, RELEASE, MOVE }
}
