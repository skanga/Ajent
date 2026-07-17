package com.github.skanga.ajent.tools.git;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.tools.fs.WorkspaceSandbox;
import com.github.skanga.ajent.tools.process.ProcessRunner;
import com.github.skanga.ajent.tools.runtime.ToolErrorKind;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitToolsClassificationTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void classifiesSpawnIdentityLockTimeoutAndGenericFailures(@TempDir Path root) {
    assertFailure(root, result(false, -1, "", false, false, "missing"), ToolErrorKind.SPAWN,
        "installed and on PATH");
    assertFailure(root, result(true, 1, "Please tell me who you are", false, false, ""),
        ToolErrorKind.SUBPROCESS, "identity not configured");
    assertFailure(root, result(true, 1, "fatal .git/index.lock exists", false, false, ""),
        ToolErrorKind.SUBPROCESS, "index.lock");
    assertFailure(root, result(true, 1, "partial", true, false, ""),
        ToolErrorKind.SUBPROCESS, "timed out");
    assertFailure(root, result(true, 9, "odd failure", false, false, ""),
        ToolErrorKind.SUBPROCESS, "exit 9");
  }

  @Test
  void formatsDescriptionsTruncationEmptyDiffAndDetailedLog(@TempDir Path root) {
    var fake = new FakeRunner();
    var tools = tools(root, fake);
    fake.resolveThen(result(true, 0, "## main\n", false, true, ""));
    assertThat(success(tools.execute("git_status", JSON.createObjectNode()
        .put("display_description", "Checking")))).startsWith("Checking\n## main")
        .contains("output truncated");
    fake.resolveThen(result(true, 0, "", false, false, ""));
    assertThat(success(tools.execute("git_status", JSON.createObjectNode())))
        .isEqualTo("working tree clean");
    fake.resolveThen(result(true, 0, "", false, false, ""));
    assertThat(success(tools.execute("git_diff", JSON.createObjectNode()))).isEqualTo("no changes");
    fake.resolveThen(result(true, 0, "abc 2026-01-01 T\n  subject\n", false, false, ""));
    assertThat(success(tools.execute("git_log", JSON.createObjectNode().put("count", 5000)
        .put("display_description", "History")))).startsWith("History\nabc");
  }

  private static void assertFailure(Path root, ProcessRunner.Result operation,
      ToolErrorKind kind, String detail) {
    var fake = new FakeRunner();
    fake.resolveThen(operation);
    ToolResult.Failure failure = (ToolResult.Failure) tools(root, fake)
        .execute("git_status", JSON.createObjectNode());
    assertThat(failure.error().kind()).isEqualTo(kind);
    assertThat(failure.error().detail()).contains(detail);
  }

  private static GitTools tools(Path root, FakeRunner fake) {
    return new GitTools(new WorkspaceSandbox(root, root, root), fake);
  }
  private static ProcessRunner.Result result(boolean started, int code, String output,
      boolean timedOut, boolean truncated, String error) {
    return new ProcessRunner.Result(started, code, output, timedOut, truncated, error);
  }
  private static String success(ToolResult result) {
    return ((ToolResult.Success) result).output().text();
  }

  private static final class FakeRunner extends ProcessRunner {
    private final ArrayDeque<Result> results = new ArrayDeque<>();
    private void resolveThen(Result operation) {
      results.add(result(false, -1, "", false, false, "not a repo"));
      results.add(operation);
    }
    @Override public Result argv(List<String> argv, Path directory, int maxBytes,
        Duration timeout) {
      return results.remove();
    }
  }
}
