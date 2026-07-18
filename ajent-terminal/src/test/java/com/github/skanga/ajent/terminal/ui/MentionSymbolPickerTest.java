package com.github.skanga.ajent.terminal.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.core.workspace.WorkspaceSymbol;
import com.github.skanga.ajent.domain.Attachment;
import java.util.List;
import org.junit.jupiter.api.Test;

class MentionSymbolPickerTest {
  @Test void mentionOwnsAsciiQueryClampsMovementAndReturnsFileRef() {
    MentionPicker.State state = MentionPicker.open(List.of(
        "README.md", "src/A.java", "src/B.java"));
    state = MentionPicker.input(state, 'b');
    state = MentionPicker.input(state, 0x1f642);
    assertThat(state).isEqualTo(new MentionPicker.Open(
        List.of("README.md", "src/A.java", "src/B.java"), "b", 0));
    state = MentionPicker.move(state, 10);
    MentionPicker.Selection selection = MentionPicker.select(state);
    assertThat(selection.state()).isEqualTo(new MentionPicker.Closed());
    assertThat(selection.attachment()).hasValueSatisfying(attachment -> {
      assertThat(attachment.kind()).isEqualTo(Attachment.Kind.FILE_REF);
      assertThat(attachment.path()).isEqualTo("src/B.java");
      assertThat(attachment.body()).isEmpty();
    });
  }

  @Test void mentionBackspaceResetsIndexThenClosesAndEmptySelectionCloses() {
    MentionPicker.State state = new MentionPicker.Open(List.of("a"), "x", 4);
    assertThat(MentionPicker.backspace(state))
        .isEqualTo(new MentionPicker.Open(List.of("a"), "", 0));
    assertThat(MentionPicker.backspace(new MentionPicker.Open(List.of(), "", 0)))
        .isEqualTo(new MentionPicker.Closed());
    assertThat(MentionPicker.select(new MentionPicker.Open(List.of(), "", 0)).attachment())
        .isEmpty();
  }

  @Test void symbolFiltersNameOnlyAndReturnsDeclarationAttachment() {
    var symbols = List.of(new WorkspaceSymbol("Alpha", "src/Z.java", 8),
        new WorkspaceSymbol("Beta", "src/Alpha.java", 3));
    SymbolPicker.State state = SymbolPicker.open(symbols);
    state = SymbolPicker.input(state, 'a');
    state = SymbolPicker.input(state, 'l');
    state = SymbolPicker.move(state, -20);
    SymbolPicker.Selection selection = SymbolPicker.select(state);
    assertThat(selection.attachment()).hasValueSatisfying(attachment -> {
      assertThat(attachment.kind()).isEqualTo(Attachment.Kind.SYMBOL);
      assertThat(attachment.name()).isEqualTo("Alpha");
      assertThat(attachment.path()).isEqualTo("src/Z.java");
      assertThat(attachment.lineNumber()).isEqualTo(8);
    });
  }

  @Test void symbolEmptyQueryBackspaceClosesAndNoMatchesClampToZero() {
    SymbolPicker.State state = SymbolPicker.open(List.of(new WorkspaceSymbol("A", "A.java", 1)));
    state = SymbolPicker.input(state, 'z');
    assertThat(SymbolPicker.move(state, 1))
        .isEqualTo(new SymbolPicker.Open(List.of(new WorkspaceSymbol("A", "A.java", 1)), "z", 0));
    state = SymbolPicker.backspace(state);
    assertThat(SymbolPicker.backspace(state)).isEqualTo(new SymbolPicker.Closed());
  }
}
