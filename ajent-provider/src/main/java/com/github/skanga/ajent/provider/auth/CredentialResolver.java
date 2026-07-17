package com.github.skanga.ajent.provider.auth;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Pure, non-blocking AgenTTY credential precedence and expiry policy. */
public final class CredentialResolver {
  private CredentialResolver() {}

  public record Resolution(Credential credential, Optional<String> pendingRefreshToken) {
    public Resolution {
      credential = Objects.requireNonNull(credential, "credential");
      pendingRefreshToken = Objects.requireNonNull(pendingRefreshToken, "pendingRefreshToken");
    }
  }

  public static Resolution resolve(
      String cliApiKey,
      Map<String, String> environment,
      Optional<Credential> saved,
      long nowMillis) {
    if (!cliApiKey.isEmpty()) return resolved(new Credential.ApiKey(cliApiKey));
    String apiKey = environment.getOrDefault("ANTHROPIC_API_KEY", "");
    if (!apiKey.isEmpty()) return resolved(new Credential.ApiKey(apiKey));
    String oauthToken = environment.getOrDefault("CLAUDE_CODE_OAUTH_TOKEN", "");
    if (!oauthToken.isEmpty()) return resolved(new Credential.OAuth(oauthToken, "", 0));
    if (saved.isEmpty()) return resolved(new Credential.None());
    Credential credential = saved.orElseThrow();
    if (!(credential instanceof Credential.OAuth oauth)) return resolved(credential);
    boolean expired = oauth.expiresAtMillis() != 0 && nowMillis >= oauth.expiresAtMillis();
    if (!expired) return resolved(oauth);
    if (oauth.refreshToken().isEmpty()) return resolved(new Credential.None());
    return new Resolution(oauth, Optional.of(oauth.refreshToken()));
  }

  private static Resolution resolved(Credential credential) {
    return new Resolution(credential, Optional.empty());
  }
}
