package com.github.skanga.ajent.tools.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/** Ajent-compatible process sandbox selection and command wrapping. */
public final class ProcessSandbox {
  private enum Backend { NONE, BWRAP, SANDBOX_EXEC }

  public record Initialization(boolean valid, boolean sandboxed, String description,
      ProcessRunner runner) {
    public Initialization {
      description = Objects.requireNonNull(description, "description");
      runner = Objects.requireNonNull(runner, "runner");
    }
  }

  private ProcessSandbox() {}

  public static Initialization initialize(String requested, Path workspace) {
    return initialize(requested, workspace, new ProcessRunner(),
        System.getProperty("os.name", ""));
  }

  static Initialization initialize(
      String requested, Path workspace, ProcessRunner base, String operatingSystem) {
    String mode = requested == null || requested.isBlank() ? "auto" : requested;
    if (!mode.equals("auto") && !mode.equals("on") && !mode.equals("off")) {
      throw new IllegalArgumentException(
          "--sandbox must be auto, on, or off (got '" + mode + "')");
    }
    Path root = Objects.requireNonNull(workspace, "workspace").toAbsolutePath().normalize();
    Objects.requireNonNull(base, "base");
    if (mode.equals("off")) {
      return new Initialization(true, false, "sandbox: off", base);
    }
    Backend backend = probe(root, base, operatingSystem);
    if (backend == Backend.NONE) {
      String unavailable = unavailableDescription(operatingSystem, mode.equals("on"));
      return new Initialization(!mode.equals("on"), false, unavailable, base);
    }
    String tag = backend == Backend.BWRAP ? "bwrap" : "sandbox-exec";
    String description = root.getParent() == null
        ? "sandbox: degraded (" + tag + ", --workspace / gives no filesystem containment)"
        : "sandbox: active (" + tag + ')';
    return new Initialization(true, true, description, new SandboxedRunner(base, root, backend));
  }

  private static Backend probe(Path workspace, ProcessRunner runner, String operatingSystem) {
    String os = operatingSystem.toLowerCase(Locale.ROOT);
    if (os.startsWith("linux")) {
      return canInvoke(runner, "bwrap", workspace) ? Backend.BWRAP : Backend.NONE;
    }
    if (os.startsWith("mac") || os.startsWith("darwin")) {
      return canInvoke(runner, "sandbox-exec", workspace) ? Backend.SANDBOX_EXEC : Backend.NONE;
    }
    return Backend.NONE;
  }

  private static boolean canInvoke(ProcessRunner runner, String executable, Path workspace) {
    ProcessRunner.Result result = runner.argv(
        List.of(executable, "--version"), workspace, 4096, Duration.ofSeconds(2));
    return result.started() && result.exitCode() == 0;
  }

  private static String unavailableDescription(String operatingSystem, boolean strict) {
    String os = operatingSystem.toLowerCase(Locale.ROOT);
    String suffix;
    if (os.startsWith("linux")) {
      suffix = strict ? "(install bubblewrap)" : "(install bubblewrap to enable)";
    } else if (os.startsWith("mac") || os.startsWith("darwin")) {
      suffix = strict ? "(sandbox-exec missing — system integrity issue)"
          : "(sandbox-exec missing)";
    } else {
      suffix = strict ? "(unsupported on this platform)" : "(no backend on this platform)";
    }
    return strict ? "sandbox: requested but no backend " + suffix
        : "sandbox: unavailable, running unsandboxed " + suffix;
  }

  private static final class SandboxedRunner extends ProcessRunner {
    private final ProcessRunner delegate;
    private final Path workspace;
    private final Backend backend;

    private SandboxedRunner(ProcessRunner delegate, Path workspace, Backend backend) {
      this.delegate = delegate;
      this.workspace = workspace;
      this.backend = backend;
    }

    @Override
    public Result shell(String command, Path directory, int maxBytes, Duration timeout) {
      List<String> wrapped = backend == Backend.BWRAP
          ? bwrapShell(command, cwd(directory)) : sandboxExecShell(command);
      return delegate.argv(wrapped, null, maxBytes, timeout);
    }

    @Override
    public Result shellWithProgress(String command, Path directory, int maxBytes, Duration timeout,
        Consumer<String> progress) {
      List<String> wrapped = backend == Backend.BWRAP
          ? bwrapShell(command, cwd(directory)) : sandboxExecShell(command);
      return delegate.argvWithProgress(wrapped, null, maxBytes, timeout, progress);
    }

    @Override
    public Result argv(List<String> argv, Path directory, int maxBytes, Duration timeout) {
      if (argv.isEmpty()) {
        return new Result(false, -1, "", false, false, "empty argv");
      }
      List<String> wrapped = backend == Backend.BWRAP
          ? bwrapArgv(argv, cwd(directory)) : sandboxExecArgv(argv);
      return delegate.argv(wrapped, null, maxBytes, timeout);
    }

    @Override
    public Result argvWithProgress(List<String> argv, Path directory, int maxBytes, Duration timeout,
        Consumer<String> progress) {
      if (argv.isEmpty()) {
        return new Result(false, -1, "", false, false, "empty argv");
      }
      List<String> wrapped = backend == Backend.BWRAP
          ? bwrapArgv(argv, cwd(directory)) : sandboxExecArgv(argv);
      return delegate.argvWithProgress(wrapped, null, maxBytes, timeout, progress);
    }

    private Path cwd(Path directory) {
      return directory == null ? workspace : directory.toAbsolutePath().normalize();
    }

    private List<String> bwrapShell(String command, Path directory) {
      var wrapped = bwrapPrefix(directory);
      wrapped.addAll(List.of("--", "/bin/sh", "-c", command));
      return wrapped;
    }

    private List<String> bwrapArgv(List<String> argv, Path directory) {
      var wrapped = bwrapPrefix(directory);
      wrapped.add("--");
      wrapped.addAll(argv);
      return wrapped;
    }

    private ArrayList<String> bwrapPrefix(Path directory) {
      var result = new ArrayList<>(List.of(
          "bwrap", "--ro-bind", "/usr", "/usr", "--ro-bind", "/bin", "/bin"));
      for (String path : List.of(
          "/etc/resolv.conf", "/etc/hosts", "/etc/nsswitch.conf", "/etc/host.conf",
          "/etc/passwd", "/etc/group", "/etc/localtime", "/etc/ssl", "/etc/pki",
          "/etc/ca-certificates", "/etc/ca-certificates.conf", "/etc/gitconfig",
          "/etc/profile", "/etc/alternatives", "/lib", "/lib64", "/sbin", "/opt")) {
        result.addAll(List.of("--ro-bind-try", path, path));
      }
      String root = workspace.toString();
      result.addAll(List.of(
          "--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp",
          "--bind", root, root, "--share-net", "--unshare-pid", "--new-session",
          "--die-with-parent", "--chdir", directory.toString()));
      return result;
    }

    private List<String> sandboxExecShell(String command) {
      return List.of("sandbox-exec", "-p", macProfile(), "/bin/sh", "-c", command);
    }

    private List<String> sandboxExecArgv(List<String> argv) {
      var result = new ArrayList<>(List.of("sandbox-exec", "-p", macProfile()));
      result.addAll(argv);
      return result;
    }

    private String macProfile() {
      String root = workspace.toString().replace("\\", "\\\\").replace("\"", "\\\"");
      return """
          (version 1)
          (deny default)
          (allow process-exec)
          (allow process-fork)
          (allow signal (target same-sandbox))
          (allow file-read*)
          (allow file-write* (subpath "%s"))
          (allow file-write* (subpath "/tmp"))
          (allow file-write* (subpath "/private/tmp"))
          (allow file-write* (subpath "/private/var/folders"))
          (allow file-write* (subpath "/dev/null"))
          (allow file-write* (subpath "/dev/tty"))
          (allow network*)
          (allow system-socket)
          (allow mach-lookup)
          (allow iokit-open)
          (allow sysctl-read)
          """.formatted(root);
    }
  }
}
