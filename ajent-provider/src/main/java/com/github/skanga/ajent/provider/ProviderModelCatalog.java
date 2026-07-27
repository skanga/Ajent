package com.github.skanga.ajent.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.core.AgenttyDebugLog;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import com.github.skanga.ajent.provider.openai.Endpoint;
import com.github.skanga.ajent.provider.openai.OpenAiWire;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Discovers hosted OpenAI-compatible and local Ollama models. */
public final class ProviderModelCatalog {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MODELS_BODY_MAX = 2 * 1024 * 1024;
  private static final int SHOW_BODY_MAX = 512 * 1024;
  private static final int ANTHROPIC_MODELS_BODY_MAX = 1024 * 1024;
  private static final URI ANTHROPIC_MODELS =
      URI.create("https://api.anthropic.com/v1/models?limit=100");
  private static final List<ProviderModel> ANTHROPIC_SEED = List.of(
      anthropic("claude-opus-4-5", "Claude Opus 4.5"),
      anthropic("claude-sonnet-4-5", "Claude Sonnet 4.5"),
      anthropic("claude-haiku-4-5", "Claude Haiku 4.5"));
  private final HttpClient client;

  public ProviderModelCatalog(HttpClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  public List<ProviderModel> listModels(ProviderAuth auth, Endpoint endpoint) {
    if (endpoint.useTls() && auth.isEmpty()) return List.of();
    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder(
              OpenAiWire.endpointUri(endpoint, endpoint.modelsPath()))
          .timeout(Duration.ofSeconds(10))
          .header("accept", "application/json")
          .header("user-agent", "ajent/0.2.8")
          .GET();
      OpenAiWire.addAuthorization(builder, auth);
      JsonNode root = sendJson(builder.build(), MODELS_BODY_MAX);
      if (root == null) return List.of();
      return endpoint.nativeApi()
          ? ollamaModels(root, auth, endpoint) : openAiModels(root, endpoint);
    } catch (IOException | RuntimeException exception) {
      AgenttyDebugLog.log("openai.list_models.parse", exception);
      return List.of();
    } catch (InterruptedException exception) {
      java.lang.Thread.currentThread().interrupt();
      return List.of();
    }
  }

  /** Lists Anthropic models, with AgenTTY's stable offline seed as the floor. */
  public List<ProviderModel> listAnthropicModels(ProviderAuth auth) {
    return listAnthropicModels(auth, ANTHROPIC_MODELS);
  }

  List<ProviderModel> listAnthropicModels(ProviderAuth auth, URI endpoint) {
    Objects.requireNonNull(auth, "auth");
    Objects.requireNonNull(endpoint, "endpoint");
    if (auth.isEmpty()) return ANTHROPIC_SEED;
    try {
      var builder = HttpRequest.newBuilder(endpoint)
          .timeout(Duration.ofSeconds(10))
          .header("accept", "application/json")
          .header("user-agent", "ajent/0.2.8")
          .header("anthropic-version", "2023-06-01")
          .header("anthropic-dangerous-direct-browser-access", "true")
          .header("x-app", "ajent")
          .GET();
      switch (auth) {
        case ProviderAuth.ApiKey key -> builder.header("x-api-key", key.value());
        case ProviderAuth.Bearer bearer -> builder
            .header("authorization", "Bearer " + bearer.token())
            .header("anthropic-beta", "oauth-2025-04-20");
        case ProviderAuth.Empty ignored -> { }
      }
      JsonNode root = sendJson(builder.build(), ANTHROPIC_MODELS_BODY_MAX);
      if (root == null) return ANTHROPIC_SEED;
      var result = new ArrayList<ProviderModel>();
      for (JsonNode value : root.path("data")) {
        String id = value.path("id").asText();
        if (id.isEmpty()) continue;
        String displayName = value.path("display_name").asText(id);
        result.add(anthropic(id, displayName));
      }
      return result.isEmpty() ? ANTHROPIC_SEED : List.copyOf(result);
    } catch (IOException | RuntimeException exception) {
      AgenttyDebugLog.log("anthropic.list_models.parse", exception);
      return ANTHROPIC_SEED;
    } catch (InterruptedException exception) {
      java.lang.Thread.currentThread().interrupt();
      return ANTHROPIC_SEED;
    }
  }

  private static ProviderModel anthropic(String id, String displayName) {
    return new ProviderModel(id, displayName, "anthropic", Optional.empty(), 200_000);
  }

  private List<ProviderModel> openAiModels(JsonNode root, Endpoint endpoint) {
    var result = new ArrayList<ProviderModel>();
    for (JsonNode value : root.path("data")) {
      String id = value.path("id").asText();
      if (!id.isEmpty()) {
        result.add(new ProviderModel(
            id, id, endpoint.label(), Optional.empty(), 0));
      }
    }
    return List.copyOf(result);
  }

  private List<ProviderModel> ollamaModels(
      JsonNode root, ProviderAuth auth, Endpoint endpoint)
      throws IOException, InterruptedException {
    var result = new ArrayList<ProviderModel>();
    for (JsonNode value : root.path("models")) {
      String id = value.path("name").asText();
      if (id.isEmpty()) continue;
      Probe probe = probeOllama(id, auth, endpoint);
      result.add(new ProviderModel(
          id, id, endpoint.label(), probe.supportsTools(), probe.contextWindow()));
    }
    return List.copyOf(result);
  }

  private Probe probeOllama(String model, ProviderAuth auth, Endpoint endpoint)
      throws InterruptedException {
    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder(
              OpenAiWire.endpointUri(endpoint, "/api/show"))
          .timeout(Duration.ofSeconds(5))
          .header("accept", "application/json")
          .header("content-type", "application/json")
          .header("user-agent", "ajent/0.2.8")
          .POST(HttpRequest.BodyPublishers.ofString(
              JSON.createObjectNode().put("model", model).toString(), StandardCharsets.UTF_8));
      OpenAiWire.addAuthorization(builder, auth);
      JsonNode root = sendJson(builder.build(), SHOW_BODY_MAX);
      if (root == null) return Probe.UNKNOWN;
      Optional<Boolean> supportsTools = Optional.empty();
      JsonNode capabilities = root.path("capabilities");
      if (capabilities.isArray()) {
        boolean found = false;
        for (JsonNode capability : capabilities) {
          if (capability.isTextual() && "tools".equals(capability.textValue())) found = true;
        }
        supportsTools = Optional.of(found);
      }
      int contextWindow = 0;
      for (var field : root.path("model_info").properties()) {
        if (field.getKey().endsWith(".context_length") && field.getValue().canConvertToInt()) {
          contextWindow = field.getValue().intValue();
          break;
        }
      }
      return new Probe(supportsTools, contextWindow);
    } catch (IOException | RuntimeException failure) {
      AgenttyDebugLog.log("openai.probe_ollama_model.parse", failure);
      return Probe.UNKNOWN;
    }
  }

  private JsonNode sendJson(HttpRequest request, int maximumBytes)
      throws IOException, InterruptedException {
    HttpResponse<java.io.InputStream> response =
        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (var body = response.body()) {
      if (response.statusCode() != 200) return null;
      byte[] bytes = body.readNBytes(maximumBytes + 1);
      if (bytes.length > maximumBytes) return null;
      return JSON.readTree(bytes);
    }
  }

  private record Probe(Optional<Boolean> supportsTools, int contextWindow) {
    private static final Probe UNKNOWN = new Probe(Optional.empty(), 0);
  }
}
