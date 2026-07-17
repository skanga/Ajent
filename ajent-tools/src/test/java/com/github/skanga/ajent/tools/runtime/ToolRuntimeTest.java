package com.github.skanga.ajent.tools.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ToolRuntimeTest {
  @Test
  void errorLabelsAndRenderingMatchAgenTTY() {
    assertThat(ToolErrorKind.values()).extracting(Object::toString).containsExactly(
        "invalid args", "not found", "not a file", "not a directory", "too large", "binary",
        "ambiguous", "no match", "invalid regex", "network", "spawn failed",
        "subprocess failed", "io", "out of workspace", "unknown");
    assertThat(new ToolError(ToolErrorKind.NOT_FOUND, "x").render())
        .isEqualTo("[not found] x");
  }

  @Test
  void outputsAndChangesRemainTyped() {
    var change = new FileChange("x", 1, 2, "before", "after");
    var output = new ToolOutput("done", Optional.of(change));
    assertThat(new ToolResult.Success(output).output().change()).contains(change);
    assertThat(new ToolResult.Failure(new ToolError(ToolErrorKind.IO, "bad")).error().detail())
        .isEqualTo("bad");
  }
}
