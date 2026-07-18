package com.github.skanga.ajent.tools.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Bounded subprocess execution using the JDK process API. */
public class ProcessRunner {
  public static final int INTERACTIVE_CAPTURE_BYTES = 2 * 1024 * 1024;

  public record Result(boolean started, int exitCode, String output, boolean timedOut,
                       boolean truncated, String startError) {}

  @FunctionalInterface
  public interface LiveOutput {
    void write(byte[] bytes, int offset, int length) throws IOException;
  }

  @FunctionalInterface
  public interface SignalGuard extends AutoCloseable {
    @Override void close();
  }

  @FunctionalInterface
  public interface Heartbeat {
    void pulse(long elapsedSeconds);
  }

  @FunctionalInterface
  interface ProcessStarter {
    Process start(ProcessBuilder builder) throws IOException;
  }

  private final ProcessStarter starter;

  public ProcessRunner() { this(ProcessBuilder::start); }

  ProcessRunner(ProcessStarter starter) {
    this.starter = Objects.requireNonNull(starter, "starter");
  }

  public Result shell(String command, Path directory, int maxBytes, Duration timeout) {
    List<String> argv = isWindows() ? List.of("cmd.exe", "/S", "/C", command)
        : List.of("sh", "-c", command);
    return run(argv, directory, maxBytes, timeout);
  }

  /** Runs the captured shell while reporting elapsed time at the native 250 ms cadence. */
  public Result shell(String command, Path directory, int maxBytes, Duration timeout,
      Heartbeat heartbeat) {
    Objects.requireNonNull(heartbeat, "heartbeat");
    var finished = new AtomicBoolean();
    boolean pulses = pulse(heartbeat, 0);
    Thread ticker = !pulses ? null : Thread.startVirtualThread(() -> {
      long started = System.nanoTime();
      while (!finished.get()) {
        try {
          Thread.sleep(250);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          return;
        }
        if (!finished.get() && !pulse(
            heartbeat, Duration.ofNanos(System.nanoTime() - started).toSeconds())) return;
      }
    });
    try {
      return shell(command, directory, maxBytes, timeout);
    } finally {
      finished.set(true);
      if (ticker != null) {
        ticker.interrupt();
        try {
          ticker.join();
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  public Result argv(List<String> argv, Path directory, int maxBytes, Duration timeout) {
    return run(argv, directory, maxBytes, timeout, Map.of());
  }

  public Result argv(List<String> argv, Path directory, int maxBytes, Duration timeout,
      Map<String, String> environment) {
    return run(argv, directory, maxBytes, timeout, environment);
  }

  /** Runs a POSIX shell with inherited stdin while teeing merged output live and to a bounded copy. */
  public Result interactivePosixShell(String command, Path directory, LiveOutput liveOutput,
      Supplier<SignalGuard> signalGuard) {
    return interactivePosixShell(
        command, directory, INTERACTIVE_CAPTURE_BYTES, liveOutput, signalGuard, ignored -> {});
  }

  /** Adds a one-second idle/output-independent elapsed callback to the interactive POSIX run. */
  public Result interactivePosixShell(String command, Path directory, LiveOutput liveOutput,
      Supplier<SignalGuard> signalGuard, Heartbeat heartbeat) {
    return interactivePosixShell(
        command, directory, INTERACTIVE_CAPTURE_BYTES, liveOutput, signalGuard, heartbeat);
  }

  Result interactivePosixShell(String command, Path directory, int maxBytes,
      LiveOutput liveOutput, Supplier<SignalGuard> signalGuard) {
    return interactivePosixShell(
        command, directory, maxBytes, liveOutput, signalGuard, ignored -> {});
  }

  Result interactivePosixShell(String command, Path directory, int maxBytes,
      LiveOutput liveOutput, Supplier<SignalGuard> signalGuard, Heartbeat heartbeat) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(liveOutput, "liveOutput");
    Objects.requireNonNull(signalGuard, "signalGuard");
    Objects.requireNonNull(heartbeat, "heartbeat");
    if (maxBytes < 0) throw new IllegalArgumentException("negative capture bound");
    Process process;
    try {
      var builder = new ProcessBuilder("/bin/sh", "-c", command, "sh")
          .redirectInput(ProcessBuilder.Redirect.INHERIT).redirectErrorStream(true);
      if (directory != null) builder.directory(directory.toFile());
      process = starter.start(builder);
    } catch (IOException exception) {
      return new Result(false, -1, "", false, false, message(exception));
    }

    SignalGuard guard;
    try {
      guard = Objects.requireNonNull(signalGuard.get(), "signal guard result");
    } catch (RuntimeException exception) {
      process.destroyForcibly();
      return new Result(true, 1, "", false, false, message(exception));
    }
    var captured = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
    boolean truncated = false;
    boolean live = true;
    boolean pulses = pulse(heartbeat, 0);
    long started = System.nanoTime();
    byte[] end = new byte[0];
    BlockingQueue<byte[]> chunks = new ArrayBlockingQueue<>(8);
    Thread reader = Thread.startVirtualThread(() -> {
      boolean interrupted = false;
      try (InputStream input = process.getInputStream()) {
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) >= 0;) {
          if (count > 0) chunks.put(Arrays.copyOf(buffer, count));
        }
      } catch (IOException ignored) {
        // AgenTTY treats a pipe read failure as end-of-output and still reaps the child.
      } catch (InterruptedException exception) {
        interrupted = true;
        Thread.currentThread().interrupt();
      } finally {
        if (!interrupted) {
          try {
            chunks.put(end);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
        }
      }
    });
    try {
      long lastPulse = 0;
      for (;;) {
        byte[] buffer = chunks.poll(1, TimeUnit.SECONDS);
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toSeconds();
        if (pulses && elapsed != lastPulse) {
          pulses = pulse(heartbeat, elapsed);
          lastPulse = elapsed;
        }
        if (buffer == null) continue;
        if (buffer == end) break;
        int count = buffer.length;
        if (live) {
          try {
            liveOutput.write(buffer, 0, count);
          } catch (IOException | RuntimeException exception) {
            live = false;
          }
        }
        int room = maxBytes - captured.size();
        if (room > 0) captured.write(buffer, 0, Math.min(room, count));
        if (count > room) truncated = true;
      }
      int exitCode = process.waitFor();
      String output = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
      if (truncated) output += "\n[capture truncated at " + formatBytes(maxBytes)
          + " — full output was shown on screen]";
      return new Result(true, exitCode, output, false, truncated, "");
    } catch (InterruptedException exception) {
      reader.interrupt();
      return interrupted(process, captured, truncated);
    } finally {
      guard.close();
    }
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
      process = starter.start(builder);
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
  private static Result interrupted(
      Process process, ByteArrayOutputStream output, boolean truncated) {
    Thread.currentThread().interrupt();
    process.destroyForcibly();
    return new Result(true, 1,
        output.toString(java.nio.charset.StandardCharsets.UTF_8), true, truncated, "interrupted");
  }
  private static String formatBytes(int bytes) {
    if (bytes == 2 * 1024 * 1024) return "2 MB";
    if (bytes >= 1024 * 1024 && bytes % (1024 * 1024) == 0)
      return bytes / (1024 * 1024) + " MB";
    if (bytes >= 1024 && bytes % 1024 == 0) return bytes / 1024 + " KB";
    return bytes + " B";
  }
  private static boolean pulse(Heartbeat heartbeat, long elapsedSeconds) {
    try {
      heartbeat.pulse(elapsedSeconds);
      return true;
    } catch (RuntimeException exception) {
      return false;
    }
  }
  private static String message(Exception exception) {
    return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
  }
}
