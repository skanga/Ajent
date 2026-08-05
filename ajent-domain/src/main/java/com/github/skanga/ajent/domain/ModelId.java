package com.github.skanga.ajent.domain;

import java.util.Objects;

/** Strongly typed model identifier matching Ajent's ModelId. */
public record ModelId(String value) {
  public ModelId { value = Objects.requireNonNull(value, "value"); }
}
