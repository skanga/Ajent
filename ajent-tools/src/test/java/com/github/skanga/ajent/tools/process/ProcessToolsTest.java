package com.github.skanga.ajent.tools.process;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.tools.fs.WorkspaceSandbox;
import com.github.skanga.ajent.tools.runtime.ToolErrorKind;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessToolsTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void portsPinnedBashAndDiagnosticsContract(@TempDir Path root) throws Exception {
    var tools = tools(root);
    assertThat(success(tools.execute("bash", JSON.createObjectNode()
        .put("command", "echo hello_from_bash")))).contains("hello_from_bash", "```");
    assertThat(success(tools.execute("bash", JSON.createObjectNode()
        .put("command", "exit /b 7")))).contains("failed with exit code 7");
    assertThat(failure(tools.execute("bash", JSON.createObjectNode().put("command", "")))
        .error().kind()).isEqualTo(ToolErrorKind.INVALID_ARGS);
    assertThat(failure(tools.execute("diagnostics", JSON.createObjectNode()))
        .error().kind()).isEqualTo(ToolErrorKind.NOT_FOUND);
    assertThat(success(tools.execute("diagnostics", JSON.createObjectNode()
        .put("command", "echo build_ok")))).contains("build_ok");
  }

  @Test
  void enforcesWorkingDirectoryTimeoutAnsiAndResultFormatting(@TempDir Path root,
      @TempDir Path outside) throws Exception {
    var tools = tools(root);
    Files.createDirectory(root.resolve("sub"));
    assertThat(success(tools.execute("bash", JSON.createObjectNode().put("command", "cd")
        .put("cwd", root.resolve("sub").toString()).put("display_description", "Where"))))
        .startsWith("Where\n```").contains(root.resolve("sub").toString());
    assertThat(failure(tools.execute("bash", JSON.createObjectNode().put("command", "echo no")
        .put("cd", outside.toString()))).error().kind()).isEqualTo(ToolErrorKind.OUT_OF_WORKSPACE);
    assertThat(failure(tools.execute("bash", JSON.createObjectNode().put("command", "python")))
        .error().detail()).contains("REPL");
    assertThat(success(tools.execute("bash", JSON.createObjectNode()
        .put("command", "powershell -NoProfile -Command \"Start-Sleep -Seconds 2\"")
        .put("timeout", 1)))).contains("timed out after 1s");
    assertThat(ProcessTools.stripAnsi("a\u001b[31mred\u001b[0m\u001b]0;title\u0007z"))
        .isEqualTo("aredz");
    assertThat(failure(tools.execute("missing", JSON.createObjectNode()))
        .error().kind()).isEqualTo(ToolErrorKind.UNKNOWN);
  }

  @Test
  void formatsEverySubprocessStateAndDiagnosticsSummary(@TempDir Path root) throws Exception {
    var fake = new FakeRunner();
    var tools = new ProcessTools(new WorkspaceSandbox(root, root, root), fake);
    assertThat(failure(tools.execute("bash", JSON.createObjectNode()))
        .error().detail()).isEqualTo("command required");
    assertThat(failure(tools.execute("bash", JSON.createObjectNode().put("command", "echo x")
        .put("cd", root.resolve("missing").toString()))).error().kind())
        .isEqualTo(ToolErrorKind.INVALID_ARGS);

    fake.add(new ProcessRunner.Result(false, -1, "", false, false, "no process"));
    assertThat(failure(tools.execute("bash", JSON.createObjectNode().put("command", "echo x")))
        .error().kind()).isEqualTo(ToolErrorKind.SPAWN);
    fake.add(new ProcessRunner.Result(true, 0, "", false, false, ""));
    assertThat(success(tools.execute("bash", JSON.createObjectNode().put("command", "echo x")
        .put("timeout_ms", 1)))).isEqualTo("Command executed successfully.");
    fake.add(new ProcessRunner.Result(true, 2, "bad", false, true, ""));
    assertThat(success(tools.execute("bash", JSON.createObjectNode().put("command", "echo x"))))
        .contains("exit code 2", "bad", "output truncated");
    fake.add(new ProcessRunner.Result(true, 2, "", false, false, ""));
    assertThat(success(tools.execute("bash", JSON.createObjectNode().put("command", "echo x"))))
        .isEqualTo("Command \"echo x\" failed with exit code 2.");
    fake.add(new ProcessRunner.Result(true, 1, "", true, false, ""));
    assertThat(success(tools.execute("bash", JSON.createObjectNode().put("command", "echo x"))))
        .contains("No output was captured");

    fake.add(new ProcessRunner.Result(true, 1,
        "error: broken\nWarning: caution\n", false, false, ""));
    assertThat(success(tools.execute("diagnostics", JSON.createObjectNode()
        .put("command", "build").put("display_description", "Checking"))))
        .startsWith("Checking\nX 1 error(s), 1 warning(s)").contains("First errors", "Full output");
    fake.add(new ProcessRunner.Result(true, 0, "", false, false, ""));
    assertThat(success(tools.execute("diagnostics", JSON.createObjectNode().put("command", "build"))))
        .isEqualTo("no diagnostics (clean build)");
  }

  @Test
  void autoDetectsEachPinnedBuildSystem(@TempDir Path root) throws Exception {
    assertDetected(root.resolve("cmake"), "build/build.ninja", List.of("cmake", "--build", "build"));
    assertDetected(root.resolve("cargo"), "Cargo.toml", List.of("cargo", "check"));
    assertDetected(root.resolve("go"), "go.mod", List.of("go", "build", "./..."));
    assertDetected(root.resolve("node"), "package.json", List.of("npx", "tsc", "--noEmit"));
    assertDetected(root.resolve("make"), "Makefile", List.of("make", "-n"));
  }

  @Test
  void processRunnerReportsSpawnAndCaptureTruncation(@TempDir Path root) {
    var runner = new ProcessRunner();
    assertThat(runner.argv(List.of("definitely-not-an-ajent-command"), root, 100,
        Duration.ofSeconds(1)).started()).isFalse();
    ProcessRunner.Result result = runner.shell("echo 123456", root, 2, Duration.ofSeconds(2));
    assertThat(result.started()).isTrue();
    assertThat(result.truncated()).isTrue();
    assertThat(result.output().getBytes()).hasSize(2);
  }

  @Test
  void persistsOversizedBashOutputAndReturnsTheNativeBoundedEnvelope(@TempDir Path root)
      throws Exception {
    var fake = new FakeRunner();
    String errors = String.join("\n", "error: lowercase", "Error: title case", "ERROR: uppercase",
        "FAILED build", "error[E123]", "thread panicked", "Traceback follows",
        "RuntimeException") + '\n';
    String full = "H".repeat(2_100) + '\n' + errors + "M".repeat(31_000) + "T".repeat(1_100);
    fake.add(new ProcessRunner.Result(true, 0, full, false, true, ""));
    var sandbox = new WorkspaceSandbox(root, root, root);
    Path spill = root.resolve("spill");
    var tools = new ProcessTools(sandbox, fake, spill);

    String result = success(tools.execute("bash",
        JSON.createObjectNode().put("command", "large output")));

    assertThat(result).contains("<persisted-output>", "Output too large (",
        "Full output saved to: ",
        "Preview (first 2000 bytes):", "❌ Errors found", "error: lowercase",
        "Error: title case", "ERROR: uppercase", "FAILED build", "error[E123]",
        "thread panicked", "Traceback follows", "RuntimeException",
        "Tail (last 1000 bytes):", "bytes elided", "</persisted-output>")
        .doesNotContain("[output truncated at");
    String marker = "Full output saved to: ";
    int pathStart = result.indexOf(marker) + marker.length();
    String pathText = result.substring(pathStart, result.indexOf('\n', pathStart));
    Path persisted = Path.of(pathText);
    assertThat(Files.readString(persisted)).isEqualTo(full);
    assertThat(sandbox.isReadable(persisted)).isTrue();
  }

  @Test
  void returnsBoundedNativeEnvelopeWhenSpillFileCannotBeCreated(@TempDir Path root)
      throws Exception {
    var fake = new FakeRunner();
    fake.add(new ProcessRunner.Result(true, 0, "plain output\n".repeat(3_000), false, true, ""));
    Path blockedSpillRoot = root.resolve("not-a-directory");
    Files.writeString(blockedSpillRoot, "occupied");
    var tools = new ProcessTools(new WorkspaceSandbox(root, root, root), fake, blockedSpillRoot);

    String result = success(tools.execute("bash",
        JSON.createObjectNode().put("command", "large output")));

    assertThat(result).contains("<persisted-output>",
        "(spill file unavailable; output truncated.)", "Tail (last 1000 bytes):")
        .doesNotContain("Full output saved to:", "❌ Errors found", "[output truncated at");
  }

  private static void assertDetected(Path root, String marker, List<String> expected) throws Exception {
    Files.createDirectories(root.resolve(marker).getParent());
    Files.writeString(root.resolve(marker), "");
    var fake = new FakeRunner();
    fake.add(new ProcessRunner.Result(true, 0, "ok", false, false, ""));
    var tools = new ProcessTools(new WorkspaceSandbox(root, root, root), fake);
    assertThat(success(tools.execute("diagnostics", JSON.createObjectNode()))).isEqualTo("ok");
    assertThat(fake.argv).isEqualTo(expected);
  }

  private static final class FakeRunner extends ProcessRunner {
    private final ArrayDeque<Result> results = new ArrayDeque<>();
    private List<String> argv;
    private void add(Result result) { results.add(result); }
    @Override public Result shell(String command, Path directory, int maxBytes, Duration timeout) {
      return results.remove();
    }
    @Override public Result shellWithProgress(
        String command, Path directory, int maxBytes, Duration timeout,
        Consumer<String> progress) {
      Result result = results.remove();
      progress.accept(result.output());
      return result;
    }
    @Override public Result argv(List<String> arguments, Path directory, int maxBytes,
        Duration timeout) {
      argv = List.copyOf(arguments);
      return results.remove();
    }
    @Override public Result argvWithProgress(List<String> arguments, Path directory, int maxBytes,
        Duration timeout, Consumer<String> progress) {
      argv = List.copyOf(arguments);
      Result result = results.remove();
      progress.accept(result.output());
      return result;
    }
  }

  private static ProcessTools tools(Path root) {
    return new ProcessTools(new WorkspaceSandbox(root, root, root));
  }
  private static String success(ToolResult result) {
    return ((ToolResult.Success) result).output().text();
  }
  private static ToolResult.Failure failure(ToolResult result) { return (ToolResult.Failure) result; }
}
