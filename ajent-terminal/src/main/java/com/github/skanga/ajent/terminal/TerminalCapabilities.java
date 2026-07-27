package com.github.skanga.ajent.terminal;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Environment-only terminal capability probes matching Maya's runtime heuristics. */
public final class TerminalCapabilities {
  private TerminalCapabilities() {}

  public static boolean synchronizedOutput(Map<String, String> environment) {
    Objects.requireNonNull(environment, "environment");
    if (override(environment.get("AJENT_FORCE_SYNC"))) return true;
    if (override(environment.get("AJENT_NO_SYNC"))) return false;
    String program = environment.getOrDefault("TERM_PROGRAM", "");
    if (program.equals("Apple_Terminal")) return false;
    for (String marker : new String[] {"KITTY_WINDOW_ID", "ALACRITTY_LOG",
        "ALACRITTY_WINDOW_ID", "GHOSTTY_RESOURCES_DIR", "WEZTERM_EXECUTABLE", "WT_SESSION",
        "KONSOLE_VERSION"}) {
      if (!environment.getOrDefault(marker, "").isEmpty()) return true;
    }
    if (leadingInteger(environment.get("VTE_VERSION")) >= 6200) return true;
    if (program.equals("WezTerm") || program.equals("ghostty")
        || program.equals("vscode") || program.equals("Hyper")) return true;
    if (program.equals("iTerm.app")) return iTermSupportsSync(
        environment.getOrDefault("TERM_PROGRAM_VERSION", ""));
    String term = environment.getOrDefault("TERM", "");
    if (containsAny(term, "kitty", "ghostty", "wezterm", "alacritty", "foot", "rio"))
      return true;
    if (term.contains("tmux") || term.equals("screen") || term.contains("screen-")) return false;
    return false;
  }

  public static Duration streamingTickPeriod(Map<String, String> environment) {
    Duration base = synchronizedOutput(environment)
        ? Duration.ofMillis(33) : Duration.ofMillis(100);
    return runningOverSsh(environment) && base.compareTo(Duration.ofMillis(80)) < 0
        ? Duration.ofMillis(80) : base;
  }

  static boolean runningOverSsh(Map<String, String> environment) {
    String disabled = environment.getOrDefault("AJENT_NO_SSH_THROTTLE", "");
    if (!disabled.isEmpty() && disabled.charAt(0) != '0') return false;
    return environment.containsKey("SSH_CONNECTION") || environment.containsKey("SSH_TTY")
        || environment.containsKey("SSH_CLIENT");
  }

  private static boolean override(String value) {
    return value != null && !value.isEmpty() && !value.equals("0")
        && !value.equals("false") && !value.equals("no");
  }

  private static boolean containsAny(String value, String... candidates) {
    for (String candidate : candidates) if (value.contains(candidate)) return true;
    return false;
  }

  private static int leadingInteger(String value) {
    if (value == null) return 0;
    int end = 0;
    while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
    if (end == 0) return 0;
    try {
      return Integer.parseInt(value.substring(0, end));
    } catch (NumberFormatException exception) {
      return Integer.MAX_VALUE;
    }
  }

  private static boolean iTermSupportsSync(String version) {
    var match = java.util.regex.Pattern.compile("^(\\d+)(?:\\.(\\d+))?").matcher(version);
    if (!match.find()) return false;
    int major = Integer.parseInt(match.group(1));
    int minor = match.group(2) == null ? 0 : Integer.parseInt(match.group(2));
    return major > 3 || major == 3 && minor >= 5;
  }
}
