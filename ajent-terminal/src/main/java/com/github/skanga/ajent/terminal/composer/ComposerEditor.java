package com.github.skanga.ajent.terminal.composer;

import java.util.ArrayList;
import java.util.List;

public final class ComposerEditor {
  private static final int UNDO_DEPTH = 64;

  private ComposerEditor() {}

  public static ComposerState deleteWordBack(ComposerState state) {
    int position = state.cursor();
    if (position <= 0) {
      return state;
    }
    int boundary = wordLeft(state.text(), position);
    if (boundary >= position) {
      return state;
    }
    var editing = beginEdit(state);
    String text = editing.text().substring(0, boundary) + editing.text().substring(position);
    return new ComposerState(
        text, boundary, editing.attachments(), editing.undoStack(), editing.redoStack());
  }

  public static ComposerState deleteWordForward(ComposerState state) {
    int position = state.cursor();
    if (position >= state.text().length()) {
      return state;
    }
    int boundary = wordRight(state.text(), position);
    if (boundary <= position) {
      return state;
    }
    var editing = beginEdit(state);
    String text = editing.text().substring(0, position) + editing.text().substring(boundary);
    return new ComposerState(
        text, position, editing.attachments(), editing.undoStack(), editing.redoStack());
  }

  public static ComposerState undo(ComposerState state) {
    if (state.undoStack().isEmpty()) {
      return state;
    }
    var undo = new ArrayList<>(state.undoStack());
    var previous = undo.removeLast();
    var redo = appendBounded(state.redoStack(), state.snapshot());
    return new ComposerState(
        previous.text(), previous.cursor(), previous.attachments(), undo, redo);
  }

  static int wordLeft(String text, int position) {
    int chipLength = AttachmentPlaceholder.lengthEndingAt(text, position);
    if (chipLength > 0) {
      return position - chipLength;
    }
    int cursor = position;
    while (cursor > 0 && Character.isWhitespace(text.charAt(cursor - 1))) {
      cursor--;
    }
    while (cursor > 0 && isWordCharacter(text.charAt(cursor - 1))) {
      cursor--;
    }
    return cursor == position && cursor > 0 ? cursor - 1 : cursor;
  }

  static int wordRight(String text, int position) {
    int chipLength = AttachmentPlaceholder.lengthAt(text, position);
    if (chipLength > 0) {
      return position + chipLength;
    }
    int cursor = position;
    while (cursor < text.length() && isWordCharacter(text.charAt(cursor))) {
      cursor++;
    }
    while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) {
      cursor++;
    }
    return cursor == position && cursor < text.length() ? cursor + 1 : cursor;
  }

  private static ComposerState beginEdit(ComposerState state) {
    return new ComposerState(
        state.text(), state.cursor(), state.attachments(),
        appendBounded(state.undoStack(), state.snapshot()), List.of());
  }

  private static List<ComposerState.Snapshot> appendBounded(
      List<ComposerState.Snapshot> snapshots, ComposerState.Snapshot snapshot) {
    var revised = new ArrayList<>(snapshots);
    if (revised.size() >= UNDO_DEPTH) {
      revised.removeFirst();
    }
    revised.add(snapshot);
    return revised;
  }

  private static boolean isWordCharacter(char value) {
    return Character.isLetterOrDigit(value) || value == '_';
  }
}
