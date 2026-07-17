package com.github.skanga.ajent.tools.git;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.tools.fs.WorkspaceSandbox;
import com.github.skanga.ajent.tools.runtime.ToolErrorKind;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitToolsTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void portsPinnedGitToolEndToEndContract(@TempDir Path root) throws Exception {
    run(root, "git", "init", "-q");
    run(root, "git", "config", "user.email", "t@t.t");
    run(root, "git", "config", "user.name", "T");
    Path file = Files.writeString(root.resolve("alpha.txt"), "one\n");
    var tools = new GitTools(new WorkspaceSandbox(root, root, root));

    assertThat(success(tools.execute("git_status", JSON.createObjectNode()
        .put("path", root.toString())))).contains("## ", "alpha.txt").doesNotContain("branch.head");
    assertThat(success(tools.execute("git_commit", JSON.createObjectNode()
        .put("message", "seed commit").put("stage_all", true)))).contains("seed commit");
    assertThat(success(tools.execute("git_log", JSON.createObjectNode().put("oneline", true))))
        .contains("seed commit");
    assertThat(success(tools.execute("git_status", JSON.createObjectNode())))
        .contains("working tree clean");
    Files.writeString(file, "one\ntwo\n");
    assertThat(success(tools.execute("git_diff", JSON.createObjectNode().put("path", file.toString()))))
        .contains("+two", "alpha.txt");
    run(root, "git", "add", "alpha.txt");
    assertThat(success(tools.execute("git_diff", JSON.createObjectNode().put("staged", true))))
        .contains("+two");
    assertThat(failure(tools.execute("git_commit", JSON.createObjectNode().put("message", "  ")))
        .error().kind()).isEqualTo(ToolErrorKind.INVALID_ARGS);
  }

  @Test
  void stagesExplicitFilesAndClassifiesCommonFailures(@TempDir Path root,
      @TempDir Path outside) throws Exception {
    var tools = new GitTools(new WorkspaceSandbox(root, root, root));
    assertThat(failure(tools.execute("git_status", JSON.createObjectNode()))
        .error().kind()).isEqualTo(ToolErrorKind.NOT_FOUND);
    assertThat(failure(tools.execute("git_diff", JSON.createObjectNode()
        .put("path", outside.toString()))).error().kind()).isEqualTo(ToolErrorKind.OUT_OF_WORKSPACE);
    assertThat(failure(tools.execute("git_commit", JSON.createObjectNode()))
        .error().detail()).contains("commit message required");

    run(root, "git", "init", "-q");
    run(root, "git", "config", "user.email", "t@t.t");
    run(root, "git", "config", "user.name", "T");
    Path one = Files.writeString(root.resolve("one.txt"), "one");
    Files.writeString(root.resolve("two.txt"), "two");
    var files = JSON.createArrayNode().add(one.toString()).add(4).add("");
    assertThat(success(tools.execute("git_commit", JSON.createObjectNode().put("message", "only one")
        .set("files", files)))).contains("only one");
    assertThat(success(tools.execute("git_status", JSON.createObjectNode()))).contains("two.txt");
    assertThat(failure(tools.execute("git_commit", JSON.createObjectNode().put("message", "nothing")))
        .error().kind()).isEqualTo(ToolErrorKind.SUBPROCESS);
    assertThat(failure(tools.execute("git_log", JSON.createObjectNode().put("ref", "missing-ref")))
        .error().kind()).isEqualTo(ToolErrorKind.NOT_FOUND);
    assertThat(failure(tools.execute("missing", JSON.createObjectNode()))
        .error().kind()).isEqualTo(ToolErrorKind.UNKNOWN);
  }

  private static void run(Path root, String... command) throws Exception {
    Process process = new ProcessBuilder(List.of(command)).directory(root.toFile())
        .redirectErrorStream(true).start();
    assertThat(process.waitFor()).isZero();
  }
  private static String success(ToolResult result) {
    return ((ToolResult.Success) result).output().text();
  }
  private static ToolResult.Failure failure(ToolResult result) { return (ToolResult.Failure) result; }
}
