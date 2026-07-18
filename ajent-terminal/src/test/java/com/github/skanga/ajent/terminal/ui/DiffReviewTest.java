package com.github.skanga.ajent.terminal.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

final class DiffReviewTest {
  private static final List<DiffReview.File> FILES = List.of(
      new DiffReview.File("a.java", List.of(
          new DiffReview.Hunk("a1", DiffReview.Status.PENDING),
          new DiffReview.Hunk("a2", DiffReview.Status.PENDING))),
      new DiffReview.File("b.java", List.of(
          new DiffReview.Hunk("b1", DiffReview.Status.PENDING))));

  @Test void emptyOpenAndBulkActionsReturnExactNativeStatus() {
    assertThat(DiffReview.open(List.of())).isEqualTo(new DiffReview.Result(
        new PickerState.CellClosed(), List.of(), "no pending changes to review"));
    assertThat(DiffReview.acceptAll(new PickerState.CellClosed(), List.of()).status())
        .isEqualTo("no pending changes to accept");
    assertThat(DiffReview.rejectAll(new PickerState.CellClosed(), List.of()).status())
        .isEqualTo("no pending changes to reject");
  }

  @Test void navigatesBothAxesWithWrapAndResetsHunkOnFileChange() {
    PickerState.TwoAxis state = DiffReview.open(FILES).state();
    state = DiffReview.move(state, FILES, -1);
    assertThat(state).isEqualTo(new PickerState.OpenAtCell(0, 1));
    state = DiffReview.nextFile(state, FILES);
    assertThat(state).isEqualTo(new PickerState.OpenAtCell(1, 0));
    state = DiffReview.previousFile(state, FILES);
    assertThat(state).isEqualTo(new PickerState.OpenAtCell(0, 0));
  }

  @Test void acceptsAndRejectsOnlyTheSelectedHunk() {
    var accepted = DiffReview.acceptHunk(new PickerState.OpenAtCell(0, 1), FILES);
    assertThat(accepted.files().get(0).hunks()).extracting(DiffReview.Hunk::status)
        .containsExactly(DiffReview.Status.PENDING, DiffReview.Status.ACCEPTED);
    var rejected = DiffReview.rejectHunk(new PickerState.OpenAtCell(1, 0), accepted.files());
    assertThat(rejected.files().get(1).hunks().getFirst().status())
        .isEqualTo(DiffReview.Status.REJECTED);
  }

  @Test void bulkAcceptKeepsModalAndBulkRejectClosesAndClearsChanges() {
    var accepted = DiffReview.acceptAll(new PickerState.OpenAtCell(0, 0), FILES);
    assertThat(accepted.status()).isEqualTo("accepted 3 hunks");
    assertThat(accepted.state()).isEqualTo(new PickerState.OpenAtCell(0, 0));
    assertThat(accepted.files()).allSatisfy(file -> assertThat(file.hunks())
        .allSatisfy(hunk -> assertThat(hunk.status()).isEqualTo(DiffReview.Status.ACCEPTED)));

    var rejected = DiffReview.rejectAll(accepted.state(), accepted.files());
    assertThat(rejected.status()).isEqualTo("rejected 3 hunks");
    assertThat(rejected.state()).isEqualTo(new PickerState.CellClosed());
    assertThat(rejected.files()).isEmpty();
  }

  @Test void singularStatusUsesHunkNotHunks() {
    var one = List.of(FILES.get(1));
    assertThat(DiffReview.acceptAll(new PickerState.OpenAtCell(0, 0), one).status())
        .isEqualTo("accepted 1 hunk");
  }
}
