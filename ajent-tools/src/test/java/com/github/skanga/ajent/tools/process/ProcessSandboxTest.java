package com.github.skanga.ajent.tools.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessSandboxTest {
  @Test
  void windowsMatchesNativeOffAutoAndStrictOnStates(@TempDir Path workspace) {
    var base = new CapturingRunner(false);

    assertThat(ProcessSandbox.initialize("off", workspace, base, "Windows 11"))
        .satisfies(result -> {
          assertThat(result.valid()).isTrue();
          assertThat(result.description()).isEqualTo("sandbox: off");
          assertThat(result.runner()).isSameAs(base);
        });
    assertThat(ProcessSandbox.initialize("auto", workspace, base, "Windows 11"))
        .satisfies(result -> {
          assertThat(result.valid()).isTrue();
          assertThat(result.description()).isEqualTo(
              "sandbox: unavailable, running unsandboxed (no backend on this platform)");
          assertThat(result.runner()).isSameAs(base);
        });
    assertThat(ProcessSandbox.initialize("on", workspace, base, "Windows 11"))
        .satisfies(result -> {
          assertThat(result.valid()).isFalse();
          assertThat(result.description()).isEqualTo(
              "sandbox: requested but no backend (unsupported on this platform)");
        });
  }

  @Test
  void linuxBubblewrapWrapsShellAndArgvWithWorkspaceLastAndRequestedCwd(
      @TempDir Path workspace) {
    var base = new CapturingRunner(true);
    var initialized = ProcessSandbox.initialize("auto", workspace, base, "Linux");
    Path directory = workspace.resolve("sub");

    initialized.runner().shell("echo ok", directory, 1234, Duration.ofSeconds(9));
    assertThat(initialized.description()).isEqualTo("sandbox: active (bwrap)");
    assertThat(base.lastArgv).startsWith("bwrap", "--ro-bind", "/usr", "/usr");
    assertThat(base.lastArgv).containsSubsequence(
        "--bind", workspace.toAbsolutePath().normalize().toString(),
        workspace.toAbsolutePath().normalize().toString(), "--share-net", "--unshare-pid",
        "--new-session", "--die-with-parent", "--chdir",
        directory.toAbsolutePath().normalize().toString(), "--", "/bin/sh", "-c", "echo ok");
    assertThat(base.lastDirectory).isNull();
    assertThat(base.lastMaxBytes).isEqualTo(1234);

    initialized.runner().argv(List.of("git", "status"), directory, 55, Duration.ofSeconds(2));
    assertThat(base.lastArgv).endsWith("--", "git", "status");

    var progress = new ArrayList<String>();
    initialized.runner().shellWithProgress(
        "echo streaming", directory, 77, Duration.ofSeconds(2), progress::add);
    assertThat(base.lastArgv).endsWith("--", "/bin/sh", "-c", "echo streaming");
    assertThat(base.lastMaxBytes).isEqualTo(77);
    assertThat(progress).containsExactly("sandboxed progress");
  }

  @Test
  void macSandboxExecUsesAWorkspaceWriteProfile(@TempDir Path workspace) {
    var base = new CapturingRunner(true);
    var initialized = ProcessSandbox.initialize("on", workspace, base, "Mac OS X");

    initialized.runner().shell("pwd", workspace, 100, Duration.ofSeconds(1));

    assertThat(initialized.valid()).isTrue();
    assertThat(initialized.description()).isEqualTo("sandbox: active (sandbox-exec)");
    assertThat(base.lastArgv).startsWith("sandbox-exec", "-p");
    String escapedRoot = workspace.toAbsolutePath().normalize().toString()
        .replace("\\", "\\\\");
    assertThat(base.lastArgv.get(2)).contains("(deny default)",
        "(allow file-write* (subpath \"" + escapedRoot + "\"))",
        "(allow network*)");
    assertThat(base.lastArgv).endsWith("/bin/sh", "-c", "pwd");
    initialized.runner().argv(List.of("xcrun", "--version"), null, 44, Duration.ofSeconds(3));
    assertThat(base.lastArgv).endsWith("xcrun", "--version");
  }

  @Test
  void validatesModesAndDescribesMissingPosixBackends(@TempDir Path workspace) {
    var missing = new CapturingRunner(false);

    assertThatThrownBy(() -> ProcessSandbox.initialize("sideways", workspace, missing, "Linux"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("--sandbox must be auto, on, or off (got 'sideways')");
    assertThat(ProcessSandbox.initialize("", workspace, missing, "Linux").description())
        .isEqualTo("sandbox: unavailable, running unsandboxed "
            + "(install bubblewrap to enable)");
    assertThat(ProcessSandbox.initialize("on", workspace, missing, "Linux"))
        .satisfies(result -> {
          assertThat(result.valid()).isFalse();
          assertThat(result.description())
              .isEqualTo("sandbox: requested but no backend (install bubblewrap)");
        });
    assertThat(ProcessSandbox.initialize("auto", workspace, missing, "Darwin").description())
        .isEqualTo("sandbox: unavailable, running unsandboxed (sandbox-exec missing)");
    assertThat(ProcessSandbox.initialize("on", workspace, missing, "Mac OS X").description())
        .isEqualTo("sandbox: requested but no backend "
            + "(sandbox-exec missing — system integrity issue)");
  }

  @Test
  void handlesRootWorkspaceNullCwdAndEmptyWrappedArgv() {
    Path root = Path.of("/").toAbsolutePath().normalize();
    var base = new CapturingRunner(true);
    var initialized = ProcessSandbox.initialize("auto", root, base, "Linux");

    assertThat(initialized.description()).contains("sandbox: degraded (bwrap");
    initialized.runner().shell("pwd", null, 1, Duration.ofSeconds(1));
    assertThat(base.lastArgv).containsSubsequence("--chdir", root.toString());
    assertThat(initialized.runner().argv(List.of(), root, 1, Duration.ofSeconds(1)))
        .satisfies(result -> {
          assertThat(result.started()).isFalse();
          assertThat(result.startError()).isEqualTo("empty argv");
        });
  }

  private static final class CapturingRunner extends ProcessRunner {
    private final boolean probeSuccess;
    private List<String> lastArgv = List.of();
    private Path lastDirectory;
    private int lastMaxBytes;

    private CapturingRunner(boolean probeSuccess) {
      this.probeSuccess = probeSuccess;
    }

    @Override
    public Result argv(List<String> argv, Path directory, int maxBytes, Duration timeout) {
      lastArgv = List.copyOf(argv);
      lastDirectory = directory;
      lastMaxBytes = maxBytes;
      boolean probe = argv.size() == 2 && argv.get(1).equals("--version");
      return new Result(probe ? probeSuccess : true, probe && !probeSuccess ? 1 : 0,
          "", false, false, probe && !probeSuccess ? "missing" : "");
    }

    @Override
    public Result argvWithProgress(List<String> argv, Path directory, int maxBytes, Duration timeout,
        Consumer<String> progress) {
      Result result = argv(argv, directory, maxBytes, timeout);
      progress.accept("sandboxed progress");
      return result;
    }
  }
}
