package com.github.skanga.ajent.domain;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Provider-aware reasoning effort choices and clamping. */
public record ReasoningPolicy(List<Effort> available, Effort fallback) {
  public ReasoningPolicy {
    available = List.copyOf(available);
    fallback = Objects.requireNonNull(fallback, "fallback");
  }

  public static ReasoningPolicy forModel(String provider, String model) {
    String providerId = provider == null ? "" : provider.toLowerCase(Locale.ROOT);
    String modelId = model == null ? "" : model.toLowerCase(Locale.ROOT);
    if ("anthropic".equals(providerId)) {
      ModelCapabilities capabilities = ModelCapabilities.fromId(modelId);
      return new ReasoningPolicy(Effort.available(capabilities), Effort.NONE);
    }
    if (!"codex".equals(providerId)) return new ReasoningPolicy(List.of(), Effort.NONE);
    if (modelId.contains("codex-mini")) {
      return new ReasoningPolicy(
          List.of(Effort.NONE, Effort.MEDIUM, Effort.HIGH), Effort.MEDIUM);
    }
    if (modelId.equals("gpt-5.3-codex-spark")) {
      return new ReasoningPolicy(
          List.of(Effort.NONE, Effort.LOW, Effort.MEDIUM, Effort.HIGH, Effort.XHIGH),
          Effort.HIGH);
    }
    return new ReasoningPolicy(
        List.of(Effort.NONE, Effort.LOW, Effort.MEDIUM, Effort.HIGH), Effort.HIGH);
  }

  public Effort clamp(Effort requested) {
    Objects.requireNonNull(requested, "requested");
    if (available.isEmpty()) return Effort.NONE;
    if (available.contains(requested)) return requested;
    if (requested == Effort.LOW && available.contains(Effort.MEDIUM)) return Effort.MEDIUM;
    if ((requested == Effort.XHIGH || requested == Effort.MAX)
        && available.contains(Effort.HIGH)) return Effort.HIGH;
    return fallback;
  }

  public Effort cycle(Effort current, int delta) {
    if (available.isEmpty()) return Effort.NONE;
    int index = available.indexOf(current);
    if (index < 0) index = 0;
    return available.get(Math.floorMod(index + delta, available.size()));
  }
}
