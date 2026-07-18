package com.github.skanga.ajent.tools.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.CheckpointId;
import com.github.skanga.ajent.tools.process.ProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GitCheckpointStoreTest {
  @Test void snapshotsSummarizesAndRestoresTrackedAndUntrackedWithoutIgnoredFiles(
      @TempDir Path directory) throws Exception {
    var runner = new ProcessRunner();
    git(runner, directory, "init", "-q");
    git(runner, directory, "config", "core.autocrlf", "false");
    Files.writeString(directory.resolve(".gitignore"), "ignored.txt\n");
    Files.writeString(directory.resolve("tracked.txt"), "before\n");
    Files.writeString(directory.resolve("existing.txt"), "keep\n");
    Files.writeString(directory.resolve("ignored.txt"), "old ignored\n");
    git(runner, directory, "add", ".gitignore", "tracked.txt");
    String indexBefore = git(runner, directory, "diff", "--cached", "--name-only");

    var store = new GitCheckpointStore(directory, runner);
    var id = new CheckpointId("checkpoint-1");
    assertThat(store.inGitRepo()).isTrue();
    assertThat(store.create(id)).isTrue();
    assertThat(store.exists(id)).isTrue();
    assertThat(store.summary(id)).isEqualTo(new GitCheckpointStore.Diff(true, 0, 0, 0));
    assertThat(git(runner, directory, "diff", "--cached", "--name-only"))
        .isEqualTo(indexBefore);

    Files.writeString(directory.resolve("tracked.txt"), "after\n");
    Files.delete(directory.resolve("existing.txt"));
    Files.writeString(directory.resolve("new.txt"), "new\n");
    Files.writeString(directory.resolve("ignored.txt"), "new ignored\n");
    GitCheckpointStore.Diff diff = store.summary(id);
    assertThat(diff.valid()).isTrue();
    assertThat(diff.filesChanged()).isEqualTo(3);
    assertThat(diff.insertions()).isEqualTo(2);
    assertThat(diff.deletions()).isEqualTo(2);

    assertThat(store.restore(id)).isEqualTo(new GitCheckpointStore.Restore(true, ""));
    assertThat(Files.readString(directory.resolve("tracked.txt"))).isEqualTo("before\n");
    assertThat(Files.readString(directory.resolve("existing.txt"))).isEqualTo("keep\n");
    assertThat(directory.resolve("new.txt")).doesNotExist();
    assertThat(Files.readString(directory.resolve("ignored.txt"))).isEqualTo("new ignored\n");
  }

  @Test void safelyRejectsNonReposBadIdsAndMissingRefs(@TempDir Path directory) {
    var outside = new GitCheckpointStore(directory, new ProcessRunner());
    assertThat(outside.inGitRepo()).isFalse();
    assertThat(outside.create(new CheckpointId("x"))).isFalse();
    assertThat(outside.summary(new CheckpointId("x"))).isEqualTo(GitCheckpointStore.Diff.invalid());
    assertThat(outside.restore(new CheckpointId("x")).error()).contains("not inside");
  }

  @Test void reportsMissingAndRejectsUnsafeRefNames(@TempDir Path directory) {
    var runner = new ProcessRunner();
    git(runner, directory, "init", "-q");
    git(runner, directory, "config", "core.autocrlf", "false");
    var store = new GitCheckpointStore(directory, runner);
    assertThat(store.create(null)).isFalse();
    assertThat(store.create(new CheckpointId("../escape"))).isFalse();
    assertThat(store.create(new CheckpointId(""))).isFalse();
    assertThat(store.exists(new CheckpointId("../escape"))).isFalse();
    assertThat(store.summary(new CheckpointId("../escape")))
        .isEqualTo(GitCheckpointStore.Diff.invalid());
    assertThat(store.restore(new CheckpointId("" )).error()).contains("empty checkpoint id");
    assertThat(store.exists(new CheckpointId("missing"))).isFalse();
    assertThat(store.restore(new CheckpointId("missing")).error()).contains("no longer exists");
  }

  @Test void binaryDiffCountsAFileWithoutInventingLineDeltas(@TempDir Path directory)
      throws Exception {
    var runner = new ProcessRunner();
    git(runner, directory, "init", "-q");
    git(runner, directory, "config", "core.autocrlf", "false");
    Files.write(directory.resolve("binary.bin"), new byte[] {0, 1, 2});
    var store = new GitCheckpointStore(directory, runner);
    var id = new CheckpointId("binary");
    assertThat(store.create(id)).isTrue();
    Files.write(directory.resolve("binary.bin"), new byte[] {0, 9, 2});
    assertThat(store.summary(id)).isEqualTo(new GitCheckpointStore.Diff(true, 1, 0, 0));
  }

  private static String git(ProcessRunner runner, Path directory, String... tail) {
    var arguments = new java.util.ArrayList<String>();
    arguments.add("git"); arguments.add("-C"); arguments.add(directory.toString());
    arguments.addAll(List.of(tail));
    ProcessRunner.Result result = runner.argv(arguments, directory, 1_000_000,
        Duration.ofSeconds(20));
    assertThat(result.started()).isTrue();
    assertThat(result.timedOut()).isFalse();
    assertThat(result.exitCode()).as(result.output()).isZero();
    return result.output();
  }
}
