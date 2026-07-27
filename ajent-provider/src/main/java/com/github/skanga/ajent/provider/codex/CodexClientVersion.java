package com.github.skanga.ajent.provider.codex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Resolves the installed Codex CLI version for model-catalog compatibility. */
public final class CodexClientVersion {
  private static final String FALLBACK = "0.121.0";
  private static final Pattern VERSION = Pattern.compile("\\b\\d+\\.\\d+\\.\\d+\\b");
  private static final int MAX_OUTPUT_BYTES = 16 * 1024;

  private CodexClientVersion() {}

  public static String detect() {
    String configured = System.getenv().getOrDefault("AJENT_CODEX_CLIENT_VERSION", "").strip();
    String normalized = normalize(configured);
    if (normalized != null) return normalized;
    boolean windows = System.getProperty("os.name", "")
        .toLowerCase(Locale.ROOT).contains("win");
    List<List<String>> commands = windows
        ? List.of(List.of("cmd.exe", "/c", "codex.cmd", "--version"),
            List.of("codex.exe", "--version"), List.of("codex", "--version"))
        : List.of(List.of("codex", "--version"));
    for (List<String> command : commands) {
      normalized = normalize(run(command));
      if (normalized != null) return normalized;
    }
    return FALLBACK;
  }

  static String normalize(String value) {
    if (value == null) return null;
    var matcher = VERSION.matcher(value);
    return matcher.find() ? matcher.group() : null;
  }

  private static String run(List<String> command) {
    Process process = null;
    try {
      process = new ProcessBuilder(command).redirectErrorStream(true).start();
      if (!process.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return null;
      }
      byte[] output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES);
      return new String(output, StandardCharsets.UTF_8);
    } catch (IOException | InterruptedException | SecurityException exception) {
      if (exception instanceof InterruptedException) java.lang.Thread.currentThread().interrupt();
      return null;
    } finally {
      if (process != null) process.destroy();
    }
  }
}
