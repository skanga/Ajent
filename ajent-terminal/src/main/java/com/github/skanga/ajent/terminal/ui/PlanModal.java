package com.github.skanga.ajent.terminal.ui;

import java.util.List;
import java.util.Objects;

/** Pure read-only plan modal fed by the session's todo tool. */
public final class PlanModal {
  private PlanModal() {}

  public enum Status { PENDING, IN_PROGRESS, COMPLETED }

  public record Item(String content, Status status) {
    public Item {
      Objects.requireNonNull(content, "content");
      Objects.requireNonNull(status, "status");
    }

    public static Item fromTool(String content, String status) {
      return new Item(content, switch (status) {
        case "completed" -> Status.COMPLETED;
        case "in_progress" -> Status.IN_PROGRESS;
        default -> Status.PENDING;
      });
    }
  }

  public record Progress(int completed, int total) {}

  public static PickerState.Modal open() { return new PickerState.OpenModal(); }

  public static PickerState.Modal close(PickerState.Modal ignored) {
    return new PickerState.ModalClosed();
  }

  public static Progress progress(List<Item> items) {
    Objects.requireNonNull(items, "items");
    return new Progress((int) items.stream()
        .filter(item -> item.status() == Status.COMPLETED).count(), items.size());
  }
}
