package com.github.skanga.ajent.provider.codex;

import java.util.Objects;

/** ChatGPT subscription credentials copied into Ajent's encrypted credential store. */
public record CodexCredentials(
    String accessToken,
    String refreshToken,
    String idToken,
    String accountId,
    long expiresAtMillis,
    long refreshedAtMillis) {
  public CodexCredentials {
    accessToken = requireBounded(accessToken, "accessToken");
    refreshToken = requireBounded(refreshToken, "refreshToken");
    idToken = requireBounded(idToken, "idToken");
    accountId = requireBounded(accountId, "accountId");
    if (expiresAtMillis < 0 || refreshedAtMillis < 0) {
      throw new IllegalArgumentException("credential timestamps cannot be negative");
    }
  }

  private static String requireBounded(String value, String name) {
    value = Objects.requireNonNull(value, name);
    if (value.length() > 128 * 1024) {
      throw new IllegalArgumentException(name + " is too large");
    }
    return value;
  }
}
