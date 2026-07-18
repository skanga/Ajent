package com.github.skanga.ajent.terminal.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure two-axis diff-review reducer. */
public final class DiffReview {
  private DiffReview() {}

  public enum Status { PENDING, ACCEPTED, REJECTED }

  public record Hunk(
      int oldStart, int oldLength, int newStart, int newLength, String patch, Status status) {
    public Hunk(String patch, Status status) { this(1, 0, 1, 0, patch, status); }

    public Hunk {
      if (oldStart < 1 || oldLength < 0 || newStart < 1 || newLength < 0) {
        throw new IllegalArgumentException("invalid diff hunk coordinates");
      }
      Objects.requireNonNull(patch, "patch");
      Objects.requireNonNull(status, "status");
    }

    public Hunk withStatus(Status value) {
      return new Hunk(oldStart, oldLength, newStart, newLength, patch, value);
    }

    public String header() {
      return "@@ -" + oldStart + "," + oldLength + " +" + newStart + "," + newLength + " @@";
    }
  }

  public record File(String path, int added, int removed, List<Hunk> hunks) {
    public File(String path, List<Hunk> hunks) { this(path, 0, 0, hunks); }

    public File {
      Objects.requireNonNull(path, "path");
      if (added < 0 || removed < 0) throw new IllegalArgumentException("negative diff count");
      hunks = List.copyOf(hunks);
    }

    public File withHunks(List<Hunk> value) { return new File(path, added, removed, value); }
  }

  public record Result(PickerState.TwoAxis state, List<File> files, String status) {
    public Result {
      Objects.requireNonNull(state, "state");
      files = List.copyOf(files);
      Objects.requireNonNull(status, "status");
    }
  }

  public static Result open(List<File> files) {
    Objects.requireNonNull(files, "files");
    return files.isEmpty()
        ? new Result(new PickerState.CellClosed(), files, "no pending changes to review")
        : new Result(new PickerState.OpenAtCell(0, 0), files, "");
  }

  public static PickerState.TwoAxis close(PickerState.TwoAxis ignored) {
    return new PickerState.CellClosed();
  }

  public static PickerState.TwoAxis move(
      PickerState.TwoAxis state, List<File> files, int delta) {
    if (!(state instanceof PickerState.OpenAtCell cell) || files.isEmpty()) return state;
    List<Hunk> hunks = files.get(cell.fileIndex()).hunks();
    if (hunks.isEmpty()) return state;
    return new PickerState.OpenAtCell(cell.fileIndex(),
        Math.floorMod(cell.hunkIndex() + delta, hunks.size()));
  }

  public static PickerState.TwoAxis nextFile(PickerState.TwoAxis state, List<File> files) {
    if (!(state instanceof PickerState.OpenAtCell cell) || files.isEmpty()) return state;
    return new PickerState.OpenAtCell((cell.fileIndex() + 1) % files.size(), 0);
  }

  public static PickerState.TwoAxis previousFile(
      PickerState.TwoAxis state, List<File> files) {
    if (!(state instanceof PickerState.OpenAtCell cell) || files.isEmpty()) return state;
    return new PickerState.OpenAtCell(Math.floorMod(cell.fileIndex() - 1, files.size()), 0);
  }

  public static Result acceptHunk(PickerState.TwoAxis state, List<File> files) {
    return setCurrent(state, files, Status.ACCEPTED);
  }

  public static Result rejectHunk(PickerState.TwoAxis state, List<File> files) {
    return setCurrent(state, files, Status.REJECTED);
  }

  public static Result acceptAll(PickerState.TwoAxis state, List<File> files) {
    if (files.isEmpty()) return new Result(state, files, "no pending changes to accept");
    Changed changed = setAll(files, Status.ACCEPTED);
    return new Result(state, changed.files(), countStatus("accepted", changed.count()));
  }

  public static Result rejectAll(PickerState.TwoAxis state, List<File> files) {
    if (files.isEmpty()) return new Result(state, files, "no pending changes to reject");
    Changed changed = setAll(files, Status.REJECTED);
    return new Result(new PickerState.CellClosed(), List.of(),
        countStatus("rejected", changed.count()));
  }

  private static Result setCurrent(
      PickerState.TwoAxis state, List<File> files, Status status) {
    if (!(state instanceof PickerState.OpenAtCell cell) || files.isEmpty()) {
      return new Result(state, files, "");
    }
    File file = files.get(cell.fileIndex());
    if (file.hunks().isEmpty()) return new Result(state, files, "");
    var hunks = new ArrayList<>(file.hunks());
    hunks.set(cell.hunkIndex(), hunks.get(cell.hunkIndex()).withStatus(status));
    var changed = new ArrayList<>(files);
    changed.set(cell.fileIndex(), file.withHunks(hunks));
    return new Result(state, changed, "");
  }

  private static Changed setAll(List<File> files, Status status) {
    var changed = new ArrayList<File>(files.size());
    int count = 0;
    for (File file : files) {
      var hunks = new ArrayList<Hunk>(file.hunks().size());
      for (Hunk hunk : file.hunks()) {
        hunks.add(hunk.withStatus(status));
        count++;
      }
      changed.add(file.withHunks(hunks));
    }
    return new Changed(List.copyOf(changed), count);
  }

  private static String countStatus(String verb, int count) {
    return verb + " " + count + (count == 1 ? " hunk" : " hunks");
  }

  private record Changed(List<File> files, int count) {}
}
