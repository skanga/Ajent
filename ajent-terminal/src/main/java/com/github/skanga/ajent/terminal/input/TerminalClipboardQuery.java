package com.github.skanga.ajent.terminal.input;

import java.util.Map;
import java.util.Objects;

/** Exact Maya OSC clipboard-read query selection used by AgenTTY. */
public final class TerminalClipboardQuery {
  public static final String OSC_52_TEXT = "\u001b]52;c;?\u001b\\";
  public static final String OSC_5522_KITTY = "\u001b]5522;type=read;"
      + "aW1hZ2UvcG5nIGltYWdlL2pwZWcgaW1hZ2Uvd2VicCBpbWFnZS9naWYgdGV4dC9wbGFpbg=="
      + "\u001b\\";

  private TerminalClipboardQuery() {}

  public static String forEnvironment(Map<String, String> environment) {
    Objects.requireNonNull(environment, "environment");
    String window = environment.getOrDefault("KITTY_WINDOW_ID", "");
    String term = environment.getOrDefault("TERM", "");
    return !window.isEmpty() || term.contains("kitty") ? OSC_5522_KITTY : OSC_52_TEXT;
  }
}
