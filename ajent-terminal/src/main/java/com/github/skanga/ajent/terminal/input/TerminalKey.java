package com.github.skanga.ajent.terminal.input;

/** Normalized terminal key plus protocol-independent modifiers. */
public record TerminalKey(Key key, Modifiers modifiers) {
  public TerminalKey {
    java.util.Objects.requireNonNull(key, "key");
    java.util.Objects.requireNonNull(modifiers, "modifiers");
  }

  public TerminalKey(Key key) {
    this(key, Modifiers.NONE);
  }

  public sealed interface Key permits CharacterKey, SpecialKey {}

  public record CharacterKey(int codePoint) implements Key {
    public CharacterKey {
      if (!Character.isValidCodePoint(codePoint)) {
        throw new IllegalArgumentException("invalid Unicode code point");
      }
    }
  }

  public enum SpecialKey implements Key {
    ENTER, BACKSPACE, LEFT, RIGHT, HOME, END, UP, DOWN, ESCAPE, TAB, BACK_TAB,
    INSERT, DELETE, PAGE_UP, PAGE_DOWN, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12
  }

  public record Modifiers(boolean ctrl, boolean alt, boolean shift) {
    public static final Modifiers NONE = new Modifiers(false, false, false);
  }
}
