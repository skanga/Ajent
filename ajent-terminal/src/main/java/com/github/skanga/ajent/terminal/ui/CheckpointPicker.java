package com.github.skanga.ajent.terminal.ui;

import com.github.skanga.ajent.domain.CheckpointId;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable oldest-to-newest rewind picker over checkpointed user turns. */
public final class CheckpointPicker {
  private CheckpointPicker() {}

  public enum DiffState { LOADING, READY, FAILED }

  public record Entry(CheckpointId id, int turn, String preview, Instant timestamp,
      DiffState diffState, int filesChanged, int insertions, int deletions, boolean clean) {
    public Entry {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(preview, "preview");
      Objects.requireNonNull(timestamp, "timestamp");
      Objects.requireNonNull(diffState, "diffState");
    }
    public Entry ready(int files, int added, int removed) {
      return new Entry(id, turn, preview, timestamp, DiffState.READY,
          files, added, removed, files == 0);
    }
    public Entry failed() {
      return new Entry(id, turn, preview, timestamp, DiffState.FAILED,
          0, 0, 0, false);
    }
  }

  public sealed interface State permits Closed, Open {}
  public record Closed() implements State {}
  public record Open(List<Entry> entries, int index) implements State {
    public Open { entries = List.copyOf(entries); }
  }
  public record Restore(boolean restored, String prompt, String error) {
    public Restore {
      Objects.requireNonNull(prompt, "prompt");
      Objects.requireNonNull(error, "error");
    }
  }

  public static List<Entry> entries(List<Message> messages) {
    var result = new ArrayList<Entry>();
    int turn = 0;
    for (Message message : messages) {
      if (message.role() == Role.USER) turn++;
      if (message.role() != Role.USER || message.checkpointId().isEmpty()) continue;
      result.add(new Entry(message.checkpointId().orElseThrow(), turn, preview(message.text()),
          message.timestamp(), DiffState.LOADING, 0, 0, 0, false));
    }
    return List.copyOf(result);
  }

  public static State open(List<Entry> entries) {
    return entries.isEmpty() ? new Closed() : new Open(entries, entries.size() - 1);
  }
  public static State close(State ignored) { return new Closed(); }
  public static State move(State state, int delta) {
    if (!(state instanceof Open open) || open.entries().isEmpty()) return state;
    return new Open(open.entries(), Math.floorMod(open.index() + delta, open.entries().size()));
  }
  public static State diff(State state, int index, Optional<int[]> values) {
    if (!(state instanceof Open open) || index < 0 || index >= open.entries().size()) return state;
    var entries = new ArrayList<>(open.entries());
    Entry entry = entries.get(index);
    entries.set(index, values.isEmpty() ? entry.failed()
        : entry.ready(values.orElseThrow()[0], values.orElseThrow()[1], values.orElseThrow()[2]));
    return new Open(entries, open.index());
  }
  public static Optional<Entry> selected(State state) {
    if (!(state instanceof Open open) || open.index() < 0
        || open.index() >= open.entries().size()) return Optional.empty();
    return Optional.of(open.entries().get(open.index()));
  }

  static String preview(String text) {
    String first = text.lines().findFirst().orElse("").stripLeading();
    if (first.isEmpty()) return "(no prompt text)";
    if (first.getBytes(StandardCharsets.UTF_8).length <= 96) return first;
    int end = 0, bytes = 0;
    while (end < first.length()) {
      int point = first.codePointAt(end);
      int size = new String(Character.toChars(point)).getBytes(StandardCharsets.UTF_8).length;
      if (bytes + size > 96) break;
      bytes += size;
      end += Character.charCount(point);
    }
    return first.substring(0, end) + "\u2026";
  }
}
