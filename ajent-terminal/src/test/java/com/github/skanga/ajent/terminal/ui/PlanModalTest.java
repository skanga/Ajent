package com.github.skanga.ajent.terminal.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

final class PlanModalTest {
  @Test void mapsNativeToolStatusesAndCountsCompletion() {
    List<PlanModal.Item> items = List.of(
        PlanModal.Item.fromTool("queued", "pending"),
        PlanModal.Item.fromTool("working", "in_progress"),
        PlanModal.Item.fromTool("shipped", "completed"),
        PlanModal.Item.fromTool("unknown", "future"));
    assertThat(items).extracting(PlanModal.Item::status).containsExactly(
        PlanModal.Status.PENDING, PlanModal.Status.IN_PROGRESS,
        PlanModal.Status.COMPLETED, PlanModal.Status.PENDING);
    assertThat(PlanModal.progress(items)).isEqualTo(new PlanModal.Progress(1, 4));
  }

  @Test void modalIsReadOnlyOpenOrClosedAndEmptyProgressIsStable() {
    assertThat(PlanModal.open()).isEqualTo(new PickerState.OpenModal());
    assertThat(PlanModal.close(PlanModal.open())).isEqualTo(new PickerState.ModalClosed());
    assertThat(PlanModal.progress(List.of())).isEqualTo(new PlanModal.Progress(0, 0));
  }
}
