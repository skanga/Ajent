package com.github.skanga.ajent.provider.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;

/** Claude public-client PKCE authorization and authorization-code exchange. */
public final class AnthropicOAuthLogin {
  public static final URI AUTHORIZE_URI = URI.create("https://claude.ai/oauth/authorize");
  public static final URI REDIRECT_URI = URI.create(
      "https://platform.claude.com/oauth/code/callback");
  public static final String SCOPES = "user:profile user:inference user:sessions:claude_code "
      + "user:mcp_servers user:file_upload";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String URLSAFE =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

  public record Attempt(String verifier, String state, URI authorizationUri) {}

  private final URI tokenUri;
  private final HttpClient http;
  private final SecureRandom random;

  public AnthropicOAuthLogin() {
    this(AnthropicOAuthClient.TOKEN_URI, HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build(), new SecureRandom());
  }

  public AnthropicOAuthLogin(URI tokenUri) {
    this(tokenUri, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
        new SecureRandom());
  }

  AnthropicOAuthLogin(URI tokenUri, HttpClient http, SecureRandom random) {
    this.tokenUri = Objects.requireNonNull(tokenUri, "tokenUri");
    this.http = Objects.requireNonNull(http, "http");
    this.random = Objects.requireNonNull(random, "random");
  }

  public Attempt newAttempt() {
    String verifier = randomUrlSafe(128);
    String state = randomUrlSafe(32);
    return new Attempt(verifier, state, authorizationUri(verifier, state));
  }

  public static URI authorizationUri(String verifier, String state) {
    Objects.requireNonNull(verifier, "verifier");
    Objects.requireNonNull(state, "state");
    String query = "response_type=code"
        + "&client_id=" + AnthropicOAuthClient.CLIENT_ID
        + "&redirect_uri=" + field(REDIRECT_URI.toString())
        + "&scope=" + field(SCOPES)
        + "&state=" + field(state)
        + "&code_challenge=" + field(challenge(verifier))
        + "&code_challenge_method=S256&code=true";
    return URI.create(AUTHORIZE_URI + "?" + query);
  }

  public OAuthTokenClient.Result exchange(String suppliedCode, String verifier, String state) {
    Objects.requireNonNull(suppliedCode, "suppliedCode");
    String code = suppliedCode.contains("#")
        ? suppliedCode.substring(0, suppliedCode.indexOf('#')) : suppliedCode;
    String form = pair("grant_type", "authorization_code")
        + "&" + pair("code", code)
        + "&" + pair("client_id", AnthropicOAuthClient.CLIENT_ID)
        + "&" + pair("redirect_uri", REDIRECT_URI.toString())
        + "&" + pair("code_verifier", verifier)
        + "&" + pair("state", state);
    HttpRequest request = HttpRequest.newBuilder(tokenUri)
        .timeout(Duration.ofSeconds(30))
        .header("content-type", "application/x-www-form-urlencoded")
        .header("accept", "application/json")
        .header("user-agent", "ajent/0.1.0")
        .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build();
    try {
      HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.body().length > MAX_RESPONSE_BYTES) return failure(
          OAuthTokenClient.ErrorKind.BAD_RESPONSE, "response body exceeds 1048576 bytes");
      return parse(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return failure(OAuthTokenClient.ErrorKind.NETWORK, detail(exception));
    } catch (IOException | RuntimeException exception) {
      return failure(OAuthTokenClient.ErrorKind.NETWORK, detail(exception));
    }
  }

  private String randomUrlSafe(int length) {
    var output = new StringBuilder(length);
    for (int index = 0; index < length; index++)
      output.append(URLSAFE.charAt(random.nextInt(URLSAFE.length())));
    return output.toString();
  }

  private static String challenge(String verifier) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(verifier.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private static OAuthTokenClient.Result parse(int status, String body) {
    JsonNode root;
    try {
      root = JSON.readTree(body);
    } catch (JsonProcessingException exception) {
      return failure(OAuthTokenClient.ErrorKind.BAD_RESPONSE,
          "json parse failed: " + exception.getOriginalMessage());
    }
    if (status >= 400) return failure(OAuthTokenClient.ErrorKind.API_ERROR,
        root.path("error_description").asText(root.path("error").asText("HTTP " + status)));
    String access = root.path("access_token").asText();
    if (access.isEmpty()) return failure(OAuthTokenClient.ErrorKind.MISSING_TOKEN,
        "200 OK but no access_token in response");
    return new OAuthTokenClient.Result.Success(new OAuthTokenClient.Token(access,
        root.path("refresh_token").asText(), root.path("expires_in").asLong()));
  }

  private static OAuthTokenClient.Result.Failure failure(
      OAuthTokenClient.ErrorKind kind, String detail) {
    return new OAuthTokenClient.Result.Failure(new OAuthTokenClient.Error(kind, detail));
  }

  private static String pair(String name, String value) { return field(name) + "=" + field(value); }
  private static String field(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
  private static String detail(Exception exception) {
    return exception.getMessage() == null ? exception.getClass().getSimpleName()
        : exception.getMessage();
  }
}
