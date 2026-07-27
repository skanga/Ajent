package com.github.skanga.ajent.provider.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.nio.file.Path;
import java.util.Map;

/** Thread-safe ChatGPT token refresh and request-header provider. */
public final class CodexAuthManager {
  public static final URI DEFAULT_TOKEN_URI = URI.create("https://auth.openai.com/oauth/token");
  public static final String DEFAULT_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long EXPIRY_MARGIN_MILLIS = Duration.ofMinutes(5).toMillis();
  private static final long REFRESH_INTERVAL_MILLIS = Duration.ofMinutes(55).toMillis();
  private static final int MAX_RESPONSE_BYTES = 256 * 1024;

  public record Headers(String authorization, String accountId) {
    public Headers {
      authorization = Objects.requireNonNull(authorization, "authorization");
      accountId = Objects.requireNonNull(accountId, "accountId");
    }
  }

  private final CodexCredentialStore store;
  private final HttpClient client;
  private final URI tokenUri;
  private final String clientId;
  private final LongSupplier clock;
  private final Object refreshLock = new Object();

  public CodexAuthManager(
      CodexCredentialStore store, HttpClient client, URI tokenUri,
      String clientId, LongSupplier clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.client = Objects.requireNonNull(client, "client");
    this.tokenUri = requireHttpUri(tokenUri);
    this.clientId = Objects.requireNonNull(clientId, "clientId");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public static CodexAuthManager systemDefault(HttpClient client) {
    return new CodexAuthManager(CodexCredentialStore.systemDefault(), client,
        DEFAULT_TOKEN_URI, DEFAULT_CLIENT_ID, System::currentTimeMillis);
  }

  public static CodexAuthManager forEnvironment(
      HttpClient client, Map<String, String> environment, Path userHome) {
    return new CodexAuthManager(CodexCredentialStore.forEnvironment(environment, userHome), client,
        DEFAULT_TOKEN_URI, DEFAULT_CLIENT_ID, System::currentTimeMillis);
  }

  public Headers headers() throws IOException, InterruptedException {
    synchronized (refreshLock) {
      CodexCredentials credentials = store.load().orElseThrow(() ->
          new IOException("Codex is not authenticated; run 'ajent login --provider codex'"));
      long now = clock.getAsLong();
      if (needsRefresh(credentials, now)) {
        try {
          credentials = refresh(credentials, now);
        } catch (IOException failure) {
          if (credentials.expiresAtMillis() > 0 && credentials.expiresAtMillis() <= now) {
            throw failure;
          }
          // A still-valid access token is safer than turning a transient refresh outage
          // into immediate downtime. The provider can still reject it authoritatively.
        }
      }
      if (credentials.accessToken().isBlank() || credentials.accountId().isBlank()) {
        throw new IOException("Codex credentials are incomplete; import them again");
      }
      return new Headers("Bearer " + credentials.accessToken(), credentials.accountId());
    }
  }

  private static boolean needsRefresh(CodexCredentials credentials, long now) {
    if (credentials.accessToken().isBlank()) return true;
    if (credentials.expiresAtMillis() > 0
        && credentials.expiresAtMillis() <= now + EXPIRY_MARGIN_MILLIS) return true;
    return credentials.refreshedAtMillis() > 0
        && credentials.refreshedAtMillis() <= now - REFRESH_INTERVAL_MILLIS;
  }

  private CodexCredentials refresh(CodexCredentials current, long now)
      throws IOException, InterruptedException {
    if (current.refreshToken().isBlank()) {
      if (current.expiresAtMillis() == 0 || current.expiresAtMillis() > now) return current;
      throw new IOException("Codex session expired and has no refresh token; import it again");
    }
    var body = JSON.createObjectNode();
    body.put("grant_type", "refresh_token");
    body.put("refresh_token", current.refreshToken());
    body.put("client_id", clientId);
    body.put("scope", "openid profile email offline_access");
    HttpRequest request = HttpRequest.newBuilder(tokenUri)
        .timeout(Duration.ofSeconds(20))
        .header("accept", "application/json")
        .header("content-type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
        .build();
    HttpResponse<java.io.InputStream> response =
        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (var stream = response.body()) {
      byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IOException("Codex token refresh failed with HTTP " + response.statusCode());
      }
      if (bytes.length > MAX_RESPONSE_BYTES) {
        throw new IOException("Codex token refresh response was too large");
      }
      var root = JSON.readTree(bytes);
      String access = root.path("access_token").asText();
      if (access.isBlank()) throw new IOException("Codex token refresh returned no access token");
      String refresh = root.path("refresh_token").asText(current.refreshToken());
      String id = root.path("id_token").asText(current.idToken());
      String account = CodexAuthImporter.accountId(id);
      if (account.isBlank()) account = current.accountId();
      long expiresIn = root.path("expires_in").asLong(0);
      long expires = expiresIn <= 0 ? CodexAuthImporter.longClaim(access, "exp") * 1000
          : now + expiresIn * 1000;
      var updated = new CodexCredentials(access, refresh, id, account, expires, now);
      if (!store.save(updated)) throw new IOException("Unable to save refreshed Codex session");
      return updated;
    }
  }

  private static URI requireHttpUri(URI value) {
    value = Objects.requireNonNull(value, "tokenUri");
    if (!"https".equalsIgnoreCase(value.getScheme())
        && !"http".equalsIgnoreCase(value.getScheme())) {
      throw new IllegalArgumentException("tokenUri must use HTTP or HTTPS");
    }
    return value;
  }
}
