package com.github.skanga.ajent.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialStoreTest {
  private static final String SEED = "test-machine-user-seed";

  @Test
  void savesEncryptedApiKeyAndOAuthAndLoadsThemExactly(@TempDir Path directory) throws Exception {
    Path path = directory.resolve("credentials.json");
    var store = new CredentialStore(path, SEED);

    assertThat(store.save(new Credential.ApiKey("test-key"))).isTrue();
    String apiEnvelope = Files.readString(path);
    assertThat(apiEnvelope).contains("aes-256-gcm").doesNotContain("test-key", "access_token");
    assertThat(store.load()).contains(new Credential.ApiKey("test-key"));

    var oauth = new Credential.OAuth("access", "refresh", 1_234_567L);
    assertThat(store.save(oauth)).isTrue();
    assertThat(store.load()).contains(oauth);
    if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
      assertThat(Files.getPosixFilePermissions(path)).isEqualTo(Set.of(
          PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }
  }

  @Test
  void acceptsLegacyPlaintextAndRejectsEmptyInvalidTamperedAndNone(@TempDir Path directory)
      throws Exception {
    Path path = directory.resolve("credentials.json");
    var store = new CredentialStore(path, SEED);
    Files.writeString(path,
        "{\"method\":\"api_key\",\"access_token\":\"legacy\"}");
    assertThat(store.load()).contains(new Credential.ApiKey("legacy"));

    Files.writeString(path, "{\"method\":\"api_key\",\"access_token\":\"\"}");
    assertThat(store.load()).isEmpty();
    Files.writeString(path, "{bad}");
    assertThat(store.load()).isEmpty();
    Files.writeString(path, "");
    assertThat(store.load()).isEmpty();
    assertThat(store.save(new Credential.None())).isTrue();
    assertThat(store.load()).isEmpty();

    assertThat(store.save(new Credential.ApiKey("secret"))).isTrue();
    var envelope = (com.fasterxml.jackson.databind.node.ObjectNode)
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(path));
    byte[] ciphertext = java.util.Base64.getDecoder().decode(envelope.path("ct").textValue());
    ciphertext[0] ^= 1;
    envelope.put("ct", java.util.Base64.getEncoder().encodeToString(ciphertext));
    Files.writeString(path, envelope.toString());
    assertThat(store.load()).isEmpty();
    assertThat(store.clear()).isTrue();
    assertThat(Files.exists(path)).isFalse();
    assertThat(store.clear()).isTrue();
  }

  @Test
  void mapsCredentialVariantsToTypedWireHeaders() {
    assertThat(Credential.toProviderAuth(new Credential.None()))
        .isEqualTo(new ProviderAuth.Empty());
    assertThat(Credential.toProviderAuth(new Credential.ApiKey("key")))
        .isEqualTo(new ProviderAuth.ApiKey("key"));
    assertThat(Credential.toProviderAuth(new Credential.OAuth("token", "refresh", 0)))
        .isEqualTo(new ProviderAuth.Bearer("token"));
  }

  @Test
  void productionFactoryUsesResolvedCompatibilityPathAndSeed(@TempDir Path directory) {
    var store = CredentialStore.forEnvironment(
        Map.of("XDG_CONFIG_HOME", directory.toString()), directory,
        "machine\u001fuser\u001fagentty-credentials-v1");

    assertThat(store.save(new Credential.ApiKey("shared"))).isTrue();
    assertThat(Files.exists(directory.resolve("agentty/credentials.json"))).isTrue();
    assertThat(store.load()).contains(new Credential.ApiKey("shared"));
  }
}
