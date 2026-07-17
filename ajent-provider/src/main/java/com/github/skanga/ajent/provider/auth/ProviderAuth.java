package com.github.skanga.ajent.provider.auth;

import java.util.Objects;

public sealed interface ProviderAuth {
  default boolean isEmpty() {
    return switch (this) {
      case Empty ignored -> true;
      case Bearer bearer -> bearer.token().isEmpty();
      case ApiKey apiKey -> apiKey.value().isEmpty();
    };
  }

  record Empty() implements ProviderAuth {}

  record Bearer(String token) implements ProviderAuth {
    public Bearer {
      token = Objects.requireNonNull(token, "token");
    }
  }

  record ApiKey(String value) implements ProviderAuth {
    public ApiKey {
      value = Objects.requireNonNull(value, "value");
    }
  }
}
