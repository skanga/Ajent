package com.github.skanga.ajent.provider.auth;

import java.util.Objects;

/** Closed credential domain; each variant owns only fields valid for that state. */
public sealed interface Credential {
  record None() implements Credential {}

  record ApiKey(String key) implements Credential {
    public ApiKey {
      key = Objects.requireNonNull(key, "key");
    }
  }

  record OAuth(String accessToken, String refreshToken, long expiresAtMillis)
      implements Credential {
    public OAuth {
      accessToken = Objects.requireNonNull(accessToken, "accessToken");
      refreshToken = Objects.requireNonNull(refreshToken, "refreshToken");
    }
  }

  static ProviderAuth toProviderAuth(Credential credential) {
    return switch (credential) {
      case None ignored -> new ProviderAuth.Empty();
      case ApiKey key -> new ProviderAuth.ApiKey(key.key());
      case OAuth oauth -> new ProviderAuth.Bearer(oauth.accessToken());
    };
  }
}
