package com.github.skanga.ajent.tools.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.skanga.ajent.tools.args.ArgReader;
import com.github.skanga.ajent.tools.fs.WorkspaceSandbox;
import com.github.skanga.ajent.tools.process.ProcessRunner;
import com.github.skanga.ajent.tools.runtime.ToolError;
import com.github.skanga.ajent.tools.runtime.ToolErrorKind;
import com.github.skanga.ajent.tools.runtime.ToolOutput;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Ajent-compatible status, diff, log, and commit tools. */
public final class GitTools {
  private static final Duration TIMEOUT = Duration.ofSeconds(60);
  private final WorkspaceSandbox sandbox;
  private final ProcessRunner runner;

  public GitTools(WorkspaceSandbox sandbox) { this(sandbox, new ProcessRunner()); }
  GitTools(WorkspaceSandbox sandbox, ProcessRunner runner) {
    this.sandbox = sandbox;
    this.runner = runner;
  }

  public ToolResult execute(String name, JsonNode arguments) {
    return switch (name) {
      case "git_status" -> status(arguments);
      case "git_diff" -> diff(arguments);
      case "git_log" -> log(arguments);
      case "git_commit" -> commit(arguments);
      default -> failure(ToolErrorKind.UNKNOWN, "unknown tool: " + name);
    };
  }

  private ToolResult status(JsonNode arguments) {
    var args = new ArgReader(arguments);
    Path checked = checked(args.string("path", "."), "git_status");
    if (checked == null) return outside(args.string("path", "."), "git_status");
    Path repository = resolveRepository(checked);
    ToolResult result = git(List.of("git", "-C", repository.toString(), "status",
        "--porcelain=v1", "--branch"), "git_status", 30_000);
    if (result instanceof ToolResult.Failure) return result;
    String output = ((ToolResult.Success) result).output().text();
    if (output.isEmpty()) output = "working tree clean";
    else if (output.startsWith("## ") && output.stripTrailing().lines().count() == 1)
      output = output.stripTrailing() + "\nworking tree clean";
    return described(args, output);
  }

  private ToolResult diff(JsonNode arguments) {
    var args = new ArgReader(arguments);
    String rawPath = args.string("path", "");
    Path checked = rawPath.isEmpty() ? sandbox.workspaceRoot() : checked(rawPath, "git_diff");
    if (checked == null) return outside(rawPath, "git_diff");
    Path repository = resolveRepository(checked);
    var command = new ArrayList<>(List.of("git", "-C", repository.toString(), "diff", "--stat", "-p"));
    if (args.bool("staged", false)) command.add("--cached");
    String ref = args.string("ref", "");
    if (!ref.isEmpty()) command.add(ref);
    if (!rawPath.isEmpty()) { command.add("--"); command.add(checked.toString()); }
    ToolResult result = git(command, "git_diff", 50_000);
    if (result instanceof ToolResult.Failure) return result;
    String output = ((ToolResult.Success) result).output().text();
    return output.isEmpty() ? success("no changes") : described(args, output);
  }

  private ToolResult log(JsonNode arguments) {
    var args = new ArgReader(arguments);
    int count = Math.clamp(args.integer("count", 20), 1, 1000);
    String rawPath = args.string("path", "");
    Path checked = rawPath.isEmpty() ? sandbox.workspaceRoot() : checked(rawPath, "git_log");
    if (checked == null) return outside(rawPath, "git_log");
    Path repository = resolveRepository(checked);
    var command = new ArrayList<>(List.of("git", "-C", repository.toString(), "log"));
    if (args.bool("oneline", false)) command.add("--oneline");
    else { command.add("--format=%h %ad %an%n  %s"); command.add("--date=short"); }
    command.add("-" + count);
    String ref = args.string("ref", "HEAD");
    command.add(ref.isEmpty() ? "HEAD" : ref);
    if (!rawPath.isEmpty()) { command.add("--"); command.add(checked.toString()); }
    ToolResult result = git(command, "git_log", 30_000);
    if (result instanceof ToolResult.Failure) return result;
    String output = ((ToolResult.Success) result).output().text();
    return output.isEmpty() ? success("no commits") : described(args, output);
  }

  private ToolResult commit(JsonNode arguments) {
    var args = new ArgReader(arguments);
    String message = args.requiredString("message").orElse(null);
    if (message == null) return failure(ToolErrorKind.INVALID_ARGS, "commit message required");
    message = message.strip();
    if (message.isEmpty()) return failure(ToolErrorKind.INVALID_ARGS,
        "commit message is empty / whitespace only");
    var files = new ArrayList<String>();
    JsonNode rawFiles = args.raw("files");
    if (rawFiles != null && rawFiles.isArray()) rawFiles.forEach(file -> {
      if (file.isTextual() && !file.textValue().isEmpty()) files.add(file.textValue());
    });
    String rawPath = args.string("path", "");
    String hint = !rawPath.isEmpty() ? rawPath : files.isEmpty() ? "" : files.getFirst();
    Path checked = hint.isEmpty() ? sandbox.workspaceRoot() : checked(hint, "git_commit");
    if (checked == null) return outside(hint, "git_commit");
    Path repository = resolveRepository(checked);
    if (args.bool("stage_all", false)) {
      ToolResult add = git(List.of("git", "-C", repository.toString(), "add", "-A"),
          "git_commit (add -A)", 30_000);
      if (add instanceof ToolResult.Failure) return add;
    }
    for (String file : files) {
      Path path = checked(file, "git_commit");
      if (path == null) return outside(file, "git_commit");
      ToolResult add = git(List.of("git", "-C", repository.toString(), "add", "--", path.toString()),
          "git_commit (add)", 30_000);
      if (add instanceof ToolResult.Failure) return add;
    }
    ProcessRunner.Result result = runner.argv(List.of("git", "-C", repository.toString(), "commit",
        "-m", message), sandbox.workspaceRoot(), 30_000, TIMEOUT);
    if (!result.started() || result.timedOut() || result.exitCode() != 0) {
      if (result.output().contains("nothing to commit")
          || result.output().contains("no changes added to commit"))
        return failure(ToolErrorKind.INVALID_ARGS, "nothing to commit - working tree clean, or no files "
            + "staged. Pass `stage_all: true`, or list files in `files: [...]`.");
      return classify(result, "git_commit");
    }
    return described(args, result.output());
  }

  private ToolResult git(List<String> command, String operation, int maxBytes) {
    ProcessRunner.Result result = runner.argv(command, sandbox.workspaceRoot(), maxBytes, TIMEOUT);
    if (!result.started() || result.timedOut() || result.exitCode() != 0) return classify(result, operation);
    return success(result.output() + (result.truncated() ? "\n[output truncated]" : ""));
  }

  private ToolResult classify(ProcessRunner.Result result, String operation) {
    if (!result.started()) return failure(ToolErrorKind.SPAWN, operation + ": " + result.startError()
        + " (is `git` installed and on PATH?)");
    String output = result.output();
    if (output.contains("not a git repository")) return failure(ToolErrorKind.NOT_FOUND, operation
        + " failed: not inside a git repository. Run `git init` first, or invoke from a directory under an existing repo.");
    if (output.contains("Please tell me who you are") || output.contains("empty ident"))
      return failure(ToolErrorKind.SUBPROCESS, operation + " failed: git identity not configured.");
    if (output.contains("unknown revision") || output.contains("bad revision")
        || output.contains("ambiguous argument")) return failure(ToolErrorKind.NOT_FOUND,
            operation + " failed: unknown revision/ref. " + output);
    if (output.contains(".git/index.lock")) return failure(ToolErrorKind.SUBPROCESS,
        operation + " failed: another git process holds .git/index.lock.");
    if (result.timedOut()) return failure(ToolErrorKind.SUBPROCESS,
        operation + " timed out. Output so far:\n" + output);
    return failure(ToolErrorKind.SUBPROCESS, operation + " failed (exit " + result.exitCode()
        + "):\n" + output);
  }

  private Path resolveRepository(Path hint) {
    Path directory = Files.isDirectory(hint) ? hint : hint.getParent();
    if (directory == null) directory = sandbox.workspaceRoot();
    ProcessRunner.Result result = runner.argv(List.of("git", "-C", directory.toString(), "rev-parse",
        "--show-toplevel"), sandbox.workspaceRoot(), 4096, TIMEOUT);
    if (result.started() && !result.timedOut() && result.exitCode() == 0 && !result.output().isBlank()) {
      Path top = Path.of(result.output().strip()).toAbsolutePath().normalize();
      return sandbox.isWithin(top) ? top : sandbox.workspaceRoot();
    }
    return directory;
  }

  private Path checked(String raw, String operation) {
    Path path = sandbox.normalize(raw);
    return sandbox.isWithin(path) ? path : null;
  }
  private static ToolResult described(ArgReader args, String output) {
    String description = args.string("display_description", "");
    return success(description.isEmpty() ? output : description + '\n' + output);
  }
  private static ToolResult outside(String path, String operation) {
    return failure(ToolErrorKind.OUT_OF_WORKSPACE, operation + ": path is outside workspace: " + path);
  }
  private static ToolResult success(String text) { return new ToolResult.Success(new ToolOutput(text)); }
  private static ToolResult failure(ToolErrorKind kind, String detail) {
    return new ToolResult.Failure(new ToolError(kind, detail));
  }
}
