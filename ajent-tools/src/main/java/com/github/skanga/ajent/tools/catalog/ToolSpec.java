package com.github.skanga.ajent.tools.catalog;

import com.github.skanga.ajent.tools.policy.EffectSet;
import java.time.Duration;
import java.util.Objects;

public record ToolSpec(
    String name,
    ToolKind kind,
    EffectSet effects,
    boolean eagerInputStreaming,
    Duration timeout,
    int maxOutputCharacters,
    TruncationStrategy truncationStrategy) {
  public ToolSpec {
    name = Objects.requireNonNull(name, "name");
    kind = Objects.requireNonNull(kind, "kind");
    effects = Objects.requireNonNull(effects, "effects");
    timeout = Objects.requireNonNull(timeout, "timeout");
    truncationStrategy = Objects.requireNonNull(truncationStrategy, "truncationStrategy");
  }
}
