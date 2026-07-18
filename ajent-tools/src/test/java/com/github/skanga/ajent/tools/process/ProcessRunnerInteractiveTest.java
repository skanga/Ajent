package com.github.skanga.ajent.tools.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProcessRunnerInteractiveTest {
  @TempDir Path directory;

  @Test void posixInteractiveRunInheritsInputTeesMergedOutputAndBoundsCapture() {
    byte[] output = "abcdefgh".getBytes(StandardCharsets.UTF_8);
    var process = new FakeProcess(output, 7);
    var events = new ArrayList<String>();
    var live = new ByteArrayOutputStream();
    var runner = new ProcessRunner(builder -> {
      assertThat(builder.command()).containsExactly("/bin/sh", "-c", "printf test", "sh");
      assertThat(builder.directory().toPath()).isEqualTo(directory);
      assertThat(builder.redirectInput()).isEqualTo(ProcessBuilder.Redirect.INHERIT);
      assertThat(builder.redirectErrorStream()).isTrue();
      events.add("spawn");
      return process;
    });

    ProcessRunner.Result result = runner.interactivePosixShell(
        "printf test", directory, 4, (bytes, offset, length) -> {
          events.add("output");
          live.write(bytes, offset, length);
        }, () -> {
          events.add("guard-open");
          return () -> events.add("guard-close");
        });

    assertThat(live.toByteArray()).containsExactly(output);
    assertThat(result.started()).isTrue();
    assertThat(result.exitCode()).isEqualTo(7);
    assertThat(result.output()).isEqualTo(
        "abcd\n[capture truncated at 4 B — full output was shown on screen]");
    assertThat(result.truncated()).isTrue();
    assertThat(result.timedOut()).isFalse();
    assertThat(events).containsExactly("spawn", "guard-open", "output", "guard-close");
    assertThat(process.stdinRequested).isFalse();
  }

  @Test void startFailureNeverInstallsSignalGuard() {
    var guarded = new AtomicBoolean();
    var runner = new ProcessRunner(builder -> { throw new IOException("fork unavailable"); });

    ProcessRunner.Result result = runner.interactivePosixShell("true", directory, 20,
        (bytes, offset, length) -> {}, () -> {
          guarded.set(true);
          return () -> {};
        });

    assertThat(result).isEqualTo(new ProcessRunner.Result(
        false, -1, "", false, false, "fork unavailable"));
    assertThat(guarded).isFalse();
  }

  @Test void liveOutputFailureDoesNotLoseCaptureAndGuardStillCloses() {
    var closed = new AtomicBoolean();
    var runner = new ProcessRunner(builder -> new FakeProcess(
        "visible".getBytes(StandardCharsets.UTF_8), 0));

    ProcessRunner.Result result = runner.interactivePosixShell("echo visible", directory, 20,
        (bytes, offset, length) -> { throw new IOException("terminal closed"); },
        () -> () -> closed.set(true));

    assertThat(result.output()).isEqualTo("visible");
    assertThat(result.exitCode()).isZero();
    assertThat(closed).isTrue();
  }

  private static final class FakeProcess extends Process {
    private final InputStream output;
    private final int exitCode;
    private boolean stdinRequested;

    private FakeProcess(byte[] output, int exitCode) {
      this.output = new ByteArrayInputStream(output);
      this.exitCode = exitCode;
    }

    @Override public OutputStream getOutputStream() {
      stdinRequested = true;
      return OutputStream.nullOutputStream();
    }
    @Override public InputStream getInputStream() { return output; }
    @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
    @Override public int waitFor() { return exitCode; }
    @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
    @Override public int exitValue() { return exitCode; }
    @Override public void destroy() {}
    @Override public Process destroyForcibly() { return this; }
    @Override public boolean isAlive() { return false; }
  }
}
