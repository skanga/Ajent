package com.github.skanga.ajent.core.persistence;

import com.github.skanga.ajent.domain.ModelId;
import com.github.skanga.ajent.domain.Profile;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Immutable user settings persisted by Ajent's filesystem store. */
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

  public Settings withProfile(Profile value) {
    return new Settings(modelId, value, favoriteModels, provider, providerKeys,
        providerModels, effort, alwaysAllowTools);
  }

  public Settings withModel(ModelId value) {
    return new Settings(value, profile, favoriteModels, provider, providerKeys,
        providerModels, effort, alwaysAllowTools);
  }

  public Settings withFavoriteModels(List<ModelId> values) {
    return new Settings(modelId, profile, values, provider, providerKeys,
        providerModels, effort, alwaysAllowTools);
  }

  public Settings withProvider(String value) {
    return new Settings(modelId, profile, favoriteModels, value, providerKeys,
        providerModels, effort, alwaysAllowTools);
  }

  public Settings withProviderModel(String providerId, ModelId value) {
    var models = new LinkedHashMap<>(providerModels);
    models.put(providerId, value.value());
    return new Settings(value, profile, favoriteModels, providerId, providerKeys,
        models, effort, alwaysAllowTools);
  }

  public Settings withProviderKey(String providerId, String key) {
    var keys = new LinkedHashMap<>(providerKeys);
    keys.put(providerId, key);
    return new Settings(modelId, profile, favoriteModels, provider, keys,
        providerModels, effort, alwaysAllowTools);
  }

  public Settings withEffort(String value) {
    return new Settings(modelId, profile, favoriteModels, provider, providerKeys,
        providerModels, value, alwaysAllowTools);
  }

  public Settings withAlwaysAllowTools(List<String> values) {
    return new Settings(modelId, profile, favoriteModels, provider, providerKeys,
        providerModels, effort, values);
  }
}
