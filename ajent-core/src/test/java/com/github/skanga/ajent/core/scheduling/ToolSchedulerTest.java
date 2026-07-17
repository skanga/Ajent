package com.github.skanga.ajent.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ToolSchedulerTest {
  private final AtomicInteger ids = new AtomicInteger();

  @Test
  void portsEveryPathAwareSchedulingConditionFromAgenTTY() {
    assertThat(schedule(
            pending("write", "file_path", "a.cpp"),
            pending("write", "file_path", "b.cpp")))
        .containsExactly(0, 1);

    assertThat(schedule(
            pending("write", "file_path", "same.cpp"),
            pending("edit", "path", "same.cpp")))
        .containsExactly(0);

    assertThat(schedule(
            pending("read", "path", "a.cpp"),
            pending("write", "file_path", "b.cpp")))
        .containsExactly(0, 1);

    assertThat(schedule(
            pending("read", "path", "a.cpp"),
            pending("write", "file_path", "a.cpp")))
        .containsExactly(0);

    assertThat(schedule(
            pending("edit", "path", "src/a.c"),
            pending("write", "file_path", "src")))
        .containsExactly(0);

    assertThat(schedule(
            pending("write", "file_path", "src"),
            pending("write", "file_path", "srcfoo")))
        .containsExactly(0, 1);

    assertThat(schedule(
            pending("read", "path", "a.cpp"),
            pending("bash", "command", "rm -rf build"),
            pending("read", "path", "b.cpp")))
        .containsExactly(0, 2);

    assertThat(schedule(
            pending("read", "path", "a.cpp"),
            pending("write", Map.of("content", "no path here"))))
        .containsExactly(0);

    assertThat(schedule(
            pending("read", "path", "a"),
            pending("read", "path", "b"),
            pending("read", "path", "c"),
            pending("grep", "pattern", "foo")))
        .containsExactly(0, 1, 2, 3);

    assertThat(schedule(
            running("write", "file_path", "a.cpp"),
            pending("read", "path", "a.cpp"),
            pending("read", "path", "b.cpp")))
        .containsExactly(2);

    assertThat(schedule(
            pending("task", "prompt", "explore module A"),
            pending("task", "prompt", "explore module B"),
            pending("task", "prompt", "review the diff")))
        .containsExactly(0, 1, 2);

    assertThat(schedule(
            pending("task", "prompt", "map the codebase"),
            pending("read", "path", "a.cpp"),
            pending("grep", "pattern", "foo")))
        .containsExactly(0, 1, 2);

    assertThat(schedule(
            pending("bash", "command", "make -j8"),
            pending("task", "prompt", "explore while building")))
        .containsExactly(0);
  }

  @Test
  void approvedCallsCanRunWhileTerminalCallsAreIgnored() {
    var approved = call("read", Map.of("path", "a"), new ToolStatus.Approved());
    var done = call("read", Map.of("path", "b"), new ToolStatus.Done("ok"));
    var failed = call("read", Map.of("path", "c"), new ToolStatus.Failed("no"));
    var rejected = call("read", Map.of("path", "d"), new ToolStatus.Rejected());

    assertThat(schedule(approved, done, failed, rejected)).containsExactly(0);
  }

  @Test
  void extractsEveryReferencePathAliasAndScopedDirectoryAlias() {
    assertThat(schedule(
            pending("write", "filepath", "a"), pending("write", "filename", "a/x")))
        .containsExactly(0);
    assertThat(schedule(
            pending("write", "file_path", "root/x"), pending("grep", "dir", "root")))
        .containsExactly(0);
    assertThat(schedule(
            pending("write", "file_path", "root/x"), pending("glob", "directory", "root")))
        .containsExactly(0);
    assertThat(schedule(
            pending("write", "file_path", "root/x"), pending("list_dir", "root", "root")))
        .containsExactly(0);
  }

  @Test
  void emptyAndNonStringPathsAreUnknownAndUnknownToolsAreExclusive() {
    assertThat(schedule(pending("write", "file_path", ""), pending("read", "path", "a")))
        .containsExactly(0);
    assertThat(schedule(
            pending("write", Map.of("file_path", 42)), pending("read", "path", "a")))
        .containsExactly(0);
    assertThat(schedule(pending("new_tool", Map.of()), pending("read", "path", "a")))
        .containsExactly(0);
  }

  @Test
  void decisionsAreImmutableAndPathOverlapHonorsDirectoryBoundaries() {
    var decision = ToolScheduler.scheduleParallelBatch(List.of(pending("read", "path", "a")));
    assertThat(decision.promote()).isUnmodifiable();
    assertThat(ToolScheduler.pathsOverlap("src/", "src/a.c")).isTrue();
    assertThat(ToolScheduler.pathsOverlap("src/a.c", "src")).isTrue();
    assertThat(ToolScheduler.pathsOverlap("src", "srcfoo")).isFalse();
  }

  @Nested
  class Effects {
    @Test
    void permissionCatalogAndTaskSchedulingViewMatchTheReference() {
      assertThat(ToolEffects.permissionEffects("todo")).isEmpty();
      assertThat(ToolEffects.permissionEffects("edit"))
          .containsExactlyInAnyOrder(ToolEffects.Effect.READ_FS, ToolEffects.Effect.WRITE_FS);
      assertThat(ToolEffects.permissionEffects("task"))
          .containsExactly(ToolEffects.Effect.EXEC);
      assertThat(ToolEffects.schedulingEffects("task"))
          .containsExactlyInAnyOrder(ToolEffects.Effect.READ_FS, ToolEffects.Effect.NET);
      assertThat(ToolEffects.permissionEffects("unknown"))
          .containsExactly(ToolEffects.Effect.EXEC);
    }

    @Test
    void onlyReadNetAndPureEffectsCompose() {
      assertThat(ToolEffects.isParallelSafe(
              ToolEffects.permissionEffects("read"), ToolEffects.permissionEffects("web_search")))
          .isTrue();
      assertThat(ToolEffects.isParallelSafe(
              ToolEffects.permissionEffects("read"), ToolEffects.permissionEffects("write")))
          .isFalse();
      assertThat(ToolEffects.isParallelSafe(
              ToolEffects.permissionEffects("write"), ToolEffects.permissionEffects("read")))
          .isFalse();
      assertThat(ToolEffects.isParallelSafe(
              ToolEffects.permissionEffects("todo"), ToolEffects.permissionEffects("bash")))
          .isTrue();
    }
  }

  private List<Integer> schedule(ToolUse... calls) {
    return ToolScheduler.scheduleParallelBatch(List.of(calls)).promote();
  }

  private ToolUse pending(String name, String key, String value) {
    return pending(name, Map.of(key, value));
  }

  private ToolUse pending(String name, Map<String, Object> arguments) {
    return call(name, arguments, new ToolStatus.Pending());
  }

  private ToolUse running(String name, String key, String value) {
    return call(name, Map.of(key, value), new ToolStatus.Running(""));
  }

  private ToolUse call(String name, Map<String, Object> arguments, ToolStatus status) {
    return new ToolUse(
        new ToolCallId("c" + ids.incrementAndGet()), new ToolName(name), arguments, status);
  }
}
