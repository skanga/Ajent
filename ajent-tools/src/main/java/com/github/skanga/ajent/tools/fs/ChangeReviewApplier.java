package com.github.skanga.ajent.tools.fs;

import com.github.skanga.ajent.tools.runtime.FileChange;
import com.github.skanga.ajent.tools.runtime.UnifiedDiff;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Applies a completed diff review as an all-or-nothing filesystem transaction. */
public final class ChangeReviewApplier {
  @FunctionalInterface
  interface CommitHook {
    void beforeCommit(int index, Path target) throws IOException;
  }

  public sealed interface Result {
    record Applied(int files) implements Result {}
    record Failed(String message, List<Path> rollbackFailures) implements Result {
      public Failed {
        Objects.requireNonNull(message, "message");
        rollbackFailures = List.copyOf(rollbackFailures);
      }
    }
  }

  private final CommitHook commitHook;

  public ChangeReviewApplier() {
    this((ignored, target) -> {});
  }

  ChangeReviewApplier(CommitHook commitHook) {
    this.commitHook = Objects.requireNonNull(commitHook, "commitHook");
  }

  public Result apply(Path workspace, List<FileChange> changes) {
    Objects.requireNonNull(workspace, "workspace");
    Objects.requireNonNull(changes, "changes");
    List<PreparedChange> prepared = new ArrayList<>(changes.size());
    try {
      prepare(workspace, changes, prepared);
    } catch (InvalidPathException exception) {
      cleanup(prepared);
      return failure("invalid changed path: " + exception.getInput());
    } catch (IOException exception) {
      cleanup(prepared);
      return failure(message(exception));
    }

    var committed = new ArrayList<PreparedChange>();
    try {
      for (int index = 0; index < prepared.size(); index++) {
        PreparedChange change = prepared.get(index);
        commitHook.beforeCommit(index, change.target());
        verifyCurrent(change);
        if (change.delete()) {
          Files.delete(change.target());
        } else if (change.staged() != null) {
          AtomicFileWriter.replace(change.staged(), change.target());
        }
        committed.add(change);
      }
      cleanup(prepared);
      return new Result.Applied(changes.size());
    } catch (IOException | RuntimeException exception) {
      List<Path> rollbackFailures = rollback(committed);
      cleanup(prepared);
      return new Result.Failed("could not apply change review: " + message(exception),
          rollbackFailures);
    }
  }

  private static void prepare(
      Path workspace, List<FileChange> changes, List<PreparedChange> prepared) throws IOException {
    Path root = workspace.toAbsolutePath().normalize();
    Path realRoot = root.toRealPath();
    var targets = new HashSet<Path>();
    for (FileChange change : changes) {
      Path raw = Path.of(change.path());
      Path target = (raw.isAbsolute() ? raw : root.resolve(raw)).toAbsolutePath().normalize();
      if (!target.startsWith(root) || !realPathWithin(target, realRoot)) {
        throw new IOException("refused change outside the workspace: " + change.path());
      }
      if (!targets.add(target)) {
        throw new IOException("cannot resolve multiple pending changes for the same file: "
            + change.path());
      }
      if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("changed path is no longer a regular file: " + change.path());
      }
      String current = Files.readString(target, StandardCharsets.UTF_8);
      if (!current.equals(change.after())) {
        throw new IOException("file changed since Ajent edited it: " + change.path());
      }

      String content = UnifiedDiff.applyAccepted(change);
      boolean delete = !change.existedBefore() && content.isEmpty();
      Path parent = Objects.requireNonNull(target.getParent(), "target parent");
      Path backup = Files.createTempFile(parent,
          "." + target.getFileName(), ".ajent-backup");
      Path staged = null;
      try {
        Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
        if (!delete && !current.equals(content)) {
          staged = AtomicFileWriter.stage(target, content.getBytes(StandardCharsets.UTF_8));
        }
        prepared.add(new PreparedChange(target, current, delete, backup, staged));
      } catch (IOException | RuntimeException exception) {
        Files.deleteIfExists(backup);
        if (staged != null) Files.deleteIfExists(staged);
        throw exception;
      }
    }
  }

  private static void verifyCurrent(PreparedChange change) throws IOException {
    if (!Files.isRegularFile(change.target(), LinkOption.NOFOLLOW_LINKS)
        || !Files.readString(change.target(), StandardCharsets.UTF_8)
            .equals(change.expected())) {
      throw new IOException("file changed since Ajent edited it: " + change.target());
    }
  }

  private static List<Path> rollback(List<PreparedChange> committed) {
    var failures = new ArrayList<Path>();
    for (int index = committed.size() - 1; index >= 0; index--) {
      PreparedChange change = committed.get(index);
      try {
        Path parent = Objects.requireNonNull(change.target().getParent(), "target parent");
        Path restore = Files.createTempFile(parent,
            "." + change.target().getFileName(), ".ajent-restore");
        try {
          Files.copy(change.backup(), restore, StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.COPY_ATTRIBUTES);
          AtomicFileWriter.replace(restore, change.target());
        } finally {
          Files.deleteIfExists(restore);
        }
      } catch (IOException | RuntimeException exception) {
        failures.add(change.target());
      }
    }
    return List.copyOf(failures);
  }

  private static boolean realPathWithin(Path target, Path realRoot) throws IOException {
    Path probe = target;
    while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
      probe = probe.getParent();
    }
    return probe != null && probe.toRealPath().startsWith(realRoot);
  }

  private static Result.Failed failure(String message) {
    return new Result.Failed(message, List.of());
  }

  private static String message(Throwable throwable) {
    return throwable.getMessage() == null
        ? throwable.getClass().getSimpleName() : throwable.getMessage();
  }

  private static void cleanup(List<PreparedChange> changes) {
    for (PreparedChange change : changes) {
      try {
        Files.deleteIfExists(change.backup());
        if (change.staged() != null) Files.deleteIfExists(change.staged());
      } catch (IOException ignored) {
        // Temporary cleanup is best-effort after the target transaction has completed.
      }
    }
  }

  private record PreparedChange(
      Path target, String expected, boolean delete, Path backup, Path staged) {}
}
