package com.github.skanga.ajent.tools.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure port of AgenTTY's bounded LCS unified-diff and hunk reconstruction engine. */
public final class UnifiedDiff {
  private static final long CELL_CAP = 6_000_000;
  private static final int CONTEXT = 3;

  private UnifiedDiff() {}

  private enum Kind { KEEP, DELETE, INSERT }
  private record Edit(Kind kind, int oldIndex, int newIndex) {}
  private record Computed(List<DiffHunk> hunks, int added, int removed) {}

  public static FileChange compute(String path, String before, String after) {
    Computed computed = compute(before, after);
    return new FileChange(path, computed.added(), computed.removed(), before, after,
        computed.hunks());
  }

  static List<DiffHunk> hunks(String before, String after) {
    return compute(before, after).hunks();
  }

  public static String render(FileChange change) {
    var result = new StringBuilder()
        .append("--- a/").append(change.path()).append('\n')
        .append("+++ b/").append(change.path()).append('\n');
    for (DiffHunk hunk : change.hunks()) {
      result.append("@@ -").append(hunk.oldStart()).append(',').append(hunk.oldLength())
          .append(" +").append(hunk.newStart()).append(',').append(hunk.newLength())
          .append(" @@\n").append(hunk.patch());
    }
    return result.toString();
  }

  public static String applyAccepted(FileChange change) {
    boolean allAccepted = !change.hunks().isEmpty()
        && change.hunks().stream().allMatch(hunk -> hunk.status() == DiffHunk.Status.ACCEPTED);
    if (allAccepted) return change.after();
    boolean noneAccepted = change.hunks().stream()
        .noneMatch(hunk -> hunk.status() == DiffHunk.Status.ACCEPTED);
    if (noneAccepted) return change.before();

    List<String> original = splitLines(change.before());
    var output = new ArrayList<String>();
    int cursor = 0;
    for (DiffHunk hunk : change.hunks()) {
      int start = Math.max(0, hunk.oldStart() - 1);
      while (cursor < start && cursor < original.size()) output.add(original.get(cursor++));
      if (hunk.status() == DiffHunk.Status.ACCEPTED) {
        for (String line : splitPatch(hunk.patch())) {
          if (line.isEmpty()) continue;
          char tag = line.charAt(0);
          if (tag == ' ' || tag == '+') output.add(line.substring(1));
        }
        cursor = start + hunk.oldLength();
      } else {
        for (int count = 0; count < hunk.oldLength() && cursor < original.size(); count++) {
          output.add(original.get(cursor++));
        }
      }
    }
    while (cursor < original.size()) output.add(original.get(cursor++));
    return String.join("\n", output);
  }

  private static Computed compute(String before, String after) {
    List<String> oldLines = splitLines(before);
    List<String> newLines = splitLines(after);
    List<Edit> edits = edits(oldLines, newLines);
    boolean[] changed = new boolean[edits.size()];
    for (int index = 0; index < edits.size(); index++) {
      changed[index] = edits.get(index).kind() != Kind.KEEP;
    }

    var hunks = new ArrayList<DiffHunk>();
    int added = 0;
    int removed = 0;
    int cursor = 0;
    while (cursor < edits.size()) {
      while (cursor < edits.size() && !changed[cursor]) cursor++;
      if (cursor >= edits.size()) break;
      int start = Math.max(0, cursor - CONTEXT);
      int end = cursor;
      while (end < edits.size()) {
        int lastChange = end;
        int probe = end;
        int gap = 0;
        while (probe < edits.size() && gap <= 2 * CONTEXT) {
          if (changed[probe]) {
            lastChange = probe;
            gap = 0;
          } else {
            gap++;
          }
          probe++;
        }
        if (lastChange == end) break;
        end = lastChange;
      }
      end = Math.min(edits.size() - 1, end + CONTEXT);

      int oldStart = -1;
      int newStart = -1;
      int oldLength = 0;
      int newLength = 0;
      var patch = new StringBuilder();
      var deletions = new StringBuilder();
      var insertions = new StringBuilder();
      for (int index = start; index <= end; index++) {
        Edit edit = edits.get(index);
        switch (edit.kind()) {
          case KEEP -> {
            flush(patch, deletions, insertions);
            if (oldStart < 0) oldStart = edit.oldIndex() + 1;
            if (newStart < 0) newStart = edit.newIndex() + 1;
            oldLength++;
            newLength++;
            patch.append(' ').append(oldLines.get(edit.oldIndex())).append('\n');
          }
          case DELETE -> {
            if (oldStart < 0) oldStart = edit.oldIndex() + 1;
            oldLength++;
            deletions.append('-').append(oldLines.get(edit.oldIndex())).append('\n');
            removed++;
          }
          case INSERT -> {
            if (newStart < 0) newStart = edit.newIndex() + 1;
            newLength++;
            insertions.append('+').append(newLines.get(edit.newIndex())).append('\n');
            added++;
          }
        }
      }
      flush(patch, deletions, insertions);
      hunks.add(new DiffHunk(Math.max(1, oldStart), oldLength,
          Math.max(1, newStart), newLength, patch.toString()));
      cursor = end + 1;
    }
    return new Computed(List.copyOf(hunks), added, removed);
  }

  private static List<Edit> edits(List<String> oldLines, List<String> newLines) {
    int oldSize = oldLines.size();
    int newSize = newLines.size();
    Map<String, Integer> ids = new HashMap<>((oldSize + newSize) * 2);
    int[] oldIds = intern(oldLines, ids);
    int[] newIds = intern(newLines, ids);
    var result = new ArrayList<Edit>(oldSize + newSize);

    int prefix = 0;
    while (prefix < oldSize && prefix < newSize && oldIds[prefix] == newIds[prefix]) {
      result.add(new Edit(Kind.KEEP, prefix, prefix));
      prefix++;
    }
    int oldSuffix = oldSize;
    int newSuffix = newSize;
    while (oldSuffix > prefix && newSuffix > prefix
        && oldIds[oldSuffix - 1] == newIds[newSuffix - 1]) {
      oldSuffix--;
      newSuffix--;
    }
    middle(oldIds, newIds, prefix, oldSuffix, prefix, newSuffix, result);
    for (int offset = 0; oldSuffix + offset < oldSize; offset++) {
      result.add(new Edit(Kind.KEEP, oldSuffix + offset, newSuffix + offset));
    }
    return result;
  }

  private static int[] intern(List<String> lines, Map<String, Integer> ids) {
    int[] result = new int[lines.size()];
    for (int index = 0; index < lines.size(); index++) {
      result[index] = ids.computeIfAbsent(lines.get(index), ignored -> ids.size());
    }
    return result;
  }

  private static void middle(int[] oldIds, int[] newIds,
      int oldStart, int oldEnd, int newStart, int newEnd, List<Edit> output) {
    int oldLength = oldEnd - oldStart;
    int newLength = newEnd - newStart;
    if (oldLength == 0) {
      for (int index = newStart; index < newEnd; index++) {
        output.add(new Edit(Kind.INSERT, -1, index));
      }
      return;
    }
    if (newLength == 0) {
      for (int index = oldStart; index < oldEnd; index++) {
        output.add(new Edit(Kind.DELETE, index, -1));
      }
      return;
    }
    if ((long) oldLength * newLength > CELL_CAP) {
      for (int index = oldStart; index < oldEnd; index++) {
        output.add(new Edit(Kind.DELETE, index, -1));
      }
      for (int index = newStart; index < newEnd; index++) {
        output.add(new Edit(Kind.INSERT, -1, index));
      }
      return;
    }

    int stride = newLength + 1;
    int[] table = new int[(oldLength + 1) * stride];
    for (int oldIndex = 1; oldIndex <= oldLength; oldIndex++) {
      int value = oldIds[oldStart + oldIndex - 1];
      int row = oldIndex * stride;
      int prior = (oldIndex - 1) * stride;
      for (int newIndex = 1; newIndex <= newLength; newIndex++) {
        table[row + newIndex] = value == newIds[newStart + newIndex - 1]
            ? table[prior + newIndex - 1] + 1
            : Math.max(table[prior + newIndex], table[row + newIndex - 1]);
      }
    }
    var reverse = new ArrayList<Edit>();
    int oldIndex = oldLength;
    int newIndex = newLength;
    while (oldIndex > 0 && newIndex > 0) {
      if (oldIds[oldStart + oldIndex - 1] == newIds[newStart + newIndex - 1]) {
        reverse.add(new Edit(Kind.KEEP,
            oldStart + oldIndex - 1, newStart + newIndex - 1));
        oldIndex--;
        newIndex--;
      } else if (table[(oldIndex - 1) * stride + newIndex]
          >= table[oldIndex * stride + newIndex - 1]) {
        reverse.add(new Edit(Kind.DELETE, oldStart + oldIndex - 1, -1));
        oldIndex--;
      } else {
        reverse.add(new Edit(Kind.INSERT, -1, newStart + newIndex - 1));
        newIndex--;
      }
    }
    while (oldIndex > 0) {
      reverse.add(new Edit(Kind.DELETE, oldStart + --oldIndex, -1));
    }
    while (newIndex > 0) {
      reverse.add(new Edit(Kind.INSERT, -1, newStart + --newIndex));
    }
    Collections.reverse(reverse);
    output.addAll(reverse);
  }

  private static void flush(
      StringBuilder patch, StringBuilder deletions, StringBuilder insertions) {
    patch.append(deletions).append(insertions);
    deletions.setLength(0);
    insertions.setLength(0);
  }

  private static List<String> splitLines(String value) {
    var lines = new ArrayList<String>();
    int start = 0;
    for (int index = 0; index < value.length(); index++) {
      if (value.charAt(index) == '\n') {
        lines.add(value.substring(start, index));
        start = index + 1;
      }
    }
    lines.add(value.substring(start));
    return lines;
  }

  private static List<String> splitPatch(String patch) {
    return patch.lines().toList();
  }
}
