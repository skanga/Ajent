package com.github.skanga.ajent.terminal.composer;

import java.util.List;
import java.util.Objects;

public record ComposerState(
    String text,
    int cursor,
    List<Attachment> attachments,
    List<Snapshot> undoStack,
    List<Snapshot> redoStack) {
  public record Snapshot(String text, int cursor, List<Attachment> attachments) {
    public Snapshot {
      text = Objects.requireNonNull(text, "text");
      attachments = List.copyOf(attachments);
    }
  }

  public ComposerState {
    text = Objects.requireNonNull(text, "text");
    if (cursor < 0 || cursor > text.length()) {
      throw new IllegalArgumentException("cursor is outside composer text");
    }
    attachments = List.copyOf(attachments);
    undoStack = List.copyOf(undoStack);
    redoStack = List.copyOf(redoStack);
  }

  public static ComposerState of(String text, int cursor) {
    return new ComposerState(text, cursor, List.of(), List.of(), List.of());
  }

  Snapshot snapshot() {
    return new Snapshot(text, cursor, attachments);
  }
}
