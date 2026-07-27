package com.github.skanga.ajent.provider.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.provider.auth.CredentialCrypt;
import com.github.skanga.ajent.provider.auth.CredentialPaths;
import com.github.skanga.ajent.provider.auth.MachineSeed;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Map;
import java.util.Set;

/** Ajent-owned encrypted storage for ChatGPT/Codex subscription credentials. */
public final class CodexCredentialStore {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_FILE_BYTES = 512 * 1024;
  private static final Set<PosixFilePermission> PRIVATE = Set.of(
      PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
  private final Path path;
  private final String machineSeed;

  public CodexCredentialStore(Path path, String machineSeed) {
    this.path = path.toAbsolutePath().normalize();
    this.machineSeed = java.util.Objects.requireNonNull(machineSeed, "machineSeed");
  }

  public static CodexCredentialStore systemDefault() {
    Path directory = java.util.Objects.requireNonNull(
        CredentialPaths.systemCredentialsPath().getParent());
    return new CodexCredentialStore(directory.resolve("codex-credentials.json"),
        MachineSeed.current());
  }

  public static CodexCredentialStore forEnvironment(
      Map<String, String> environment, Path userHome) {
    String xdg = environment.getOrDefault("XDG_CONFIG_HOME", "");
    Path directory = xdg.isBlank()
        ? userHome.resolve(".config").resolve("ajent") : Path.of(xdg).resolve("ajent");
    return new CodexCredentialStore(directory.resolve("codex-credentials.json"),
        MachineSeed.current());
  }

  public Path path() {
    return path;
  }

  public Optional<CodexCredentials> load() {
    try {
      if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES) return Optional.empty();
      String sealed = Files.readString(path, StandardCharsets.UTF_8);
      Optional<String> plaintext = CredentialCrypt.unseal(sealed, machineSeed);
      if (plaintext.isEmpty()) return Optional.empty();
      var root = JSON.readTree(plaintext.orElseThrow());
      String access = root.path("access_token").asText();
      String account = root.path("account_id").asText();
      if (access.isBlank() || account.isBlank()) return Optional.empty();
      return Optional.of(new CodexCredentials(access, root.path("refresh_token").asText(),
          root.path("id_token").asText(), account, root.path("expires_at").asLong(0),
          root.path("refreshed_at").asLong(0)));
    } catch (IOException | RuntimeException exception) {
      return Optional.empty();
    }
  }

  public boolean save(CodexCredentials credentials) {
    try {
      var root = JSON.createObjectNode();
      root.put("access_token", credentials.accessToken());
      root.put("refresh_token", credentials.refreshToken());
      root.put("id_token", credentials.idToken());
      root.put("account_id", credentials.accountId());
      root.put("expires_at", credentials.expiresAtMillis());
      root.put("refreshed_at", credentials.refreshedAtMillis());
      Optional<String> sealed = CredentialCrypt.seal(
          JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root), machineSeed);
      if (sealed.isEmpty()) return false;
      writePrivate(sealed.orElseThrow());
      return true;
    } catch (IOException | RuntimeException exception) {
      return false;
    }
  }

  public boolean clear() {
    try {
      return Files.deleteIfExists(path) || !Files.exists(path);
    } catch (IOException exception) {
      return false;
    }
  }

  private void writePrivate(String content) throws IOException {
    Path parent = java.util.Objects.requireNonNull(path.getParent());
    Files.createDirectories(parent);
    Path temporary = Files.createTempFile(parent, "codex-credentials-", ".tmp");
    try {
      Files.writeString(temporary, content, StandardCharsets.UTF_8);
      if (Files.getFileStore(parent).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(temporary, PRIVATE);
      }
      try {
        Files.move(temporary, path,
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
