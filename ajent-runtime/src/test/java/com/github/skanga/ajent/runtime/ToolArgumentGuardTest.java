package com.github.skanga.ajent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ToolArgumentGuardTest {
  @Test void validatesWriteAndEditAliasesAndAlternativeEditShapes() {
    assertThat(missing("write", Map.of())).isEqualTo("path");
    assertThat(missing("write", Map.of("filename", "x"))).isEqualTo("content");
    assertThat(missing("write", Map.of("file_path", "x", "file_content", "y"))).isEmpty();
    assertThat(missing("write", Map.of("path", "", "content", "x"))).isEqualTo("path");

    assertThat(missing("edit", Map.of())).isEqualTo("path");
    assertThat(missing("edit", Map.of("filepath", "x", "edits", List.of(Map.of("x", 1)))))
        .isEmpty();
    assertThat(missing("edit", Map.of("path", "x"))).isEqualTo("old_string");
    assertThat(missing("edit", Map.of("path", "x", "oldStr", "before")))
        .isEqualTo("new_string");
    assertThat(missing("edit", Map.of("filename", "x", "old_str", "a", "new_str", "b")))
        .isEmpty();
  }

  @Test void validatesEveryNativeSingleFieldGuardAndLeavesUngatedToolsAlone() {
    Map<String, String> required = Map.ofEntries(
        Map.entry("bash", "command"), Map.entry("diagnostics", "command"),
        Map.entry("grep", "pattern"), Map.entry("find_definition", "symbol"),
        Map.entry("search_docs", "query"), Map.entry("web_fetch", "url"),
        Map.entry("git_commit", "message"), Map.entry("remember", "text"),
        Map.entry("task", "prompt"), Map.entry("skill", "name"));
    required.forEach((tool, field) -> {
      assertThat(missing(tool, Map.of())).isEqualTo(field);
      assertThat(missing(tool, Map.of(field, "value"))).isEmpty();
      assertThat(missing(tool, Map.of(field, 7))).isEqualTo(field);
    });
    for (String tool : List.of("read", "list_dir", "glob", "git_diff", "git_log",
        "git_status", "web_search", "todo", "forget", "wipe_memory", "future_tool"))
      assertThat(missing(tool, Map.of())).isEmpty();
  }

  private static String missing(String name, Map<String, Object> arguments) {
    return AgentReducer.missingRequiredField(new ToolUse(new ToolCallId("id"),
        new ToolName(name), arguments, new ToolStatus.Pending()));
  }
}
