package com.github.skanga.ajent.tools.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** JDK HTTP adapter for Ollama's {@code /api/embed} batch endpoint. */
public final class JdkOllamaEmbeddingClient implements EmbeddingClient {
  private static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;
  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient client;

  public JdkOllamaEmbeddingClient(HttpClient client) {
    this.client = client;
  }

  @Override public Optional<List<float[]>> embed(Config config, List<String> texts) {
    if (config.model().isEmpty() || texts.isEmpty()) return Optional.empty();
    try {
      ObjectNode body = JSON.createObjectNode();
      body.put("model", config.model());
      ArrayNode input = body.putArray("input");
      texts.forEach(input::add);
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create("http://" + config.host() + ':' + config.port() + "/api/embed"))
          .timeout(Duration.ofSeconds(120))
          .header("content-type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body),
              StandardCharsets.UTF_8)).build();
      HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream stream = response.body()) {
        if (response.statusCode() != 200) return Optional.empty();
        byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) return Optional.empty();
        JsonNode root = JSON.readTree(bytes);
        JsonNode rows = root.path("embeddings");
        if (!rows.isArray()) rows = root.path("embedding");
        if (!rows.isArray()) return Optional.empty();
        var result = new ArrayList<float[]>(rows.size());
        for (JsonNode row : rows) {
          if (!row.isArray()) return Optional.empty();
          float[] vector = new float[row.size()];
          for (int index = 0; index < row.size(); index++) {
            if (!row.get(index).isNumber()) return Optional.empty();
            vector[index] = row.get(index).floatValue();
          }
          result.add(vector);
        }
        return result.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(result));
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (Exception exception) {
      return Optional.empty();
    }
  }
}
