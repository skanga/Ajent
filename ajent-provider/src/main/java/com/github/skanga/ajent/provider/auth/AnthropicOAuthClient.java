package com.github.skanga.ajent.provider.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.provider.EnvironmentHttpClient;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** Anthropic/Claude Code public-client OAuth refresh protocol. */
public final class AnthropicOAuthClient implements OAuthTokenClient {
  public static final String CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
  public static final URI TOKEN_URI = URI.create("https://platform.claude.com/v1/oauth/token");
  private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
  private static final ObjectMapper JSON = new ObjectMapper();

  private final URI tokenUri;
  private final HttpClient http;

  public AnthropicOAuthClient() {
    this(TOKEN_URI);
  }

  public AnthropicOAuthClient(URI tokenUri) {
    this(tokenUri, EnvironmentHttpClient.oauthBuilder(System.getenv())
        .connectTimeout(Duration.ofSeconds(10)).build());
  }

  AnthropicOAuthClient(URI tokenUri, HttpClient http) {
    this.tokenUri = Objects.requireNonNull(tokenUri, "tokenUri");
    this.http = Objects.requireNonNull(http, "http");
  }

  @Override public Result refresh(String refreshToken) {
    Objects.requireNonNull(refreshToken, "refreshToken");
    String form = field("grant_type", "refresh_token") + "&" + field("client_id", CLIENT_ID)
        + "&" + field("refresh_token", refreshToken);
    HttpRequest request = HttpRequest.newBuilder(tokenUri)
        .timeout(Duration.ofSeconds(30))
        .header("content-type", "application/x-www-form-urlencoded")
        .header("accept", "application/json")
        .header("user-agent", "ajent/0.1.0")
        .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
        .build();
    try {
      HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.body().length > MAX_RESPONSE_BYTES) return failure(ErrorKind.BAD_RESPONSE,
          "response body exceeds 1048576 bytes");
      return parse(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    } catch (InterruptedException exception) {
      java.lang.Thread.currentThread().interrupt();
      return failure(ErrorKind.NETWORK, detail(exception));
    } catch (IOException | RuntimeException exception) {
      return failure(ErrorKind.NETWORK, detail(exception));
    }
  }

  private static Result parse(int status, String body) {
    JsonNode root;
    try {
      root = JSON.readTree(body);
    } catch (JsonProcessingException exception) {
      return failure(ErrorKind.BAD_RESPONSE, "json parse failed: " + exception.getOriginalMessage());
    }
    if (status >= 400) {
      String fallback = root.path("error").asText("HTTP " + status);
      return failure(ErrorKind.API_ERROR, root.path("error_description").asText(fallback));
    }
    String accessToken = root.path("access_token").asText();
    if (accessToken.isEmpty()) return failure(ErrorKind.MISSING_TOKEN,
        "200 OK but no access_token in response");
    return new Result.Success(new Token(accessToken, root.path("refresh_token").asText(),
        root.path("expires_in").asLong()));
  }

  private static Result.Failure failure(ErrorKind kind, String detail) {
    return new Result.Failure(new Error(kind, detail));
  }

  private static String field(String name, String value) {
    return URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
        + URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String detail(Exception exception) {
    return exception.getMessage() == null ? exception.getClass().getSimpleName()
        : exception.getMessage();
  }
}
