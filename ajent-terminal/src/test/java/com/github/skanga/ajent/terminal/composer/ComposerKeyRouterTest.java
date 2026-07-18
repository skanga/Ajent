package com.github.skanga.ajent.terminal.composer;

import static com.github.skanga.ajent.terminal.composer.ComposerKeyRouter.Action.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.terminal.input.TerminalKey;
import org.junit.jupiter.api.Test;

final class ComposerKeyRouterTest {
  private static final ComposerKeyRouter.State EMPTY =
      new ComposerKeyRouter.State(true, false, false, false, false);

  @Test void enterSubmitsWhileShiftOrAltEnterInsertsNewline() {
    assertCommand(special(TerminalKey.SpecialKey.ENTER), EMPTY, SUBMIT);
    assertCommand(special(TerminalKey.SpecialKey.ENTER, mods(false, false, true)), EMPTY, NEWLINE);
    assertCommand(special(TerminalKey.SpecialKey.ENTER, mods(false, true, false)), EMPTY, NEWLINE);
  }

  @Test void routesCharacterAndWordNavigationAndLineBoundaries() {
    assertCommand(special(TerminalKey.SpecialKey.LEFT), EMPTY, CURSOR_LEFT);
    assertCommand(special(TerminalKey.SpecialKey.LEFT, mods(true, false, false)),
        EMPTY, CURSOR_WORD_LEFT);
    assertCommand(special(TerminalKey.SpecialKey.RIGHT), EMPTY, CURSOR_RIGHT);
    assertCommand(special(TerminalKey.SpecialKey.RIGHT, mods(true, false, false)),
        EMPTY, CURSOR_WORD_RIGHT);
    assertCommand(special(TerminalKey.SpecialKey.HOME), EMPTY, CURSOR_HOME);
    assertCommand(special(TerminalKey.SpecialKey.END), EMPTY, CURSOR_END);
  }

  @Test void backspaceQueueUndoRequiresTheExactEmptyUnpeekedState() {
    var queued = new ComposerKeyRouter.State(true, true, false, false, false);
    assertCommand(special(TerminalKey.SpecialKey.BACKSPACE, mods(false, true, false)),
        queued, QUEUE_POP_LAST);
    assertCommand(special(TerminalKey.SpecialKey.BACKSPACE), queued, BACKSPACE);
    assertCommand(special(TerminalKey.SpecialKey.BACKSPACE, mods(false, true, false)),
        new ComposerKeyRouter.State(false, true, false, false, false), BACKSPACE);
    assertCommand(special(TerminalKey.SpecialKey.BACKSPACE, mods(false, true, false)),
        new ComposerKeyRouter.State(true, true, false, false, true), BACKSPACE);
  }

  @Test void upPrioritizesQueuePeekThenRecallThenHistory() {
    var queued = new ComposerKeyRouter.State(true, true, false, true, false);
    assertCommand(special(TerminalKey.SpecialKey.UP, mods(false, true, false)),
        queued, QUEUE_PEEK_PREVIOUS);
    assertCommand(special(TerminalKey.SpecialKey.UP), queued, RECALL_QUEUED);
    assertCommand(special(TerminalKey.SpecialKey.UP),
        new ComposerKeyRouter.State(true, false, false, true, false), HISTORY_PREVIOUS);
    assertCommand(special(TerminalKey.SpecialKey.UP),
        new ComposerKeyRouter.State(false, false, true, true, false), HISTORY_PREVIOUS);
    assertThat(ComposerKeyRouter.route(
        new ComposerKeyRouter.State(false, false, false, true, false),
        special(TerminalKey.SpecialKey.UP))).isEmpty();
  }

  @Test void downOnlyWalksAnActiveQueuePeekOrHistory() {
    assertCommand(special(TerminalKey.SpecialKey.DOWN, mods(false, true, false)),
        new ComposerKeyRouter.State(false, true, false, false, true), QUEUE_PEEK_NEXT);
    assertCommand(special(TerminalKey.SpecialKey.DOWN),
        new ComposerKeyRouter.State(false, false, true, true, false), HISTORY_NEXT);
    assertThat(ComposerKeyRouter.route(EMPTY, special(TerminalKey.SpecialKey.DOWN))).isEmpty();
  }

  @Test void editorControlsAcceptProtocolLettersAndLegacyControlBytes() {
    assertCommand(character('k', mods(true, false, false)), EMPTY, KILL_TO_LINE_END);
    assertCommand(character(21, mods(true, false, false)), EMPTY, KILL_TO_LINE_START);
    assertCommand(character('w', mods(true, false, false)), EMPTY, DELETE_WORD_BACK);
    assertCommand(character('z', mods(true, false, false)), EMPTY, UNDO);
    assertCommand(character('z', mods(true, false, true)), EMPTY, REDO);
    assertCommand(character('y', mods(true, false, false)), EMPTY, REDO);
    assertCommand(character(22, mods(true, false, false)), EMPTY, PASTE_IMAGE);
  }

  @Test void alternateControlsAndPrintableUnicodeMatchTheReference() {
    assertCommand(character('V', mods(false, true, true)), EMPTY, PASTE_IMAGE);
    assertCommand(character('d', mods(false, true, false)), EMPTY, DELETE_WORD_FORWARD);
    assertThat(ComposerKeyRouter.route(EMPTY, character(0x1f642)))
        .contains(new ComposerKeyRouter.Result.Insert(0x1f642));
    assertThat(ComposerKeyRouter.route(EMPTY, character(1))).isEmpty();
    assertThat(ComposerKeyRouter.route(EMPTY, special(TerminalKey.SpecialKey.ESCAPE))).isEmpty();
    assertThat(ComposerKeyRouter.route(EMPTY, special(TerminalKey.SpecialKey.TAB))).isEmpty();
  }

  private static void assertCommand(
      TerminalKey key, ComposerKeyRouter.State state, ComposerKeyRouter.Action expected) {
    assertThat(ComposerKeyRouter.route(state, key))
        .contains(new ComposerKeyRouter.Result.Command(expected));
  }

  private static TerminalKey special(TerminalKey.SpecialKey key) {
    return new TerminalKey(key);
  }

  private static TerminalKey special(TerminalKey.SpecialKey key, TerminalKey.Modifiers modifiers) {
    return new TerminalKey(key, modifiers);
  }

  private static TerminalKey character(int value) {
    return new TerminalKey(new TerminalKey.CharacterKey(value));
  }

  private static TerminalKey character(int value, TerminalKey.Modifiers modifiers) {
    return new TerminalKey(new TerminalKey.CharacterKey(value), modifiers);
  }

  private static TerminalKey.Modifiers mods(boolean ctrl, boolean alt, boolean shift) {
    return new TerminalKey.Modifiers(ctrl, alt, shift);
  }
}
