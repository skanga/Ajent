package com.github.skanga.ajent.tools.workspace;

import com.github.skanga.ajent.domain.CheckpointId;
import com.github.skanga.ajent.tools.process.ProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Parentless-commit worktree snapshots compatible with AgenTTY's checkpoint refs. */
public final class GitCheckpointStore {
  private static final String REF_PREFIX = "refs/ajent/checkpoints/";
  private static final int KEEP = 64;
  private static final Duration TIMEOUT = Duration.ofSeconds(120);
  private final Path workingDirectory;
  private final ProcessRunner runner;
  private final Repo repo;

  public record Diff(boolean valid, int filesChanged, int insertions, int deletions) {
    public static Diff invalid() { return new Diff(false, 0, 0, 0); }
  }

  public record Restore(boolean restored, String error) {
    public Restore { error = Objects.requireNonNull(error, "error"); }
  }

  private record Repo(boolean valid, Path root, Path gitDirectory) {}

  public GitCheckpointStore(Path workingDirectory, ProcessRunner runner) {
    this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
        .toAbsolutePath().normalize();
    this.runner = Objects.requireNonNull(runner, "runner");
    this.repo = discover();
  }

  public boolean inGitRepo() { return repo.valid(); }

  public synchronized boolean create(CheckpointId id) {
    if (!valid(id)) return false;
    Path scratch = prepareScratch();
    try {
      if (!ok(scratch(List.of("add", "-A", "--ignore-errors"), scratch, 512 * 1024))) return false;
      ProcessRunner.Result tree = scratch(List.of("write-tree"), scratch, 512 * 1024);
      if (!ok(tree)) return false;
      ProcessRunner.Result commit = git(List.of("-c", "user.name=ajent", "-c",
          "user.email=checkpoint@ajent", "commit-tree", chomp(tree.output()), "-m",
          "ajent checkpoint " + id.value()), 512 * 1024);
      if (!ok(commit) || !ok(git(List.of("update-ref", ref(id), chomp(commit.output())),
          512 * 1024))) return false;
      prune();
      return true;
    } finally {
      deleteQuietly(scratch);
    }
  }

  public boolean exists(CheckpointId id) {
    return valid(id) && ok(git(List.of("rev-parse", "--verify", "--quiet",
        ref(id) + "^{commit}"), 8192));
  }

  public synchronized Diff summary(CheckpointId id) {
    if (!valid(id)) return Diff.invalid();
    ProcessRunner.Result revision = git(List.of("rev-parse", "--verify", "--quiet",
        ref(id) + "^{commit}"), 8192);
    if (!ok(revision)) return Diff.invalid();
    Path scratch = prepareScratch();
    String currentTree;
    try {
      if (!ok(scratch(List.of("add", "-A", "--ignore-errors"), scratch, 512 * 1024))) {
        return Diff.invalid();
      }
      ProcessRunner.Result tree = scratch(List.of("write-tree"), scratch, 512 * 1024);
      if (!ok(tree)) return Diff.invalid();
      currentTree = chomp(tree.output());
    } finally {
      deleteQuietly(scratch);
    }
    ProcessRunner.Result stat = git(List.of("diff", "--numstat", "-M",
        chomp(revision.output()), currentTree), 16 * 1024 * 1024);
    if (!ok(stat)) return Diff.invalid();
    int files = 0, additions = 0, deletions = 0;
    for (String line : stat.output().lines().toList()) {
      if (line.isEmpty()) continue;
      files++;
      String[] columns = line.split("\t", 3);
      if (columns.length >= 2) {
        additions += integer(columns[0]);
        deletions += integer(columns[1]);
      }
    }
    return new Diff(true, files, additions, deletions);
  }

  public synchronized Restore restore(CheckpointId id) {
    if (!repo.valid()) return failure("not inside a git repository");
    if (id.value().isEmpty()) return failure("empty checkpoint id");
    ProcessRunner.Result revision = git(List.of("rev-parse", "--verify", "--quiet",
        ref(id) + "^{commit}"), 8192);
    if (!ok(revision)) return failure("checkpoint no longer exists (pruned?)");
    String commit = chomp(revision.output());
    Set<String> snapshot = nulSet(git(List.of("ls-tree", "-r", "-z", "--name-only", commit),
        16 * 1024 * 1024));
    if (snapshot == null) return failure("failed to list checkpoint contents");
    List<String> current = nulList(git(List.of("ls-files", "-z", "-c", "-o",
        "--exclude-standard"), 16 * 1024 * 1024));
    if (current == null) return failure("failed to list current files");
    Path scratch = prepareScratch();
    try {
      if (!ok(scratch(List.of("read-tree", commit), scratch, 512 * 1024))
          || !ok(scratch(List.of("checkout-index", "-a", "-f"), scratch, 512 * 1024))) {
        return failure("failed to write checkpoint files");
      }
    } finally {
      deleteQuietly(scratch);
    }
    for (String relative : current) {
      if (snapshot.contains(relative)) continue;
      Path target = repo.root().resolve(relative).normalize();
      if (target.startsWith(repo.root())) deleteQuietly(target);
    }
    return new Restore(true, "");
  }

  private Repo discover() {
    ProcessRunner.Result top = runner.argv(List.of("git", "-C", workingDirectory.toString(),
        "rev-parse", "--show-toplevel"), workingDirectory, 8192, Duration.ofSeconds(10));
    ProcessRunner.Result directory = runner.argv(List.of("git", "-C", workingDirectory.toString(),
        "rev-parse", "--absolute-git-dir"), workingDirectory, 8192, Duration.ofSeconds(10));
    if (!ok(top) || !ok(directory)) return new Repo(false, workingDirectory, workingDirectory);
    try {
      return new Repo(true, Path.of(chomp(top.output())).toAbsolutePath().normalize(),
          Path.of(chomp(directory.output())).toAbsolutePath().normalize());
    } catch (RuntimeException exception) {
      return new Repo(false, workingDirectory, workingDirectory);
    }
  }

  private ProcessRunner.Result git(List<String> tail, int cap) {
    var argv = new ArrayList<String>();
    argv.add("git"); argv.add("-C"); argv.add(repo.root().toString()); argv.addAll(tail);
    return runner.argv(argv, repo.root(), cap, TIMEOUT);
  }

  private ProcessRunner.Result scratch(List<String> tail, Path scratch, int cap) {
    var argv = new ArrayList<String>();
    argv.add("git"); argv.add("-C"); argv.add(repo.root().toString()); argv.addAll(tail);
    return runner.argv(argv, repo.root(), cap, TIMEOUT,
        Map.of("GIT_INDEX_FILE", scratch.toString()));
  }

  private Path prepareScratch() {
    Path scratch = repo.gitDirectory().resolve("ajent-checkpoint-index");
    deleteQuietly(scratch);
    try {
      Path source = repo.gitDirectory().resolve("index");
      if (Files.isRegularFile(source)) Files.copy(source, scratch, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException ignored) { }
    return scratch;
  }

  private void prune() {
    ProcessRunner.Result listed = git(List.of("for-each-ref", "--sort=creatordate",
        "--format=%(refname)", REF_PREFIX), 512 * 1024);
    if (!ok(listed)) return;
    List<String> refs = listed.output().lines().filter(line -> !line.isBlank()).toList();
    for (int index = 0; index < refs.size() - KEEP; index++) {
      git(List.of("update-ref", "-d", refs.get(index)), 8192);
    }
  }

  private boolean valid(CheckpointId id) {
    return repo.valid() && id != null && !id.value().isEmpty()
        && id.value().chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_');
  }

  private String ref(CheckpointId id) { return REF_PREFIX + id.value(); }
  private static boolean ok(ProcessRunner.Result result) {
    return result.started() && !result.timedOut() && result.exitCode() == 0;
  }
  private static String chomp(String value) { return value.replaceFirst("[\\r\\n]+$", ""); }
  private static int integer(String value) {
    try { return "-".equals(value) ? 0 : Integer.parseInt(value); }
    catch (NumberFormatException exception) { return 0; }
  }
  private static Set<String> nulSet(ProcessRunner.Result result) {
    List<String> values = nulList(result);
    return values == null ? null : new HashSet<>(values);
  }
  private static List<String> nulList(ProcessRunner.Result result) {
    if (!ok(result)) return null;
    var values = new ArrayList<String>();
    int start = 0;
    while (start < result.output().length()) {
      int end = result.output().indexOf('\0', start);
      if (end < 0) break;
      if (end > start) values.add(result.output().substring(start, end));
      start = end + 1;
    }
    return values;
  }
  private static Restore failure(String error) { return new Restore(false, error); }
  private static void deleteQuietly(Path path) {
    try { Files.deleteIfExists(path); } catch (IOException ignored) { }
  }
}
