package com.github.skanga.ajent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.tools.process.ProcessRunner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class InteractiveCodeBlockExecutionTest {
  @TempDir Path workspace;

  @Test void posixUsesInheritedInputLiveTeeAndScopedParentSignalGuard() throws Exception {
    var runner = new RecordingRunner();
    var live = new ByteArrayOutputStream();
    var signalGuardClosed = new AtomicBoolean();
    var heartbeat = new java.util.ArrayList<Long>();

    ProcessRunner.Result result = InteractiveCommand.executeCodeBlock(false, runner,
        "printf hello", workspace, live::write, () -> () -> signalGuardClosed.set(true),
        heartbeat::add);

    assertThat(result.output()).isEqualTo("posix capture");
    assertThat(runner.posixCalls).isEqualTo(1);
    assertThat(runner.capturedCalls).isZero();
    assertThat(runner.command).isEqualTo("printf hello");
    assertThat(runner.directory).isEqualTo(workspace);
    assertThat(live.toString()).isEqualTo("live output");
    assertThat(signalGuardClosed).isTrue();
    assertThat(heartbeat).containsExactly(2L);
  }

  @Test void windowsPreservesBoundedCapturedRunnerFallback() {
    var runner = new RecordingRunner();
    var heartbeat = new java.util.ArrayList<Long>();

    ProcessRunner.Result result = InteractiveCommand.executeCodeBlock(true, runner,
        "powershell encoded", workspace, (bytes, offset, length) -> {}, () -> () -> {},
        heartbeat::add);

    assertThat(result.output()).isEqualTo("windows capture");
    assertThat(runner.capturedCalls).isEqualTo(1);
    assertThat(runner.posixCalls).isZero();
    assertThat(runner.command).isEqualTo("powershell encoded");
    assertThat(runner.directory).isEqualTo(workspace);
    assertThat(runner.maxBytes).isEqualTo(30_000);
    assertThat(runner.timeout).isEqualTo(Duration.ofSeconds(120));
    assertThat(heartbeat).containsExactly(4L);
  }

  @Test void terminalDecorationDistinguishesPlatformAndCompletionKind() {
    assertThat(InteractiveCommand.codeBlockHeader(false, "echo yes"))
        .isEqualTo("\u001b[2m\n╭─ running ─ Ctrl-C to stop ─────────────────────────────────"
            + "\u001b[0m\n\u001b[36m$ \u001b[0m\u001b[1mecho yes\u001b[0m\n");
    assertThat(InteractiveCommand.codeBlockHeader(true, "dir"))
        .contains("(output shown when it finishes)").contains("\u001b[1mdir\u001b[0m");
    assertThat(InteractiveCommand.codeBlockFooter(false,
        new ProcessRunner.Result(true, 130, "", false, false, ""), 3))
        .isEqualTo("\n\u001b[33m╰─ ■ stopped\u001b[0m\u001b[2m  exit 130  ·  3s\u001b[0m\n");
    assertThat(InteractiveCommand.codeBlockFooter(true,
        new ProcessRunner.Result(true, 1, "", true, false, ""), 9))
        .contains("\u001b]2;\u0007").contains("╰─ ■ timed out").contains("exit 1  ·  9s");
    assertThat(InteractiveCommand.codeBlockFooter(false,
        new ProcessRunner.Result(true, 131, "", false, false, ""), 1))
        .contains("╰─ ■ stopped").contains("exit 131");
    assertThat(InteractiveCommand.codeBlockFooter(false,
        new ProcessRunner.Result(true, 0, "", false, false, ""), 2))
        .contains("╰─ ✓ done").contains("exit 0");
    assertThat(InteractiveCommand.codeBlockFooter(false,
        new ProcessRunner.Result(true, 7, "", false, false, ""), 4))
        .contains("╰─ ✕ failed").contains("exit 7");
    assertThat(InteractiveCommand.codeBlockFooter(false,
        new ProcessRunner.Result(false, -1, "", false, false, "no shell"), 0))
        .contains("╰─ ✕ failed").contains("exit -1");
    assertThat(InteractiveCommand.codeBlockLabel("./very-long-command-name-here --flag"))
        .isEqualTo("./very-long-command-name");
    assertThat(InteractiveCommand.codeBlockLabel("mvn test")).isEqualTo("mvn");
    assertThat(InteractiveCommand.codeBlockLabel("")).isEmpty();
    assertThat(InteractiveCommand.codeBlockHeartbeat(false, 30, "mvn", 12, 1))
        .contains("\u001b]2;● 12s — mvn — ajent\u0007")
        .contains("\u001b[30;1H").contains("⣯ running… 12s · mvn · Ctrl-C to stop");
    assertThat(InteractiveCommand.codeBlockHeartbeat(true, 30, "mvn", 4, 2))
        .contains("\r\u001b[2K").contains("⣟ running… 4s");
    assertThat(InteractiveCommand.codeBlockHeartbeat(false, 2, "mvn", 0, -1))
        .isEqualTo("\u001b]2;● 0s — mvn — ajent\u0007");
    assertThat(InteractiveCommand.codeBlockStatusBegin(30))
        .isEqualTo("\u001b[1;29r\u001b[29;1H");
    assertThat(InteractiveCommand.codeBlockStatusBegin(2)).isEmpty();
    assertThat(InteractiveCommand.codeBlockStatusEnd(30))
        .isEqualTo("\u001b[r\u001b[30;1H\u001b[2K\u001b[29;1H\u001b]2;\u0007");
    assertThat(InteractiveCommand.codeBlockStatusEnd(2)).isEqualTo("\u001b]2;\u0007");
  }

  private static final class RecordingRunner extends ProcessRunner {
    private int posixCalls;
    private int capturedCalls;
    private String command;
    private Path directory;
    private int maxBytes;
    private Duration timeout;

    @Override @SuppressWarnings("try") // ignored SignalGuard is exercised only for its close().
    public Result interactivePosixShell(String command, Path directory,
        LiveOutput liveOutput, Supplier<SignalGuard> signalGuard, Heartbeat heartbeat) {
      posixCalls++;
      this.command = command;
      this.directory = directory;
      try {
        byte[] bytes = "live output".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        liveOutput.write(bytes, 0, bytes.length);
      } catch (IOException exception) {
        throw new AssertionError(exception);
      }
      SignalGuard guard = signalGuard.get();
      try (guard) {
        heartbeat.pulse(2);
        return new Result(true, 0, "posix capture", false, false, "");
      }
    }

    @Override public Result shell(
        String command, Path directory, int maxBytes, Duration timeout) {
      return shell(command, directory, maxBytes, timeout, ignored -> {});
    }

    @Override public Result shell(String command, Path directory, int maxBytes, Duration timeout,
        Heartbeat heartbeat) {
      capturedCalls++;
      this.command = command;
      this.directory = directory;
      this.maxBytes = maxBytes;
      this.timeout = timeout;
      heartbeat.pulse(4);
      return new Result(true, 0, "windows capture", false, false, "");
    }
  }
}
