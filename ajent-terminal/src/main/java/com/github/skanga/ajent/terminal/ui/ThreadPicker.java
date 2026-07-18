package com.github.skanga.ajent.terminal.ui;

import com.github.skanga.ajent.domain.ThreadId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure saved-thread picker reducer in newest-first persistence order. */
public final class ThreadPicker {
  private static final int PAGE_ROWS = 14;

  private ThreadPicker() {}

  public record Entry(ThreadId id, String title, Instant updatedAt) {
    public Entry {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(title, "title");
      Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public String displayTitle() { return title.isEmpty() ? "(untitled)" : title; }
  }

  public enum Jump { HOME, END, PAGE_UP, PAGE_DOWN }

  public record Selection(PickerState.OneAxis state, Optional<Entry> entry) {
    public Selection {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(entry, "entry");
    }
  }

  public static PickerState.OneAxis open(List<Entry> entries, ThreadId current) {
    Objects.requireNonNull(entries, "entries");
    Objects.requireNonNull(current, "current");
    int index = 0;
    for (int candidate = 0; candidate < entries.size(); candidate++) {
      if (entries.get(candidate).id().equals(current)) index = candidate;
    }
    return new PickerState.OpenAt(index, "");
  }

  public static PickerState.OneAxis close(PickerState.OneAxis ignored) {
    return new PickerState.Closed();
  }

  public static PickerState.OneAxis move(
      PickerState.OneAxis state, List<Entry> entries, int delta) {
    if (!(state instanceof PickerState.OpenAt open) || entries.isEmpty()) return state;
    return new PickerState.OpenAt(Math.floorMod(open.index() + delta, entries.size()), "");
  }

  public static PickerState.OneAxis jump(
      PickerState.OneAxis state, List<Entry> entries, Jump where) {
    Objects.requireNonNull(where, "where");
    if (!(state instanceof PickerState.OpenAt open) || entries.isEmpty()) return state;
    int index = switch (where) {
      case HOME -> 0;
      case END -> entries.size() - 1;
      case PAGE_UP -> Math.max(0, open.index() - PAGE_ROWS);
      case PAGE_DOWN -> Math.min(entries.size() - 1, open.index() + PAGE_ROWS);
    };
    return new PickerState.OpenAt(index, "");
  }

  public static Selection select(PickerState.OneAxis state, List<Entry> entries) {
    Optional<Entry> selected = Optional.empty();
    if (state instanceof PickerState.OpenAt open
        && open.index() >= 0 && open.index() < entries.size()) {
      selected = Optional.of(entries.get(open.index()));
    }
    return new Selection(new PickerState.Closed(), selected);
  }
}
