package com.github.skanga.ajent.domain;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public record MessageId(String value) {
  public MessageId { value = Objects.requireNonNull(value, "value"); }

  public static MessageId random() {
    return new MessageId("%016x".formatted(ThreadLocalRandom.current().nextLong()));
  }
}
