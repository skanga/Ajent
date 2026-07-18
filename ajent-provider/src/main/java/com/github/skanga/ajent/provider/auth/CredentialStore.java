package com.github.skanga.ajent.provider.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Map;
import java.util.Set;

/** Encrypted-at-rest credential filesystem adapter with legacy plaintext loading. */
public final class CredentialStore {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS = Set.of(
      PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

  private final Path path;
  private final String machineSeed;

  public CredentialStore(Path path, String machineSeed) {
    this.path = path.toAbsolutePath();
    this.machineSeed = machineSeed;
  }

  public static CredentialStore systemDefault() {
    return new CredentialStore(CredentialPaths.systemCredentialsPath(), MachineSeed.current());
  }

  public Path path() { return path; }

  static CredentialStore forEnvironment(
      Map<String, String> environment, Path workingDirectory, String machineSeed) {
    return new CredentialStore(
        CredentialPaths.credentialsPath(environment, workingDirectory), machineSeed);
  }

  public Optional<Credential> load() {
    try {
      if (!Files.isRegularFile(path)) return Optional.empty();
      String raw = Files.readString(path, StandardCharsets.UTF_8);
      if (raw.isEmpty()) return Optional.empty();
      Optional<String> body = CredentialCrypt.looksSealed(raw)
          ? CredentialCrypt.unseal(raw, machineSeed) : Optional.of(raw);
      if (body.isEmpty()) return Optional.empty();
      var root = JSON.readTree(body.orElseThrow());
      return switch (root.path("method").asText()) {
        case "api_key" -> nonempty(root.path("access_token").asText())
            .map(Credential.ApiKey::new);
        case "oauth" -> nonempty(root.path("access_token").asText()).map(access ->
            new Credential.OAuth(access, root.path("refresh_token").asText(),
                root.path("expires_at").asLong(0)));
        default -> Optional.empty();
      };
    } catch (IOException | RuntimeException exception) {
      return Optional.empty();
    }
  }

  public boolean save(Credential credential) {
    try {
      ObjectNode root = JSON.createObjectNode();
      switch (credential) {
        case Credential.None ignored -> {
          root.put("method", "none");
          addSecretFields(root, "", "", 0);
        }
        case Credential.ApiKey apiKey -> {
          root.put("method", "api_key");
          addSecretFields(root, apiKey.key(), "", 0);
        }
        case Credential.OAuth oauth -> {
          root.put("method", "oauth");
          addSecretFields(root, oauth.accessToken(), oauth.refreshToken(), oauth.expiresAtMillis());
        }
      }
      String plaintext = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
      Optional<String> envelope = CredentialCrypt.seal(plaintext, machineSeed);
      return envelope.isPresent() && writePrivate(envelope.orElseThrow());
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

  private boolean writePrivate(String content) throws IOException {
    Path parent = path.getParent();
    Files.createDirectories(parent);
    Path temporary;
    if (Files.getFileStore(parent).supportsFileAttributeView("posix")) {
      temporary = Files.createTempFile(parent, path.getFileName().toString(), ".tmp",
          PosixFilePermissions.asFileAttribute(PRIVATE_PERMISSIONS));
    } else {
      temporary = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
    }
    try {
      byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
      try (FileChannel channel = FileChannel.open(temporary,
          StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      try {
        Files.move(temporary, path,
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
      }
      if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(path, PRIVATE_PERMISSIONS);
      }
      return true;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static Optional<String> nonempty(String value) {
    return value.isEmpty() ? Optional.empty() : Optional.of(value);
  }

  private static void addSecretFields(
      ObjectNode root, String accessToken, String refreshToken, long expiresAtMillis) {
    root.put("access_token", accessToken);
    root.put("refresh_token", refreshToken);
    root.put("expires_at", expiresAtMillis);
  }
}
