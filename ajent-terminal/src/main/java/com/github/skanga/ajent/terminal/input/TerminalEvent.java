package com.github.skanga.ajent.terminal.input;

import java.util.Objects;

/** Events emitted by the incremental terminal byte decoder. */
public sealed interface TerminalEvent {
  record Key(TerminalKey value) implements TerminalEvent {
    public Key { Objects.requireNonNull(value, "value"); }
  }

  record Paste(String content) implements TerminalEvent {
    public Paste { Objects.requireNonNull(content, "content"); }
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
