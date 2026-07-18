package com.github.skanga.ajent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.tools.fs.FileTools;
import com.github.skanga.ajent.tools.fs.WorkspaceSandbox;
import com.github.skanga.ajent.tools.runtime.ToolDispatcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DispatcherToolPortTest {
  @Test void mapsDispatcherSuccessAndTypedFailure(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("note.txt"), "hello from file");
    var files = new FileTools(new WorkspaceSandbox(root, root, root));
    var dispatcher = new ToolDispatcher(files, null, null, null, null, null, null, null);
    var port = new DispatcherToolPort(dispatcher);
    ToolCompletion success = port.execute(call("read", Map.of("path", "note.txt")));
    assertThat(success).isInstanceOfSatisfying(ToolCompletion.Success.class,
        result -> {
          assertThat(result.output()).contains("hello from file");
          assertThat(result.change()).isEmpty();
        });
    ToolCompletion written = port.execute(call("write", Map.of(
        "path", "changed.txt", "content", "new body")));
    assertThat(written).isInstanceOfSatisfying(ToolCompletion.Success.class,
        result -> assertThat(result.change()).hasValueSatisfying(change -> {
          assertThat(change.path()).endsWith("changed.txt");
          assertThat(change.after()).isEqualTo("new body");
        }));
    ToolCompletion failure = port.execute(call("does_not_exist", Map.of()));
    assertThat(failure).isInstanceOfSatisfying(ToolCompletion.Failure.class,
        result -> assertThat(result.error()).contains("[unknown]", "unknown tool"));
  }

  private static ToolUse call(String name, Map<String, Object> arguments) {
    return new ToolUse(new ToolCallId("id"), new ToolName(name), arguments,
        new ToolStatus.Running(""));
  }
}
