package com.github.skanga.ajent.provider.codex;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexAuthImporterTest {
  @TempDir Path temporary;

  @Test
  void explicitlyImportsFileAndDerivesAccountIdFromIdToken() throws Exception {
    Path codexHome = temporary.resolve(".codex");
    Files.createDirectories(codexHome);
    String idToken = jwt("""
        {"https://api.openai.com/auth":{"chatgpt_account_id":"acct_123"}}
        """);
    Files.writeString(codexHome.resolve("auth.json"), """
        {"tokens":{"access_token":"access","refresh_token":"refresh","id_token":"%s"}}
        """.formatted(idToken));

    var result = CodexAuthImporter.inspect(
        Map.of("CODEX_HOME", codexHome.toString()), temporary);

    assertThat(result).isInstanceOfSatisfying(CodexAuthImporter.Result.Available.class, found -> {
      assertThat(found.source()).isEqualTo(codexHome.resolve("auth.json"));
      assertThat(found.credentials().accountId()).isEqualTo("acct_123");
      assertThat(found.credentials().accessToken()).isEqualTo("access");
    });
  }

  @Test
  void reportsKeyringStorageWhenNoAuthFileExists() throws Exception {
    Path codexHome = temporary.resolve("codex-home");
    Files.createDirectories(codexHome);
    Files.writeString(codexHome.resolve("config.toml"),
        "cli_auth_credentials_store = \"keyring\"\n");

    assertThat(CodexAuthImporter.inspect(
        Map.of("CODEX_HOME", codexHome.toString()), temporary))
        .isInstanceOf(CodexAuthImporter.Result.Keyring.class);
  }

  @Test
  void rejectsOversizedOrMalformedCredentialFiles() throws Exception {
    Path codexHome = temporary.resolve("codex-home");
    Files.createDirectories(codexHome);
    Files.writeString(codexHome.resolve("auth.json"), "x".repeat(300_000));

    assertThat(CodexAuthImporter.inspect(
        Map.of("CODEX_HOME", codexHome.toString()), temporary))
        .isInstanceOf(CodexAuthImporter.Result.Invalid.class);
  }

  @Test
  void reportsMissingAndRecognizesAutoKeyringWithCommentsAndQuotes() throws Exception {
    Path codexHome = temporary.resolve("missing-home");
    assertThat(CodexAuthImporter.inspect(
        Map.of("CODEX_HOME", codexHome.toString()), temporary))
        .isEqualTo(new CodexAuthImporter.Result.Missing(
            codexHome.resolve("auth.json").toAbsolutePath().normalize()));

    Files.createDirectories(codexHome);
    Files.writeString(codexHome.resolve("config.toml"), """
        unrelated = true
        cli_auth_credentials_store
        cli_auth_credentials_store = 'auto' # use the OS keyring
        """);
    assertThat(CodexAuthImporter.inspect(
        Map.of("CODEX_HOME", codexHome.toString()), temporary))
        .isInstanceOf(CodexAuthImporter.Result.Keyring.class);
  }

  @Test
  void parsesDirectAccountExpiryAndRefreshTimeAndRejectsIncompleteTokens() throws Exception {
    Path codexHome = temporary.resolve("direct-home");
    Files.createDirectories(codexHome);
    String access = jwt("{\"exp\":12345}");
    Files.writeString(codexHome.resolve("auth.json"), """
        {"last_refresh":"2026-01-02T03:04:05Z","tokens":{
          "access_token":"%s","refresh_token":"","id_token":"",
          "account_id":"direct-account"}}
        """.formatted(access));

    assertThat(CodexAuthImporter.inspect(
        Map.of("CODEX_HOME", codexHome.toString()), temporary))
        .isInstanceOfSatisfying(CodexAuthImporter.Result.Available.class, available -> {
          assertThat(available.credentials().accountId()).isEqualTo("direct-account");
          assertThat(available.credentials().expiresAtMillis()).isEqualTo(12_345_000);
          assertThat(available.credentials().refreshedAtMillis()).isPositive();
        });

    Files.writeString(codexHome.resolve("auth.json"),
        "{\"last_refresh\":\"bad\",\"tokens\":{\"access_token\":\"\"}}");
    assertThat(CodexAuthImporter.inspect(
        Map.of("CODEX_HOME", codexHome.toString()), temporary))
        .isInstanceOfSatisfying(CodexAuthImporter.Result.Invalid.class,
            invalid -> assertThat(invalid.reason()).contains("missing"));
  }

  @Test
  void coversJwtClaimVariantsAndInvalidTokens() {
    assertThat(CodexAuthImporter.accountId(jwt(
        "{\"chatgpt_account_id\":\"direct\"}"))).isEqualTo("direct");
    assertThat(CodexAuthImporter.accountId(jwt(
        "{\"https://api.openai.com/auth\":{\"account_id\":\"nested\"}}")))
        .isEqualTo("nested");
    assertThat(CodexAuthImporter.accountId("not-a-jwt")).isEmpty();
    assertThat(CodexAuthImporter.accountId("a.%%%")).isEmpty();
    assertThat(CodexAuthImporter.longClaim(jwt("{\"exp\":-3}"), "exp")).isZero();
    assertThat(CodexAuthImporter.longClaim("", "exp")).isZero();
  }

  @Test
  void ignoresNonKeyringAndOversizedConfig() throws Exception {
    Path codexHome = temporary.resolve("config-home");
    Files.createDirectories(codexHome);
    Files.writeString(codexHome.resolve("config.toml"),
        "cli_auth_credentials_store = \"file\"\n");
    assertThat(CodexAuthImporter.inspect(
        Map.of("CODEX_HOME", codexHome.toString()), temporary))
        .isInstanceOf(CodexAuthImporter.Result.Missing.class);
    Files.writeString(codexHome.resolve("config.toml"), "x".repeat(300_000));
    assertThat(CodexAuthImporter.inspect(
        Map.of("CODEX_HOME", codexHome.toString()), temporary))
        .isInstanceOf(CodexAuthImporter.Result.Missing.class);
  }

  private static String jwt(String payload) {
    var encoder = java.util.Base64.getUrlEncoder().withoutPadding();
    return encoder.encodeToString("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "."
        + encoder.encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".sig";
  }
}
