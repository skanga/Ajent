package com.github.skanga.ajent.provider.codex;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexCredentialStoreTest {
  @TempDir Path temporary;

  @Test
  void storesCodexCredentialsEncryptedAndRoundTripsThem() throws Exception {
    Path path = temporary.resolve("codex-credentials.json");
    var store = new CodexCredentialStore(path, "machine-seed");
    var credentials = new CodexCredentials(
        "access-secret", "refresh-secret", "id-secret", "acct", 1234, 5678);

    assertThat(store.save(credentials)).isTrue();
    assertThat(Files.readString(path)).doesNotContain("access-secret", "refresh-secret");
    assertThat(store.load()).contains(credentials);
  }

  @Test
  void loadRejectsMissingOversizedCorruptWrongSeedAndIncompleteFiles() throws Exception {
    Path path = temporary.resolve("bad.json");
    var store = new CodexCredentialStore(path, "seed");
    assertThat(store.load()).isEmpty();

    Files.writeString(path, "not encrypted");
    assertThat(store.load()).isEmpty();

    var credentials = new CodexCredentials("access", "", "", "acct", 0, 0);
    assertThat(store.save(credentials)).isTrue();
    assertThat(new CodexCredentialStore(path, "other-seed").load()).isEmpty();

    Files.writeString(path, "x".repeat(600_000));
    assertThat(store.load()).isEmpty();
  }

  @Test
  void clearIsIdempotentAndSaveFailsWhenParentIsAFile() throws Exception {
    Path path = temporary.resolve("clear.json");
    var store = new CodexCredentialStore(path, "seed");
    assertThat(store.clear()).isTrue();
    assertThat(store.save(new CodexCredentials("a", "", "", "acct", 0, 0))).isTrue();
    assertThat(store.clear()).isTrue();
    assertThat(path).doesNotExist();
    assertThat(store.clear()).isTrue();

    Path parent = Files.writeString(temporary.resolve("parent-file"), "x");
    var impossible = new CodexCredentialStore(parent.resolve("credentials.json"), "seed");
    assertThat(impossible.save(new CodexCredentials("a", "", "", "acct", 0, 0))).isFalse();
  }

  @Test
  void environmentPathSelectionUsesXdgOrUserConfig() {
    assertThat(CodexCredentialStore.forEnvironment(
        java.util.Map.of("XDG_CONFIG_HOME", temporary.resolve("xdg").toString()), temporary).path())
        .isEqualTo(temporary.resolve("xdg/ajent/codex-credentials.json")
            .toAbsolutePath().normalize());
    assertThat(CodexCredentialStore.forEnvironment(java.util.Map.of(), temporary).path())
        .isEqualTo(temporary.resolve(".config/ajent/codex-credentials.json")
            .toAbsolutePath().normalize());
  }
}
