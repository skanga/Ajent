package com.github.skanga.ajent.provider.openai;

import com.github.skanga.ajent.provider.auth.ProviderAuth;
import java.util.Map;
import java.util.Objects;

public final class ProviderAuthResolver {
  private ProviderAuthResolver() {}

  public static ProviderAuth resolve(
      String providerId,
      ProviderAuth anthropicCredentials,
      String cliKey,
      String savedKey,
      Map<String, String> environment) {
    Objects.requireNonNull(anthropicCredentials, "anthropicCredentials");
    Objects.requireNonNull(environment, "environment");
    var preset = ProviderRegistry.presetFor(providerId);
    if (preset.isPresent() && preset.get().kind() == ProviderRegistry.Kind.ANTHROPIC) {
      return anthropicCredentials;
    }
    if (preset.isPresent() && preset.get().kind() == ProviderRegistry.Kind.CODEX) {
      return new ProviderAuth.Empty();
    }
    if (preset.isPresent() && preset.get().authStyle() == ProviderRegistry.AuthStyle.NONE) {
      return new ProviderAuth.Empty();
    }
    if (!cliKey.isEmpty()) {
      return new ProviderAuth.ApiKey(cliKey);
    }
    if (!savedKey.isEmpty()) {
      return new ProviderAuth.ApiKey(savedKey);
    }
    if (preset.isPresent()) {
      for (String variable : preset.get().authEnvironment()) {
        String value = environment.getOrDefault(variable, "");
        if (!value.isEmpty()) {
          return new ProviderAuth.ApiKey(value);
        }
      }
    }
    return new ProviderAuth.Empty();
  }
}
