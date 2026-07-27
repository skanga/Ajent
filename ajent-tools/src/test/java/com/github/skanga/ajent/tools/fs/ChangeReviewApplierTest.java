package com.github.skanga.ajent.tools.fs;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.tools.runtime.DiffHunk;
import com.github.skanga.ajent.tools.runtime.FileChange;
import com.github.skanga.ajent.tools.runtime.UnifiedDiff;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangeReviewApplierTest {
  @Test
  void rejectionDeletesNewEmptyFileButKeepsPreExistingEmptyFile(@TempDir Path directory)
      throws Exception {
    Path created = Files.writeString(directory.resolve("created.txt"), "generated");
    Path existing = Files.writeString(directory.resolve("existing.txt"), "generated");
    FileChange createdChange = rejected(UnifiedDiff.compute(
        created.toString(), "", "generated", false));
    FileChange existingChange = rejected(UnifiedDiff.compute(
        existing.toString(), "", "generated", true));

    ChangeReviewApplier.Result result = new ChangeReviewApplier()
        .apply(directory, List.of(createdChange, existingChange));

    assertThat(result).isInstanceOf(ChangeReviewApplier.Result.Applied.class);
    assertThat(created).doesNotExist();
    assertThat(existing).exists();
    assertThat(Files.readString(existing)).isEmpty();
  }

  @Test
  void failureOnSecondCommitRestoresFirstFileAndAllowsRetry(@TempDir Path directory)
      throws Exception {
    Path first = Files.writeString(directory.resolve("first.txt"), "after-1");
    Path second = Files.writeString(directory.resolve("second.txt"), "after-2");
    List<FileChange> changes = List.of(
        rejected(UnifiedDiff.compute(first.toString(), "before-1", "after-1", true)),
        rejected(UnifiedDiff.compute(second.toString(), "before-2", "after-2", true)));
    var failing = new ChangeReviewApplier((index, ignored) -> {
      if (index == 1) throw new IOException("injected failure");
    });

    ChangeReviewApplier.Result failed = failing.apply(directory, changes);

    assertThat(failed).isInstanceOf(ChangeReviewApplier.Result.Failed.class);
    assertThat(Files.readString(first)).isEqualTo("after-1");
    assertThat(Files.readString(second)).isEqualTo("after-2");
    assertThat(new ChangeReviewApplier().apply(directory, changes))
        .isInstanceOf(ChangeReviewApplier.Result.Applied.class);
    assertThat(Files.readString(first)).isEqualTo("before-1");
    assertThat(Files.readString(second)).isEqualTo("before-2");
  }

  @Test
  void concurrentEditBeforeSecondCommitRollsBackFirstAndPreservesExternalEdit(
      @TempDir Path directory) throws Exception {
    Path first = Files.writeString(directory.resolve("first.txt"), "after-1");
    Path second = Files.writeString(directory.resolve("second.txt"), "after-2");
    List<FileChange> changes = List.of(
        rejected(UnifiedDiff.compute(first.toString(), "before-1", "after-1", true)),
        rejected(UnifiedDiff.compute(second.toString(), "before-2", "after-2", true)));
    var concurrent = new ChangeReviewApplier((index, target) -> {
      if (index == 1) Files.writeString(target, "external");
    });

    ChangeReviewApplier.Result result = concurrent.apply(directory, changes);

    assertThat(result).isInstanceOf(ChangeReviewApplier.Result.Failed.class);
    assertThat(Files.readString(first)).isEqualTo("after-1");
    assertThat(Files.readString(second)).isEqualTo("external");
  }

  @Test
  void replacementPreservesExecutablePermissionsWhenSupported(@TempDir Path directory)
      throws Exception {
    FileStore store = Files.getFileStore(directory);
    if (!store.supportsFileAttributeView("posix")) return;
    Path script = Files.writeString(directory.resolve("script.sh"), "after",
        StandardCharsets.UTF_8);
    var permissions = EnumSet.of(PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    Files.setPosixFilePermissions(script, permissions);

    ChangeReviewApplier.Result result = new ChangeReviewApplier().apply(directory, List.of(
        rejected(UnifiedDiff.compute(script.toString(), "before", "after", true))));

    assertThat(result).isInstanceOf(ChangeReviewApplier.Result.Applied.class);
    assertThat(Files.getPosixFilePermissions(script)).isEqualTo(permissions);
  }

  @Test
  void rejectsDuplicatesOutsidePathsAndConcurrentContent(@TempDir Path directory)
      throws Exception {
    Path file = Files.writeString(directory.resolve("file.txt"), "external");
    FileChange stale = rejected(UnifiedDiff.compute(
        file.toString(), "before", "after", true));
    assertThat(new ChangeReviewApplier().apply(directory, List.of(stale)))
        .isInstanceOfSatisfying(ChangeReviewApplier.Result.Failed.class,
            failure -> assertThat(failure.message()).contains("changed since Ajent"));

    FileChange current = UnifiedDiff.compute(file.toString(), "before", "external", true);
    assertThat(new ChangeReviewApplier().apply(directory, List.of(current, current)))
        .isInstanceOfSatisfying(ChangeReviewApplier.Result.Failed.class,
            failure -> assertThat(failure.message()).contains("same file"));

    Path outside = directory.getParent().resolve("outside.txt");
    assertThat(new ChangeReviewApplier().apply(directory, List.of(
        UnifiedDiff.compute(outside.toString(), "", "created", false))))
        .isInstanceOfSatisfying(ChangeReviewApplier.Result.Failed.class,
            failure -> assertThat(failure.message()).contains("outside the workspace"));
  }

  private static FileChange rejected(FileChange change) {
    return change.withHunks(change.hunks().stream()
        .map(hunk -> hunk.withStatus(DiffHunk.Status.REJECTED)).toList());
  }
}
