package com.github.skanga.ajent.domain;

import java.util.Objects;

public record ThreadId(String value) {
  public ThreadId { value = Objects.requireNonNull(value, "value"); }
}
