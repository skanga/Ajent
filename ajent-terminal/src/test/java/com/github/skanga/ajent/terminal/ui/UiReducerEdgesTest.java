package com.github.skanga.ajent.terminal.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

final class UiReducerEdgesTest {
  @Test void modelPickerClosedEmptyAndInvalidEdgesAreNoOps() {
    var closed = new PickerState.Closed();
    var empty = List.<ModelPicker.Model>of();
    assertThat(ModelPicker.move(closed, empty, 1)).isSameAs(closed);
    assertThat(ModelPicker.move(new PickerState.OpenAt(), empty, 1))
        .isEqualTo(new PickerState.OpenAt());
    assertThat(ModelPicker.jump(closed, empty, ModelPicker.Jump.END)).isSameAs(closed);
    assertThat(ModelPicker.jump(new PickerState.OpenAt(), empty, ModelPicker.Jump.END))
        .isEqualTo(new PickerState.OpenAt());
    assertThat(ModelPicker.input(closed, empty, 'x')).isSameAs(closed);
    assertThat(ModelPicker.backspace(closed, empty)).isSameAs(closed);
    assertThat(ModelPicker.backspace(new PickerState.OpenAt(), empty))
        .isEqualTo(new PickerState.OpenAt());
    assertThat(ModelPicker.select(closed, empty).model()).isEmpty();
    assertThat(ModelPicker.select(new PickerState.OpenAt(4, ""), empty).model()).isEmpty();
    assertThat(ModelPicker.toggleFavorite(closed, empty).models()).isEmpty();
    assertThat(ModelPicker.toggleFavorite(new PickerState.OpenAt(3, ""), empty).models()).isEmpty();
  }

  @Test void modelPickerCoversEveryJumpAndFilterClamp() {
    var models = List.of(new ModelPicker.Model("a", "Alpha", false),
        new ModelPicker.Model("b", "Beta", false), new ModelPicker.Model("c", "Alpine", false));
    var state = new PickerState.OpenAt(2, "");
    assertThat(ModelPicker.jump(state, models, ModelPicker.Jump.END))
        .isEqualTo(new PickerState.OpenAt(2, ""));
    assertThat(ModelPicker.jump(state, models, ModelPicker.Jump.PAGE_UP))
        .isEqualTo(new PickerState.OpenAt(0, ""));
    assertThat(ModelPicker.input(new PickerState.OpenAt(2, "A"), models, 'l'))
        .isEqualTo(new PickerState.OpenAt(1, "Al"));
    assertThat(ModelPicker.filteredIndices(models, "zzz")).isEmpty();
  }

  @Test void providerPickerCoversClosedInvalidAndEveryJump() {
    var providers = List.of(new ProviderPicker.Provider("a", "A"),
        new ProviderPicker.Provider("b", "B"));
    var closed = new PickerState.Closed();
    assertThat(ProviderPicker.move(closed, providers, 1)).isSameAs(closed);
    assertThat(ProviderPicker.jump(closed, providers, ProviderPicker.Jump.HOME)).isSameAs(closed);
    assertThat(ProviderPicker.jump(new PickerState.OpenAt(2, ""), providers,
        ProviderPicker.Jump.HOME)).isEqualTo(new PickerState.OpenAt(0, ""));
    assertThat(ProviderPicker.jump(new PickerState.OpenAt(0, ""), providers,
        ProviderPicker.Jump.END)).isEqualTo(new PickerState.OpenAt(2, ""));
    assertThat(ProviderPicker.jump(new PickerState.OpenAt(2, ""), providers,
        ProviderPicker.Jump.PAGE_UP)).isEqualTo(new PickerState.OpenAt(0, ""));
    assertThat(ProviderPicker.jump(new PickerState.OpenAt(0, ""), providers,
        ProviderPicker.Jump.PAGE_DOWN)).isEqualTo(new PickerState.OpenAt(2, ""));
    assertThat(ProviderPicker.select(closed, providers).state()).isSameAs(closed);
    assertThat(ProviderPicker.select(new PickerState.OpenAt(-1, ""), providers).action()).isEmpty();
    assertThat(ProviderPicker.select(new PickerState.OpenAt(9, ""), providers).action()).isEmpty();
  }

  @Test void loginPredicatesAndChoiceGuardsCoverEveryStateShape() {
    var closed = new LoginModal.Closed();
    var picking = new LoginModal.Picking();
    var oauth = new LoginModal.OAuthCode("v", "s", URI.create("https://example.test"),
        new Utf8Editor());
    assertThat(LoginModal.isOpen(closed)).isFalse();
    assertThat(LoginModal.isOpen(picking)).isTrue();
    assertThat(LoginModal.isInputState(closed)).isFalse();
    assertThat(LoginModal.isInputState(oauth)).isTrue();
    assertThat(LoginModal.isInputState(new LoginModal.ApiKeyInput())).isTrue();
    assertThat(LoginModal.isInputState(new LoginModal.CustomHostInput())).isTrue();
    assertThat(LoginModal.pick(oauth, '1', () -> { throw new AssertionError(); }).state())
        .isSameAs(oauth);
    assertThat(LoginModal.pick(picking, 'x', () -> { throw new AssertionError(); }).state())
        .isSameAs(picking);
    assertThat(LoginModal.submit(picking).state()).isSameAs(picking);
  }

  @Test void loginCustomHostCoversHttpPathAndBareHostBranches() {
    var http = new LoginModal.CustomHostInput(new Utf8Editor().insert("http://host/v1"));
    assertThat(LoginModal.submit(http).action()).contains(new LoginModal.SwitchCustomHost("host"));
    var bare = new LoginModal.CustomHostInput(new Utf8Editor().insert("host:99"));
    assertThat(LoginModal.submit(bare).action()).contains(
        new LoginModal.SwitchCustomHost("host:99"));
  }

  @Test void utf8EditorCoversBoundariesAndRejectsInteriorByteCursor() {
    var empty = new Utf8Editor();
    assertThat(empty.left()).isSameAs(empty);
    assertThat(empty.right()).isSameAs(empty);
    assertThat(empty.backspace()).isSameAs(empty);
    var value = empty.insert("🙂");
    assertThat(value.right()).isSameAs(value);
    assertThatIllegalArgumentException().isThrownBy(() -> new Utf8Editor("🙂", 1));
    assertThatIllegalArgumentException().isThrownBy(() -> new Utf8Editor("x", -1));
  }

  @Test void diffReviewCoversClosedAndFilesWithoutHunks() {
    var closed = new PickerState.CellClosed();
    var files = List.of(new DiffReview.File("empty", List.of()));
    var open = new PickerState.OpenAtCell(0, 0);
    assertThat(DiffReview.move(closed, files, 1)).isSameAs(closed);
    assertThat(DiffReview.move(open, files, 1)).isSameAs(open);
    assertThat(DiffReview.nextFile(closed, files)).isSameAs(closed);
    assertThat(DiffReview.previousFile(closed, files)).isSameAs(closed);
    assertThat(DiffReview.acceptHunk(open, files).files()).isEqualTo(files);
    assertThat(DiffReview.rejectHunk(closed, files).files()).isEqualTo(files);
    assertThat(DiffReview.close(open)).isEqualTo(closed);
  }

  @Test void toolViewerCoversClosedInvalidAndScrollClampEdges() {
    var closed = new ToolOutputViewer.Closed();
    assertThat(ToolOutputViewer.close(closed)).isEqualTo(closed);
    assertThat(ToolOutputViewer.move(closed, 1)).isSameAs(closed);
    assertThat(ToolOutputViewer.select(closed)).isSameAs(closed);
    assertThat(ToolOutputViewer.step(closed, 1)).isSameAs(closed);
    assertThat(ToolOutputViewer.copy(closed).clipboard()).isEmpty();
    assertThat(ToolOutputViewer.withMaxScroll(closed, 2)).isSameAs(closed);
    var empty = new ToolOutputViewer.Open(List.of(), 0, false, 0, 0);
    assertThat(ToolOutputViewer.move(empty, 1)).isSameAs(empty);
    assertThat(ToolOutputViewer.select(empty)).isSameAs(empty);
    assertThat(ToolOutputViewer.step(empty, 1)).isSameAs(empty);
    assertThat(ToolOutputViewer.copy(empty).clipboard()).isEmpty();
  }
}
