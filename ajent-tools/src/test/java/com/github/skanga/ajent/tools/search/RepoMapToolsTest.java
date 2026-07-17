package com.github.skanga.ajent.tools.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.tools.fs.WorkspaceSandbox;
import com.github.skanga.ajent.tools.runtime.ToolErrorKind;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoMapToolsTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void portsRankedFocusedAndBudgetedRepositoryMap(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("alpha.cpp"),
        "int compute_sum(int a, int b) {\n  return a + b;\n}\n");
    Files.writeString(root.resolve("gamma.py"),
        "def compute_total(x):\n    return compute_sum(x, x)\n");
    var tool = new RepoMapTools(new WorkspaceSandbox(root, root, root));

    String map = success(tool.execute(JSON.createObjectNode()));
    assertThat(map).contains("Repository map (2 files ranked", "alpha.cpp:", "L1:",
        "compute_sum", "gamma.py:", "compute_total");
    String focused = success(tool.execute(JSON.createObjectNode().put("focus", "compute_total")));
    assertThat(focused).contains("focused on 'compute_total'");
    assertThat(focused.indexOf("gamma.py:")).isLessThan(focused.indexOf("alpha.cpp:"));
    String budgeted = success(tool.execute(JSON.createObjectNode().put("budget", 1000)));
    assertThat(budgeted.getBytes()).hasSizeLessThan(2200);
  }

  @Test
  void handlesNoSourcesCapsBudgetAndRejectsEscapes(@TempDir Path root,
      @TempDir Path outside) throws Exception {
    var tool = new RepoMapTools(new WorkspaceSandbox(root, root, root));
    ToolResult.Failure empty = (ToolResult.Failure) tool.execute(JSON.createObjectNode());
    assertThat(empty.error().detail()).contains("no source files");
    assertThat(((ToolResult.Failure) tool.execute(JSON.createObjectNode()
        .put("path", outside.toString()))).error().kind()).isEqualTo(ToolErrorKind.OUT_OF_WORKSPACE);
    Files.writeString(root.resolve("tiny.java"), "class Tiny {}\n");
    assertThat(success(tool.execute(JSON.createObjectNode().put("budget", -20))))
        .contains("budget 1000 bytes");
  }

  private static String success(ToolResult result) {
    return ((ToolResult.Success) result).output().text();
  }
}
