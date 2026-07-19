package com.github.skanga.ajent.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Best-effort catch-site diagnostics compatible with {@code AGENTTY_DEBUG_LOG}. */
public final class AgenttyDebugLog {
  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
  private static final Object LOCK = new Object();

  private AgenttyDebugLog() {}

  public static boolean enabled() {
    return !Holder.path.isEmpty();
  }

  public static void log(String where, String message) {
    write(Holder.path, LocalDateTime.now(), where, message);
  }

  public static void log(String where, Throwable failure) {
    if (!enabled()) return;
    String message = failure == null ? "unknown failure" : failure.getMessage();
    log(where, message == null || message.isEmpty()
        ? failure.getClass().getSimpleName() : message);
  }

  static void write(String path, LocalDateTime timestamp, String where, String message) {
    if (path == null || path.isEmpty()) return;
    try {
      String line = TIMESTAMP.format(timestamp) + " [" + where + "] " + message + '\n';
      synchronized (LOCK) {
        Files.writeString(Path.of(path), line, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      }
    } catch (RuntimeException | java.io.IOException ignored) {
      // Diagnostics must never replace the error already being recovered from.
    }
  }

  private static final class Holder {
    private static final String path = System.getenv().getOrDefault("AGENTTY_DEBUG_LOG", "");
  }
}
