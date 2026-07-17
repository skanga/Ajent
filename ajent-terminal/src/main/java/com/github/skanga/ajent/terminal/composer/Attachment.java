package com.github.skanga.ajent.terminal.composer;

import java.util.Objects;

public record Attachment(
    Kind kind,
    String body,
    String path,
    String mediaType,
    String name,
    int lineNumber,
    long lineCount,
    long byteCount) {
  public enum Kind { PASTE, FILE_REF, IMAGE, SYMBOL, OUTPUT }

  public Attachment {
    kind = Objects.requireNonNull(kind, "kind");
    body = Objects.requireNonNull(body, "body");
    path = Objects.requireNonNull(path, "path");
    mediaType = Objects.requireNonNull(mediaType, "mediaType");
    name = Objects.requireNonNull(name, "name");
  }
}
