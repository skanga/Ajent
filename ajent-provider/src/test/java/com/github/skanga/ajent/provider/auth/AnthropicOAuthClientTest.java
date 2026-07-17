package com.github.skanga.ajent.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class AnthropicOAuthClientTest {
  @Test void postsNativeRefreshFormAndParsesToken() throws Exception {
    var requestBody = new AtomicReference<String>();
    var contentType = new AtomicReference<String>();
    HttpServer server = server((exchange) -> {
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      contentType.set(exchange.getRequestHeaders().getFirst("content-type"));
      byte[] body = ("{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\","
          + "\"expires_in\":3600}").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    try {
      var client = new AnthropicOAuthClient(tokenUri(server));
      OAuthTokenClient.Result result = client.refresh("old refresh");
      assertThat(result).isEqualTo(new OAuthTokenClient.Result.Success(
          new OAuthTokenClient.Token("new-access", "new-refresh", 3600)));
      assertThat(contentType).hasValue("application/x-www-form-urlencoded");
      assertThat(requestBody.get()).isEqualTo("grant_type=refresh_token&client_id="
          + AnthropicOAuthClient.CLIENT_ID + "&refresh_token=old+refresh");
    } finally {
      server.stop(0);
    }
  }

  @Test void mapsApiBadJsonAndMissingTokenErrorsExactly() throws Exception {
    assertThat(call(400, "{\"error_description\":\"revoked\"}"))
        .isEqualTo(new OAuthTokenClient.Result.Failure(
            new OAuthTokenClient.Error(OAuthTokenClient.ErrorKind.API_ERROR, "revoked")));
    assertThat(call(200, "not-json"))
        .isInstanceOfSatisfying(OAuthTokenClient.Result.Failure.class, failure -> {
          assertThat(failure.error().kind()).isEqualTo(OAuthTokenClient.ErrorKind.BAD_RESPONSE);
          assertThat(failure.error().detail()).startsWith("json parse failed: ");
        });
    assertThat(call(200, "{}"))
        .isEqualTo(new OAuthTokenClient.Result.Failure(new OAuthTokenClient.Error(
            OAuthTokenClient.ErrorKind.MISSING_TOKEN,
            "200 OK but no access_token in response")));
  }

  private static OAuthTokenClient.Result call(int status, String response) throws Exception {
    HttpServer server = server(exchange -> {
      exchange.getRequestBody().readAllBytes();
      byte[] body = response.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    try {
      return new AnthropicOAuthClient(tokenUri(server)).refresh("refresh");
    } finally {
      server.stop(0);
    }
  }

  private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/oauth/token", handler);
    server.start();
    return server;
  }

  private static URI tokenUri(HttpServer server) {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/oauth/token");
  }
}
