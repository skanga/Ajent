package com.github.skanga.ajent.domain;

import java.util.Objects;

/** Strongly typed model identifier matching AgenTTY's ModelId. */
public record ModelId(String value) {
  public ModelId { value = Objects.requireNonNull(value, "value"); }
}
