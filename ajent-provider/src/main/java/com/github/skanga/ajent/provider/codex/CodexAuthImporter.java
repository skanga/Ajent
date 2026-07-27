package com.github.skanga.ajent.provider.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/** Inspects Codex CLI storage for an explicit one-time import into Ajent. */
public final class CodexAuthImporter {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long MAX_SOURCE_BYTES = 256 * 1024;

  private CodexAuthImporter() {}

  public sealed interface Result {
    record Available(Path source, CodexCredentials credentials) implements Result {}
    record Keyring(Path config) implements Result {}
    record Missing(Path expected) implements Result {}
    record Invalid(Path source, String reason) implements Result {}
  }

  public static Result inspect(Map<String, String> environment, Path userHome) {
    Objects.requireNonNull(environment, "environment");
    Objects.requireNonNull(userHome, "userHome");
    Path codexHome = environment.getOrDefault("CODEX_HOME", "").isBlank()
        ? userHome.resolve(".codex")
        : Path.of(environment.get("CODEX_HOME"));
    Path auth = codexHome.resolve("auth.json").toAbsolutePath().normalize();
    if (Files.isRegularFile(auth)) return parse(auth);
    Path config = codexHome.resolve("config.toml").toAbsolutePath().normalize();
    if (usesKeyring(config)) return new Result.Keyring(config);
    return new Result.Missing(auth);
  }

  private static Result parse(Path path) {
    try {
      if (Files.size(path) > MAX_SOURCE_BYTES) {
        return new Result.Invalid(path, "credential file is too large");
      }
      JsonNode root = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
      JsonNode tokens = root == null ? null : root.path("tokens");
      String access = text(tokens, "access_token");
      String refresh = text(tokens, "refresh_token");
      String id = text(tokens, "id_token");
      String account = text(tokens, "account_id");
      if (account.isBlank()) account = accountId(id);
      if (access.isBlank() || account.isBlank()) {
        return new Result.Invalid(path, "access token or ChatGPT account id is missing");
      }
      long expires = longClaim(access, "exp") * 1000;
      long refreshed = parseInstant(root.path("last_refresh").asText());
      return new Result.Available(path,
          new CodexCredentials(access, refresh, id, account, expires, refreshed));
    } catch (IOException | RuntimeException exception) {
      return new Result.Invalid(path, "credential file is not valid Codex JSON");
    }
  }

  private static boolean usesKeyring(Path config) {
    try {
      if (!Files.isRegularFile(config) || Files.size(config) > MAX_SOURCE_BYTES) return false;
      for (String line : Files.readAllLines(config, StandardCharsets.UTF_8)) {
        String cleaned = line.split("#", 2)[0].strip();
        if (!cleaned.startsWith("cli_auth_credentials_store")) continue;
        int equals = cleaned.indexOf('=');
        if (equals < 0) continue;
        String value = cleaned.substring(equals + 1).strip().replace("\"", "").replace("'", "");
        return value.equalsIgnoreCase("keyring") || value.equalsIgnoreCase("auto");
      }
    } catch (IOException | RuntimeException ignored) {
      return false;
    }
    return false;
  }

  static String accountId(String jwt) {
    JsonNode claims = claims(jwt);
    if (claims == null) return "";
    String direct = claims.path("chatgpt_account_id").asText();
    if (!direct.isBlank()) return direct;
    JsonNode auth = claims.path("https://api.openai.com/auth");
    return auth.path("chatgpt_account_id").asText(auth.path("account_id").asText());
  }

  static long longClaim(String jwt, String name) {
    JsonNode claims = claims(jwt);
    return claims == null ? 0 : Math.max(0, claims.path(name).asLong(0));
  }

  private static JsonNode claims(String jwt) {
    try {
      String[] parts = jwt.split("\\.", -1);
      if (parts.length < 2) return null;
      return JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
    } catch (IOException | IllegalArgumentException exception) {
      return null;
    }
  }

  private static String text(JsonNode node, String field) {
    return node == null ? "" : node.path(field).asText();
  }

  private static long parseInstant(String value) {
    try {
      return value.isBlank() ? 0 : Instant.parse(value).toEpochMilli();
    } catch (RuntimeException exception) {
      return 0;
    }
  }
}
