package com.github.skanga.ajent.core.persistence;

import com.github.skanga.ajent.domain.ModelId;
import com.github.skanga.ajent.domain.Profile;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable user settings persisted by AgenTTY's filesystem store. */
public record Settings(
    ModelId modelId,
    Profile profile,
    List<ModelId> favoriteModels,
    String provider,
    Map<String, String> providerKeys,
    Map<String, String> providerModels,
    String effort,
    List<String> alwaysAllowTools) {

  public Settings {
    modelId = Objects.requireNonNull(modelId, "modelId");
    profile = Objects.requireNonNull(profile, "profile");
    favoriteModels = List.copyOf(favoriteModels);
    provider = Objects.requireNonNull(provider, "provider");
    providerKeys = Map.copyOf(providerKeys);
    providerModels = Map.copyOf(providerModels);
    effort = Objects.requireNonNull(effort, "effort");
    alwaysAllowTools = List.copyOf(alwaysAllowTools);
  }

  public static Settings defaults() {
    return new Settings(new ModelId(""), Profile.WRITE, List.of(), "",
        Map.of(), Map.of(), "", List.of());
  }
}
