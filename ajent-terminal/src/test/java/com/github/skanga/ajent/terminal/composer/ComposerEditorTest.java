package com.github.skanga.ajent.terminal.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ComposerEditorTest {
  @Test
  void ctrlWDeletesBackwardWithReferenceCursorPlacement() {
    assertThat(ComposerEditor.deleteWordBack(ComposerState.of("foo bar baz", 11)))
        .extracting(ComposerState::text, ComposerState::cursor)
        .containsExactly("foo bar ", 8);
    assertThat(ComposerEditor.deleteWordBack(ComposerState.of("foo bar baz", 8)))
        .extracting(ComposerState::text, ComposerState::cursor)
        .containsExactly("foo baz", 4);
  }

  @Test
  void ctrlWAtStartAndOnEmptyBufferIsANoOp() {
    assertThat(ComposerEditor.deleteWordBack(ComposerState.of("foo", 0)))
        .extracting(ComposerState::text, ComposerState::cursor)
        .containsExactly("foo", 0);
    assertThat(ComposerEditor.deleteWordBack(ComposerState.of("", 0)))
        .extracting(ComposerState::text, ComposerState::cursor)
        .containsExactly("", 0);
  }

  @Test
  void altDDeletesForwardAndLeavesCursorInPlace() {
    assertThat(ComposerEditor.deleteWordForward(ComposerState.of("foo bar baz", 0)))
        .extracting(ComposerState::text, ComposerState::cursor)
        .containsExactly("bar baz", 0);
    assertThat(ComposerEditor.deleteWordForward(ComposerState.of("foo bar", 7)))
        .extracting(ComposerState::text, ComposerState::cursor)
        .containsExactly("foo bar", 7);
  }

  @Test
  void ctrlWRemovesACompleteAttachmentChip() {
    var attachment = new Attachment(
        Attachment.Kind.PASTE, "x\ny\nz", "", "", "", 0, 3, 42);
    var placeholder = AttachmentPlaceholder.make(0);
    var state = new ComposerState(
        "see " + placeholder, 4 + placeholder.length(), List.of(attachment), List.of(), List.of());

    var edited = ComposerEditor.deleteWordBack(state);

    assertThat(edited.text()).doesNotContain(Character.toString(AttachmentPlaceholder.SENTINEL));
  }

  @Test
  void undoRestoresCtrlWMutation() {
    var original = ComposerState.of("foo bar baz", 11);
    var edited = ComposerEditor.deleteWordBack(original);
    assertThat(ComposerEditor.undo(edited))
        .extracting(ComposerState::text, ComposerState::cursor)
        .containsExactly("foo bar baz", 11);
  }

  @Test
  void malformedChipLikeTextFallsBackToACharacterBoundary() {
    var malformed = "x" + AttachmentPlaceholder.SENTINEL + "ATT:x" + AttachmentPlaceholder.SENTINEL;
    assertThat(AttachmentPlaceholder.lengthAt(malformed, 1)).isZero();
    assertThat(AttachmentPlaceholder.lengthEndingAt(malformed, malformed.length())).isZero();
    assertThat(ComposerEditor.deleteWordBack(ComposerState.of("!", 1)).text()).isEmpty();
    assertThat(ComposerEditor.deleteWordForward(ComposerState.of("!", 0)).text()).isEmpty();
  }

  @Test
  void placeholderRecognitionPinsTheReferenceWireShape() {
    var placeholder = AttachmentPlaceholder.make(123);
    assertThat(placeholder).isEqualTo("\u0001ATT:123\u0001");
    assertThat(AttachmentPlaceholder.lengthAt(placeholder, 0)).isEqualTo(placeholder.length());
    assertThat(AttachmentPlaceholder.lengthEndingAt(placeholder, placeholder.length()))
        .isEqualTo(placeholder.length());
    assertThat(AttachmentPlaceholder.lengthAt("", 0)).isZero();
    assertThat(AttachmentPlaceholder.lengthEndingAt("", 0)).isZero();
  }

  @Test
  void stateAndPlaceholderRejectInvalidPositions() {
    assertThatThrownBy(() -> ComposerState.of("x", -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ComposerState.of("x", 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AttachmentPlaceholder.make(-1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(AttachmentPlaceholder.lengthAt("x", -1)).isZero();
    assertThat(AttachmentPlaceholder.lengthEndingAt("x", 2)).isZero();
  }

  @Test
  void undoWithoutHistoryAndForwardDeleteAcrossChipAreSafe() {
    var state = ComposerState.of("plain", 2);
    assertThat(ComposerEditor.undo(state)).isSameAs(state);

    var placeholder = AttachmentPlaceholder.make(0);
    assertThat(ComposerEditor.deleteWordForward(ComposerState.of(placeholder + " tail", 0)))
        .extracting(ComposerState::text, ComposerState::cursor)
        .containsExactly(" tail", 0);
  }
}
