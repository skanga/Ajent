package com.github.skanga.ajent.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CredentialResolverTest {
  private static final long NOW = 10_000L;

  @Test
  void appliesCliThenApiKeyThenOauthThenDiskPriority() {
    var saved = Optional.<Credential>of(new Credential.ApiKey("saved"));
    assertThat(CredentialResolver.resolve(
        "cli", Map.of("ANTHROPIC_API_KEY", "env", "CLAUDE_CODE_OAUTH_TOKEN", "oauth"),
        saved, NOW).credential()).isEqualTo(new Credential.ApiKey("cli"));
    assertThat(CredentialResolver.resolve(
        "", Map.of("ANTHROPIC_API_KEY", "env", "CLAUDE_CODE_OAUTH_TOKEN", "oauth"),
        saved, NOW).credential()).isEqualTo(new Credential.ApiKey("env"));
    assertThat(CredentialResolver.resolve(
        "", Map.of("CLAUDE_CODE_OAUTH_TOKEN", "oauth"), saved, NOW).credential())
        .isEqualTo(new Credential.OAuth("oauth", "", 0));
    assertThat(CredentialResolver.resolve("", Map.of(), saved, NOW).credential())
        .isEqualTo(new Credential.ApiKey("saved"));
    assertThat(CredentialResolver.resolve("", Map.of(), Optional.empty(), NOW).credential())
        .isEqualTo(new Credential.None());
  }

  @Test
  void handlesExpiredSavedOauthWithoutBlockingStartup() {
    var refreshable = new Credential.OAuth("stale", "refresh-me", NOW - 1);
    var resolution = CredentialResolver.resolve(
        "", Map.of(), Optional.of(refreshable), NOW);
    assertThat(resolution.credential()).isEqualTo(refreshable);
    assertThat(resolution.pendingRefreshToken()).contains("refresh-me");

    var noRefresh = new Credential.OAuth("stale", "", NOW - 1);
    assertThat(CredentialResolver.resolve(
        "", Map.of(), Optional.of(noRefresh), NOW)).isEqualTo(
            new CredentialResolver.Resolution(new Credential.None(), Optional.empty()));

    var fresh = new Credential.OAuth("fresh", "refresh", NOW + 1);
    assertThat(CredentialResolver.resolve(
        "", Map.of(), Optional.of(fresh), NOW)).isEqualTo(
            new CredentialResolver.Resolution(fresh, Optional.empty()));
    var noExpiry = new Credential.OAuth("opaque", "", 0);
    assertThat(CredentialResolver.resolve(
        "", Map.of(), Optional.of(noExpiry), NOW).credential()).isEqualTo(noExpiry);
  }
}
