package com.github.skanga.ajent.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class AnthropicOAuthLoginTest {
  @Test void buildsTheExactClaudePkceAuthorizationUrl() {
    String verifier = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-_";
    String state = "state with spaces";
    String url = AnthropicOAuthLogin.authorizationUri(verifier, state).toString();
    assertThat(url).isEqualTo("https://claude.ai/oauth/authorize?response_type=code"
        + "&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        + "&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback"
        + "&scope=user%3Aprofile%20user%3Ainference%20user%3Asessions%3Aclaude_code%20"
        + "user%3Amcp_servers%20user%3Afile_upload"
        + "&state=state%20with%20spaces"
        + "&code_challenge=C3emvhFZpDKWQRqM3t1AsQggBeNFS-nQhKXS5GzAHcY"
        + "&code_challenge_method=S256&code=true");
  }

  @Test void exchangesJoinedCallbackCodeWithTheExactForm() throws Exception {
    var captured = new AtomicReference<Map<String, String>>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      server.createContext("/token", exchange -> {
        captured.set(form(exchange));
        respond(exchange, 200,
            "{\"access_token\":\"access\",\"refresh_token\":\"refresh\",\"expires_in\":3600}");
      });
      server.start();
      var login = new AnthropicOAuthLogin(java.net.URI.create(
          "http://127.0.0.1:" + server.getAddress().getPort() + "/token"));
      OAuthTokenClient.Result result = login.exchange("the-code#returned-state", "verifier", "state");
      assertThat(result).isEqualTo(new OAuthTokenClient.Result.Success(
          new OAuthTokenClient.Token("access", "refresh", 3600)));
      assertThat(captured.get()).containsExactlyInAnyOrderEntriesOf(Map.of(
          "grant_type", "authorization_code", "code", "the-code",
          "client_id", AnthropicOAuthClient.CLIENT_ID,
          "redirect_uri", AnthropicOAuthLogin.REDIRECT_URI.toString(),
          "code_verifier", "verifier", "state", "state"));
    } finally {
      server.stop(0);
    }
  }

  @Test void mapsApiMalformedAndMissingTokenResponses() throws Exception {
    var status = new AtomicReference<>(400);
    var body = new AtomicReference<>("{\"error_description\":\"denied\"}");
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      server.createContext("/token", exchange -> respond(exchange, status.get(), body.get()));
      server.start();
      var login = new AnthropicOAuthLogin(java.net.URI.create(
          "http://127.0.0.1:" + server.getAddress().getPort() + "/token"));
      assertFailure(login.exchange("code", "verifier", "state"),
          OAuthTokenClient.ErrorKind.API_ERROR, "denied");
      status.set(200); body.set("not-json");
      assertFailure(login.exchange("code", "verifier", "state"),
          OAuthTokenClient.ErrorKind.BAD_RESPONSE, "json parse failed");
      body.set("{}");
      assertFailure(login.exchange("code", "verifier", "state"),
          OAuthTokenClient.ErrorKind.MISSING_TOKEN, "no access_token");
    } finally {
      server.stop(0);
    }
  }

  private static Map<String, String> form(HttpExchange exchange) throws java.io.IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    return Arrays.stream(body.split("&")).map(part -> part.split("=", 2)).collect(
        Collectors.toMap(parts -> decode(parts[0]), parts -> decode(parts[1])));
  }
  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }
  private static void respond(HttpExchange exchange, int status, String body)
      throws java.io.IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes); exchange.close();
  }
  private static void assertFailure(OAuthTokenClient.Result result,
                                    OAuthTokenClient.ErrorKind kind, String detail) {
    assertThat(result).isInstanceOf(OAuthTokenClient.Result.Failure.class);
    var error = ((OAuthTokenClient.Result.Failure) result).error();
    assertThat(error.kind()).isEqualTo(kind); assertThat(error.detail()).contains(detail);
  }
}
