package com.github.skanga.ajent.provider;

import com.github.skanga.ajent.provider.wire.SseFramer;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Opt-in, process-lifetime append log matching AgenTTY's raw Anthropic API diagnostics. */
final class ApiDebugLog {
  private static final Map<Path, ApiDebugLog> LOGS = new ConcurrentHashMap<>();

  private final BufferedWriter writer;
  private final long startedNanos = System.nanoTime();

  private ApiDebugLog(BufferedWriter writer) {
    this.writer = writer;
  }

  static ApiDebugLog open(Map<String, String> environment) {
    String enabled = environment.get("AGENTTY_DEBUG_API");
    if (enabled == null || enabled.isEmpty() || enabled.charAt(0) == '0') return null;
    String configured = environment.get("AGENTTY_DEBUG_FILE");
    Path path = Path.of(configured == null || configured.isEmpty()
        ? "agentty-api.log" : configured).toAbsolutePath().normalize();
    return LOGS.computeIfAbsent(path, ApiDebugLog::open);
  }

  private static ApiDebugLog open(Path path) {
    try {
      return new ApiDebugLog(Files.newBufferedWriter(path, StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND));
    } catch (IOException exception) {
      return null;
    }
  }

  synchronized void write(String format, Object... arguments) {
    try {
      long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
      writer.write(String.format(Locale.ROOT, "[+%6dms] ", elapsedMillis));
      writer.write(String.format(Locale.ROOT, format, arguments));
      writer.flush();
    } catch (IOException ignored) {
      // Diagnostics are observational and must never fail a provider request.
    }
  }

  void event(SseFramer.Event event) {
    write("<< event=%s data=%s%n", event.name(), event.data());
  }

  static void closeAllForTests() {
    LOGS.values().forEach(ApiDebugLog::close);
    LOGS.clear();
  }

  private synchronized void close() {
    try {
      writer.close();
    } catch (IOException ignored) { }
  }
}
