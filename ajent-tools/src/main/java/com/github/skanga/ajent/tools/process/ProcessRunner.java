package com.github.skanga.ajent.tools.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Bounded, non-interactive subprocess execution using the JDK process API. */
public class ProcessRunner {
  public record Result(boolean started, int exitCode, String output, boolean timedOut,
                       boolean truncated, String startError) {}

  public Result shell(String command, Path directory, int maxBytes, Duration timeout) {
    List<String> argv = isWindows() ? List.of("cmd.exe", "/S", "/C", command)
        : List.of("sh", "-c", command);
    return run(argv, directory, maxBytes, timeout);
  }

  public Result argv(List<String> argv, Path directory, int maxBytes, Duration timeout) {
    return run(argv, directory, maxBytes, timeout, Map.of());
  }

  public Result argv(List<String> argv, Path directory, int maxBytes, Duration timeout,
      Map<String, String> environment) {
    return run(argv, directory, maxBytes, timeout, environment);
  }

  private Result run(List<String> argv, Path directory, int maxBytes, Duration timeout) {
    return run(argv, directory, maxBytes, timeout, Map.of());
  }

  private Result run(List<String> argv, Path directory, int maxBytes, Duration timeout,
      Map<String, String> environment) {
    Process process;
    try {
      var builder = new ProcessBuilder(argv).redirectErrorStream(true);
      if (directory != null) builder.directory(directory.toFile());
      builder.environment().putAll(environment);
      process = builder.start();
      process.getOutputStream().close();
    } catch (IOException exception) {
      return new Result(false, -1, "", false, false, message(exception));
    }
    var bytes = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
    boolean[] truncated = {false};
    Thread reader = Thread.ofVirtual().start(() -> {
      try (var input = process.getInputStream()) {
        byte[] buffer = new byte[4096];
        for (int count; (count = input.read(buffer)) >= 0;) {
          int room = maxBytes - bytes.size();
          if (room > 0) bytes.write(buffer, 0, Math.min(room, count));
          if (count > room) truncated[0] = true;
        }
      } catch (IOException ignored) {
        // Process termination closes the pipe.
      }
    });
    boolean timedOut = false;
    try {
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        timedOut = true;
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
      }
      reader.join(Duration.ofSeconds(3));
      return new Result(true, process.isAlive() ? 1 : process.exitValue(), bytes.toString(java.nio.charset.StandardCharsets.UTF_8),
          timedOut, truncated[0], "");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      return new Result(true, 1, bytes.toString(java.nio.charset.StandardCharsets.UTF_8), true,
          truncated[0], "interrupted");
    }
  }

  private static boolean isWindows() { return System.getProperty("os.name", "").startsWith("Windows"); }
  private static String message(Exception exception) {
    return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
  }
}
