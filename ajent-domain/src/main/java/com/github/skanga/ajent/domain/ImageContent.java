package com.github.skanga.ajent.domain;

import java.util.Objects;

public record ImageContent(String mediaType, byte[] bytes) {
  public ImageContent {
    mediaType = Objects.requireNonNull(mediaType, "mediaType");
    bytes = Objects.requireNonNull(bytes, "bytes").clone();
  }

  @Override public byte[] bytes() { return bytes.clone(); }

  public boolean isEmpty() { return bytes.length == 0; }
}
