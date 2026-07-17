package com.github.skanga.ajent.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import com.github.skanga.ajent.provider.openai.Endpoint;
import com.github.skanga.ajent.provider.openai.OpenAiWire;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
          .header("user-agent", "ajent/0.1.0-SNAPSHOT")
          .GET();
      OpenAiWire.addAuthorization(builder, auth);
      JsonNode root = sendJson(builder.build(), MODELS_BODY_MAX);
      if (root == null) return List.of();
      return endpoint.nativeApi()
          ? ollamaModels(root, auth, endpoint) : openAiModels(root, endpoint);
    } catch (IOException | RuntimeException exception) {
      return List.of();
    } catch (InterruptedException exception) {
      java.lang.Thread.currentThread().interrupt();
      return List.of();
    }
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
          .header("user-agent", "ajent/0.1.0-SNAPSHOT")
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
      var fields = root.path("model_info").fields();
      while (fields.hasNext()) {
        var field = fields.next();
        if (field.getKey().endsWith(".context_length") && field.getValue().canConvertToInt()) {
          contextWindow = field.getValue().intValue();
          break;
        }
      }
      return new Probe(supportsTools, contextWindow);
    } catch (IOException | RuntimeException ignored) {
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
