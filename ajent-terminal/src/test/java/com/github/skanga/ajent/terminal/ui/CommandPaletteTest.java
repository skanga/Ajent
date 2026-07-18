package com.github.skanga.ajent.terminal.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class CommandPaletteTest {
  @Test void portsTheExactOrderedCommandCatalog() {
    assertThat(CommandPalette.Command.values()).extracting(CommandPalette.Command::label)
        .containsExactly("New thread", "Review changes", "Accept all changes",
            "Reject all changes", "Cycle profile", "Open model picker", "Switch provider",
            "Open threads", "Open plan", "Run code block", "Inspect tool outputs",
            "Compact context", "Rewind to checkpoint", "Login", "Quit");
  }

  @Test void filtersCaseInsensitivelyInCatalogOrder() {
    assertThat(CommandPalette.filtered("ThReAd"))
        .containsExactly(CommandPalette.Command.NEW_THREAD, CommandPalette.Command.OPEN_THREADS);
    assertThat(CommandPalette.filtered("output"))
        .containsExactly(CommandPalette.Command.INSPECT_TOOL_OUTPUTS);
    assertThat(CommandPalette.filtered("no such command")).isEmpty();
  }

  @Test void inputAndBackspaceResetTheVisibleCursor() {
    CommandPalette.State state = CommandPalette.open();
    state = CommandPalette.move(state, 8);
    state = CommandPalette.input(state, 't');
    assertThat(state).isEqualTo(new CommandPalette.Open("t", 0));
    state = CommandPalette.move(state, 2);
    state = CommandPalette.backspace(state);
    assertThat(state).isEqualTo(new CommandPalette.Open("", 0));
    assertThat(CommandPalette.input(state, 0x2603)).isSameAs(state);
  }

  @Test void movementClampsAgainstFilteredRowsAndEmptyResults() {
    CommandPalette.State state = new CommandPalette.Open("thread", 0);
    assertThat(CommandPalette.move(state, 99)).isEqualTo(new CommandPalette.Open("thread", 1));
    assertThat(CommandPalette.move(state, -99)).isEqualTo(new CommandPalette.Open("thread", 0));
    assertThat(CommandPalette.move(new CommandPalette.Open("missing", 7), 1))
        .isEqualTo(new CommandPalette.Open("missing", 0));
  }

  @Test void selectionUsesTheSameFilteredRowsRenderedByThePalette() {
    var transition = CommandPalette.select(new CommandPalette.Open("thread", 1));
    assertThat(transition.state()).isEqualTo(new CommandPalette.Closed());
    assertThat(transition.selected()).contains(CommandPalette.Command.OPEN_THREADS);
  }

  @Test void invalidOrClosedSelectionDoesNothingExceptCloseAnOpenPalette() {
    var closed = new CommandPalette.Closed();
    assertThat(CommandPalette.select(closed))
        .isEqualTo(new CommandPalette.Transition(closed, java.util.Optional.empty()));
    assertThat(CommandPalette.select(new CommandPalette.Open("thread", 9)))
        .isEqualTo(new CommandPalette.Transition(new CommandPalette.Closed(),
            java.util.Optional.empty()));
  }

  @Test void genericPickerShapesCannotCarryClosedCursorState() {
    PickerState.OneAxis one = new PickerState.Closed();
    PickerState.TwoAxis two = new PickerState.OpenAtCell(2, 3);
    PickerState.Modal modal = new PickerState.OpenModal();
    assertThat(one).isInstanceOf(PickerState.Closed.class);
    assertThat(two).isEqualTo(new PickerState.OpenAtCell(2, 3));
    assertThat(modal).isInstanceOf(PickerState.OpenModal.class);
    assertThat(PickerState.fuzzyContains("Claude Sonnet", "SON")).isTrue();
    assertThat(PickerState.fuzzyContains("é", "É")).isFalse();
  }
}
