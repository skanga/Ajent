package com.github.skanga.ajent.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.provider.auth.ProviderAuth;
import com.github.skanga.ajent.provider.codex.CodexAuthManager;
import com.github.skanga.ajent.provider.codex.CodexCredentialStore;
import com.github.skanga.ajent.provider.codex.CodexCredentials;
import com.github.skanga.ajent.provider.openai.Endpoint;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProviderModelCatalogTest {
  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  @Test
  void listsOpenAiModelsAndIgnoresEmptyIds() throws Exception {
    start();
    server.createContext("/models", exchange -> json(exchange, 200,
        "{\"data\":[{\"id\":\"alpha\"},{\"id\":\"\"},{\"id\":\"beta\"}]}"));
    var endpoint = endpoint("/models", false);

    List<ProviderModel> models = new ProviderModelCatalog(HttpClient.newHttpClient())
        .listModels(new ProviderAuth.Empty(), endpoint);

    assertThat(models).extracting(ProviderModel::id).containsExactly("alpha", "beta");
    assertThat(models).allSatisfy(model -> {
      assertThat(model.provider()).isEqualTo("test");
      assertThat(model.supportsTools()).isEmpty();
    });
  }

  @Test
  void probesOllamaCapabilitiesAndArchitectureIndependentContextLength() throws Exception {
    start();
    server.createContext("/api/tags", exchange -> json(exchange, 200,
        "{\"models\":[{\"name\":\"qwen:7b\"},{\"name\":\"plain:3b\"}]}"));
    server.createContext("/api/show", exchange -> {
      String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      if (request.contains("qwen:7b")) {
        json(exchange, 200, "{\"capabilities\":[\"completion\",\"tools\"],"
            + "\"model_info\":{\"qwen2.context_length\":32768}}" );
      } else {
        json(exchange, 200, "{\"capabilities\":[\"completion\"],"
            + "\"model_info\":{\"llama.context_length\":8192}}" );
      }
    });
    Endpoint base = endpoint("/api/tags", true);

    List<ProviderModel> models = new ProviderModelCatalog(HttpClient.newHttpClient())
        .listModels(new ProviderAuth.Empty(), base);

    assertThat(models).containsExactly(
        new ProviderModel("qwen:7b", "qwen:7b", "test", java.util.Optional.of(true), 32_768),
        new ProviderModel("plain:3b", "plain:3b", "test", java.util.Optional.of(false), 8_192));
  }

  @Test
  void keepsEveryOllamaTagWhenAnIndividualShowProbeIsMalformed() throws Exception {
    start();
    server.createContext("/api/tags", exchange -> json(exchange, 200,
        "{\"models\":[{\"name\":\"good\"},{\"name\":\"broken\"}]}"));
    server.createContext("/api/show", exchange -> {
      String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      json(exchange, 200, request.contains("good")
          ? "{\"capabilities\":[\"tools\"]}" : "{malformed}");
    });

    List<ProviderModel> models = new ProviderModelCatalog(HttpClient.newHttpClient())
        .listModels(new ProviderAuth.Empty(), endpoint("/api/tags", true));

    assertThat(models).containsExactly(
        new ProviderModel("good", "good", "test", java.util.Optional.of(true), 0),
        new ProviderModel("broken", "broken", "test", java.util.Optional.empty(), 0));
  }

  @Test
  void returnsEmptyForMissingHostedAuthHttpErrorsAndMalformedJson() throws Exception {
    var catalog = new ProviderModelCatalog(HttpClient.newHttpClient());
    assertThat(catalog.listModels(new ProviderAuth.Empty(), Endpoint.fromSpec("openai"))).isEmpty();

    start();
    server.createContext("/failure", exchange -> json(exchange, 503, "no"));
    server.createContext("/malformed", exchange -> json(exchange, 200, "{bad}"));
    assertThat(catalog.listModels(new ProviderAuth.Empty(), endpoint("/failure", false))).isEmpty();
    assertThat(catalog.listModels(new ProviderAuth.Empty(), endpoint("/malformed", false))).isEmpty();
  }

  @Test void anthropicUsesOfflineSeedWithoutCredentials() {
    List<ProviderModel> models = new ProviderModelCatalog(HttpClient.newHttpClient())
        .listAnthropicModels(new ProviderAuth.Empty());

    assertThat(models).extracting(ProviderModel::id).containsExactly(
        "claude-opus-4-5", "claude-sonnet-4-5", "claude-haiku-4-5");
    assertThat(models).allSatisfy(model -> {
      assertThat(model.provider()).isEqualTo("anthropic");
      assertThat(model.contextWindow()).isEqualTo(200_000);
    });
  }

  @Test void anthropicParsesUpstreamCatalogAndSendsTypedAuthHeaders() throws Exception {
    start();
    server.createContext("/v1/models", exchange -> {
      assertThat(exchange.getRequestURI().getQuery()).isEqualTo("limit=100");
      assertThat(exchange.getRequestHeaders().getFirst("x-api-key")).isEqualTo("secret");
      assertThat(exchange.getRequestHeaders().getFirst("anthropic-version"))
          .isEqualTo("2023-06-01");
      json(exchange, 200, "{\"data\":[{\"id\":\"claude-new\","
          + "\"display_name\":\"Claude New\"},{\"id\":\"claude-raw\"},{\"id\":\"\"}]}");
    });
    URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
        + "/v1/models?limit=100");

    List<ProviderModel> models = new ProviderModelCatalog(HttpClient.newHttpClient())
        .listAnthropicModels(new ProviderAuth.ApiKey("secret"), endpoint);

    assertThat(models).containsExactly(
        new ProviderModel("claude-new", "Claude New", "anthropic",
            java.util.Optional.empty(), 200_000),
        new ProviderModel("claude-raw", "claude-raw", "anthropic",
            java.util.Optional.empty(), 200_000));
  }

  @Test void anthropicBearerUsesOauthGateAndFailuresFallBackToSeed() throws Exception {
    start();
    server.createContext("/oauth", exchange -> {
      assertThat(exchange.getRequestHeaders().getFirst("authorization"))
          .isEqualTo("Bearer token");
      assertThat(exchange.getRequestHeaders().getFirst("anthropic-beta"))
          .isEqualTo("oauth-2025-04-20");
      json(exchange, 200, "{\"data\":[]}");
    });
    server.createContext("/bad", exchange -> json(exchange, 503, "unavailable"));
    var catalog = new ProviderModelCatalog(HttpClient.newHttpClient());
    String base = "http://127.0.0.1:" + server.getAddress().getPort();

    assertThat(catalog.listAnthropicModels(new ProviderAuth.Bearer("token"),
        URI.create(base + "/oauth"))).extracting(ProviderModel::id)
        .containsExactly("claude-opus-4-5", "claude-sonnet-4-5", "claude-haiku-4-5");
    assertThat(catalog.listAnthropicModels(new ProviderAuth.ApiKey("key"),
        URI.create(base + "/bad"))).isEqualTo(
            catalog.listAnthropicModels(new ProviderAuth.Empty()));
  }

  @Test void codexDiscoversSubscriptionModelsWithAccountHeaders() throws Exception {
    start();
    server.createContext("/models", exchange -> {
      assertThat(exchange.getRequestURI().getQuery()).isEqualTo("client_version=0.2.8");
      assertThat(exchange.getRequestHeaders().getFirst("authorization"))
          .isEqualTo("Bearer access");
      assertThat(exchange.getRequestHeaders().getFirst("chatgpt-account-id"))
          .isEqualTo("acct");
      json(exchange, 200, """
          {"models":[{"slug":"gpt-5.2-codex","display_name":"GPT-5.2 Codex",
          "context_window":272000},{"slug":""}]}
          """);
    });
    var store = new CodexCredentialStore(
        java.nio.file.Files.createTempDirectory("codex-models").resolve("auth"), "seed");
    assertThat(store.save(new CodexCredentials(
        "access", "", "", "acct", Long.MAX_VALUE, 0))).isTrue();
    var auth = new CodexAuthManager(store, HttpClient.newHttpClient(),
        URI.create("http://127.0.0.1/unused"), "client", System::currentTimeMillis);

    List<ProviderModel> models = new ProviderModelCatalog(HttpClient.newHttpClient())
        .listCodexModels(auth, URI.create("http://127.0.0.1:"
            + server.getAddress().getPort() + "/models"), "0.2.8");

    assertThat(models).containsExactly(new ProviderModel(
        "gpt-5.2-codex", "GPT-5.2 Codex", "codex",
        java.util.Optional.of(true), 272_000));
  }

  @Test void codexDiscoveryReturnsTypedHttpAndEmptyCatalogFailures() throws Exception {
    start();
    server.createContext("/denied", exchange -> json(exchange, 401, "{}"));
    server.createContext("/empty", exchange -> json(exchange, 200, "{\"models\":[]}"));
    var store = new CodexCredentialStore(
        java.nio.file.Files.createTempDirectory("codex-model-errors").resolve("auth"), "seed");
    assertThat(store.save(new CodexCredentials(
        "access", "", "", "acct", Long.MAX_VALUE, 0))).isTrue();
    var auth = new CodexAuthManager(store, HttpClient.newHttpClient(),
        URI.create("http://127.0.0.1/unused"), "client", System::currentTimeMillis);
    var catalog = new ProviderModelCatalog(HttpClient.newHttpClient());
    String base = "http://127.0.0.1:" + server.getAddress().getPort();

    assertThat(catalog.discoverCodexModels(auth, URI.create(base + "/denied"), "0.2.8"))
        .isEqualTo(new ProviderModelCatalog.Discovery.Failure(
            ProviderModelCatalog.FailureKind.AUTHENTICATION,
            "model discovery returned HTTP 401"));
    assertThat(catalog.discoverCodexModels(auth, URI.create(base + "/empty"), "0.2.8"))
        .isEqualTo(new ProviderModelCatalog.Discovery.Failure(
            ProviderModelCatalog.FailureKind.EMPTY_CATALOG, "Codex returned no models"));
  }

  private void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
  }

  private Endpoint endpoint(String path, boolean nativeApi) {
    return new Endpoint("127.0.0.1", server.getAddress().getPort(), path, path,
        false, "test", nativeApi);
  }

  private static void json(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("content-type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
