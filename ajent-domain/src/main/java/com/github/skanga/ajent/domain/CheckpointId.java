package com.github.skanga.ajent.domain;

import java.util.Objects;

public record CheckpointId(String value) {
  public CheckpointId { value = Objects.requireNonNull(value, "value"); }
}
