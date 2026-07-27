package com.github.skanga.ajent.provider.codex;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexAuthManagerTest {
  @TempDir Path temporary;

  @Test
  void refreshesExpiringTokensPersistsRotationAndReturnsChatGptHeaders() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var requestBody = new AtomicReference<String>();
    server.createContext("/oauth/token", exchange -> {
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] body = """
          {"access_token":"new-access","refresh_token":"new-refresh","id_token":"%s","expires_in":3600}
          """.formatted(jwt("""
          {"https://api.openai.com/auth":{"chatgpt_account_id":"acct_new"}}
          """)).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("content-type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      long now = 2_000_000;
      var store = new CodexCredentialStore(
          temporary.resolve("codex.json"), "seed");
      assertThat(store.save(new CodexCredentials(
          "old-access", "old-refresh", "", "acct_old", now + 10_000, 0))).isTrue();
      var manager = new CodexAuthManager(store, HttpClient.newHttpClient(),
          URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/oauth/token"),
          "client-id", () -> now);

      CodexAuthManager.Headers headers = manager.headers();

      assertThat(headers.authorization()).isEqualTo("Bearer new-access");
      assertThat(headers.accountId()).isEqualTo("acct_new");
      assertThat(requestBody.get()).contains(
          "\"grant_type\":\"refresh_token\"",
          "\"refresh_token\":\"old-refresh\"",
          "\"client_id\":\"client-id\"");
      assertThat(store.load().orElseThrow().refreshToken()).isEqualTo("new-refresh");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void handlesMissingIncompleteAndNoRefreshTokenCredentials() throws Exception {
    long now = 10_000_000;
    Path path = temporary.resolve("states.json");
    var store = new CodexCredentialStore(path, "seed");
    var manager = new CodexAuthManager(store, HttpClient.newHttpClient(),
        URI.create("http://127.0.0.1:1/token"), "client", () -> now);

    org.assertj.core.api.Assertions.assertThatThrownBy(manager::headers)
        .isInstanceOf(java.io.IOException.class).hasMessageContaining("not authenticated");

    assertThat(store.save(new CodexCredentials(
        "valid", "", "", "acct", now + 600_000, 0))).isTrue();
    assertThat(manager.headers()).isEqualTo(
        new CodexAuthManager.Headers("Bearer valid", "acct"));

    assertThat(store.save(new CodexCredentials(
        "still-valid", "", "", "acct", now + 100_000, 0))).isTrue();
    assertThat(manager.headers().authorization()).isEqualTo("Bearer still-valid");

    assertThat(store.save(new CodexCredentials(
        "expired", "", "", "acct", now, 0))).isTrue();
    org.assertj.core.api.Assertions.assertThatThrownBy(manager::headers)
        .isInstanceOf(java.io.IOException.class).hasMessageContaining("no refresh token");

    assertThat(store.save(new CodexCredentials(
        "", "", "", "acct", 0, 0))).isTrue();
    org.assertj.core.api.Assertions.assertThatThrownBy(manager::headers)
        .isInstanceOf(java.io.IOException.class).hasMessageContaining("not authenticated");
  }

  @Test
  void transientRefreshFailureKeepsValidTokenButExpiredTokenFails() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/token", exchange -> {
      byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(503, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      long now = 20_000_000;
      var store = new CodexCredentialStore(temporary.resolve("fallback.json"), "seed");
      URI endpoint = URI.create(
          "http://127.0.0.1:" + server.getAddress().getPort() + "/token");
      var manager = new CodexAuthManager(
          store, HttpClient.newHttpClient(), endpoint, "client", () -> now);

      assertThat(store.save(new CodexCredentials(
          "valid", "refresh", "", "acct", now + 100_000, 0))).isTrue();
      assertThat(manager.headers().authorization()).isEqualTo("Bearer valid");

      assertThat(store.save(new CodexCredentials(
          "expired", "refresh", "", "acct", now, 0))).isTrue();
      org.assertj.core.api.Assertions.assertThatThrownBy(manager::headers)
          .isInstanceOf(java.io.IOException.class).hasMessageContaining("HTTP 503");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void validatesUrisCredentialsAndMalformedRefreshResponses() throws Exception {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CodexAuthManager(
        new CodexCredentialStore(temporary.resolve("x"), "seed"),
        HttpClient.newHttpClient(), URI.create("file:///token"), "client", () -> 0))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTP or HTTPS");
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
        new CodexCredentials(null, "", "", "", 0, 0))
        .isInstanceOf(NullPointerException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
        new CodexCredentials("", "", "", "", -1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
        new CodexCredentials("x".repeat(140_000), "", "", "", 0, 0))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too large");

    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var response = new AtomicReference<>("{}");
    server.createContext("/token", exchange -> {
      byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      long now = 30_000_000;
      var store = new CodexCredentialStore(temporary.resolve("malformed.json"), "seed");
      assertThat(store.save(new CodexCredentials(
          "old", "refresh", "old-id", "acct", now, 0))).isTrue();
      var manager = new CodexAuthManager(store, HttpClient.newHttpClient(),
          URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token"),
          "client", () -> now);
      org.assertj.core.api.Assertions.assertThatThrownBy(manager::headers)
          .isInstanceOf(java.io.IOException.class).hasMessageContaining("no access token");

      response.set("{\"access_token\":\"new\",\"expires_in\":0}");
      assertThat(manager.headers()).isEqualTo(
          new CodexAuthManager.Headers("Bearer new", "acct"));
      assertThat(store.load().orElseThrow()).satisfies(saved -> {
        assertThat(saved.refreshToken()).isEqualTo("refresh");
        assertThat(saved.idToken()).isEqualTo("old-id");
        assertThat(saved.refreshedAtMillis()).isEqualTo(now);
      });
    } finally {
      server.stop(0);
    }
  }

  private static String jwt(String payload) {
    var encoder = java.util.Base64.getUrlEncoder().withoutPadding();
    return encoder.encodeToString("{}".getBytes(StandardCharsets.UTF_8)) + "."
        + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig";
  }
}
