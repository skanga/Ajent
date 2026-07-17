package com.github.skanga.ajent.provider;

import java.util.Objects;
import java.util.Optional;

public record ProviderModel(
    String id,
    String displayName,
    String provider,
    Optional<Boolean> supportsTools,
    int contextWindow) {
  public ProviderModel {
    id = Objects.requireNonNull(id, "id");
    displayName = Objects.requireNonNull(displayName, "displayName");
    provider = Objects.requireNonNull(provider, "provider");
    supportsTools = Objects.requireNonNull(supportsTools, "supportsTools");
    if (contextWindow < 0) throw new IllegalArgumentException("contextWindow cannot be negative");
  }
}
