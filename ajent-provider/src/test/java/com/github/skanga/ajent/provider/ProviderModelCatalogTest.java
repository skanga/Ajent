package com.github.skanga.ajent.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.provider.auth.ProviderAuth;
import com.github.skanga.ajent.provider.openai.Endpoint;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
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
