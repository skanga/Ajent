package com.github.skanga.ajent.tools.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.skanga.ajent.tools.args.ArgReader;
import com.github.skanga.ajent.tools.fs.WorkspaceSandbox;
import com.github.skanga.ajent.tools.runtime.ToolError;
import com.github.skanga.ajent.tools.runtime.ToolErrorKind;
import com.github.skanga.ajent.tools.runtime.ToolOutput;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** AgenTTY-compatible bash and diagnostics tools. */
public final class ProcessTools {
  private static final int CAPTURE_CAP = 8 * 1024 * 1024;
  private final WorkspaceSandbox sandbox;
  private final ProcessRunner runner;

  public ProcessTools(WorkspaceSandbox sandbox) { this(sandbox, new ProcessRunner()); }
  ProcessTools(WorkspaceSandbox sandbox, ProcessRunner runner) {
    this.sandbox = sandbox;
    this.runner = runner;
  }

  public ToolResult execute(String name, JsonNode arguments) {
    return switch (name) {
      case "bash" -> bash(arguments);
      case "diagnostics" -> diagnostics(arguments);
      default -> failure(ToolErrorKind.UNKNOWN, "unknown tool: " + name);
    };
  }

  private ToolResult bash(JsonNode arguments) {
    var args = new ArgReader(arguments);
    String command = args.requiredString("command").orElse(null);
    if (command == null) return failure(ToolErrorKind.INVALID_ARGS, "command required");
    String reason = BashValidator.validate(command);
    if (!reason.isEmpty()) return failure(ToolErrorKind.INVALID_ARGS, reason);
    if (command.isEmpty()) return failure(ToolErrorKind.INVALID_ARGS, "command must not be empty");
    int timeout = args.integer("timeout", 60);
    if (args.has("timeout_ms")) {
      int millis = args.integer("timeout_ms", 0);
      if (millis > 0) timeout = (millis + 999) / 1000;
    }
    if (timeout <= 0 || timeout > 300) timeout = 60;
    Path directory = sandbox.workspaceRoot();
    String cd = args.string("cd", "");
    if (!cd.isEmpty()) {
      directory = sandbox.normalize(cd);
      if (!Files.isDirectory(directory)) return failure(ToolErrorKind.INVALID_ARGS,
          "cd '" + cd + "' is not a directory");
      if (!sandbox.isWithin(directory)) return failure(ToolErrorKind.OUT_OF_WORKSPACE,
          "bash: path is outside workspace: " + directory);
    }
    long started = System.nanoTime();
    ProcessRunner.Result result = runner.shell(command, directory, CAPTURE_CAP,
        Duration.ofSeconds(timeout));
    if (!result.started()) return failure(ToolErrorKind.SPAWN,
        "failed to spawn command: " + result.startError());
    String output = stripAnsi(result.output());
    String body;
    if (result.timedOut()) body = output.isEmpty() ? "Command \"" + command + "\" timed out after "
        + timeout + "s. No output was captured." : "Command \"" + command + "\" timed out after "
        + timeout + "s. Output captured before timeout:\n\n" + fence(output);
    else if (result.exitCode() != 0) body = output.isEmpty() ? "Command \"" + command
        + "\" failed with exit code " + result.exitCode() + "." : "Command \"" + command
        + "\" failed with exit code " + result.exitCode() + ".\n\n" + fence(output);
    else body = output.isEmpty() ? "Command executed successfully." : fence(output);
    if (result.truncated()) body += "\n\n[output truncated at " + CAPTURE_CAP + " bytes]";
    long elapsed = (System.nanoTime() - started) / 1_000_000;
    if (elapsed >= 500) body += elapsed < 10_000 ? "\n\n[elapsed: " + elapsed + " ms]"
        : "\n\n[elapsed: " + (elapsed / 1000) + "." + ((elapsed % 1000) / 100) + " s]";
    String description = args.string("display_description", "");
    return success(description.isEmpty() ? body : description + '\n' + body);
  }

  private ToolResult diagnostics(JsonNode arguments) {
    var args = new ArgReader(arguments);
    String command = args.string("command", "");
    ProcessRunner.Result result;
    if (!command.isEmpty()) result = runner.shell(command, sandbox.workspaceRoot(), 100_000,
        Duration.ofSeconds(120));
    else {
      List<String> detected = diagnosticCommand();
      if (detected.isEmpty()) return failure(ToolErrorKind.NOT_FOUND,
          "no build system detected; pass a command");
      result = runner.argv(detected, sandbox.workspaceRoot(), 100_000, Duration.ofSeconds(120));
    }
    String output = legacy(result, 120);
    if (output.isEmpty()) return success("no diagnostics (clean build)");
    int errors = 0;
    int warnings = 0;
    var firstErrors = new StringBuilder();
    for (String line : output.lines().toList()) {
      boolean error = line.contains("error:") || line.contains("Error:") || line.contains("ERROR:")
          || line.contains(" error ") || line.contains("error[");
      boolean warning = line.contains("warning:") || line.contains("Warning:")
          || line.contains("WARNING:") || line.contains("warn[");
      if (error) { if (errors++ < 10) firstErrors.append("  ").append(line).append('\n'); }
      if (warning) warnings++;
    }
    String body = output;
    if (errors > 0 || warnings > 0) body = "X " + errors + " error(s), " + warnings
        + " warning(s)\n\n" + (firstErrors.isEmpty() ? "" : "First errors:\n" + firstErrors + "\n")
        + "Full output:\n" + output;
    String description = args.string("display_description", "");
    return success(description.isEmpty() ? body : description + '\n' + body);
  }

  private List<String> diagnosticCommand() {
    Path root = sandbox.workspaceRoot();
    if (Files.exists(root.resolve("build/build.ninja")) || Files.exists(root.resolve("build/Makefile")))
      return List.of("cmake", "--build", "build");
    if (Files.exists(root.resolve("Cargo.toml"))) return List.of("cargo", "check");
    if (Files.exists(root.resolve("go.mod"))) return List.of("go", "build", "./...");
    if (Files.exists(root.resolve("package.json"))) return List.of("npx", "tsc", "--noEmit");
    if (Files.exists(root.resolve("Makefile"))) return List.of("make", "-n");
    return List.of();
  }

  static String stripAnsi(String input) {
    return input.replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]", "")
        .replaceAll("\\u001B\\].*?(?:\\u0007|\\u001B\\\\)", "")
        .replaceAll("\\u001B.", "");
  }

  private static String legacy(ProcessRunner.Result result, int timeout) {
    if (!result.started()) return "[" + result.startError() + "]";
    String output = result.output();
    if (result.truncated()) output += "\n[output truncated]";
    if (result.timedOut()) output += "\n[timed out after " + timeout + "s]";
    else if (result.exitCode() != 0) output += "\n[exit code " + result.exitCode() + "]";
    return output;
  }

  private static String fence(String output) {
    return "```\n" + output + (output.isEmpty() || output.endsWith("\n") ? "" : "\n") + "```";
  }
  private static ToolResult success(String text) { return new ToolResult.Success(new ToolOutput(text)); }
  private static ToolResult failure(ToolErrorKind kind, String detail) {
    return new ToolResult.Failure(new ToolError(kind, detail));
  }
}
