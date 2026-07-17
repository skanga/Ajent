package com.github.skanga.ajent.domain;

import java.util.Objects;

public record Attachment(
    Kind kind, byte[] body, String path, String mediaType, String name,
    int lineNumber, int lineCount, long byteCount) {
  public enum Kind { PASTE, FILE_REF, SYMBOL, IMAGE, OUTPUT }

  public Attachment {
    kind = Objects.requireNonNull(kind, "kind");
    body = Objects.requireNonNull(body, "body").clone();
    path = Objects.requireNonNull(path, "path");
    mediaType = Objects.requireNonNull(mediaType, "mediaType");
    name = Objects.requireNonNull(name, "name");
    if (lineNumber < 0 || lineCount < 0 || byteCount < 0) {
      throw new IllegalArgumentException("attachment counts cannot be negative");
    }
  }

  @Override public byte[] body() { return body.clone(); }
}
