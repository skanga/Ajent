package com.github.skanga.ajent.tools.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Bounded JDK HTTP adapter for one Ollama neural relevance score. */
public final class JdkOllamaScoringTransport implements NeuralReranker.ScoringTransport {
  private static final int MAX_RESPONSE_BYTES = 64 * 1024;
  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient client;

  public JdkOllamaScoringTransport(HttpClient client) { this.client = client; }

  @Override public Optional<String> score(NeuralReranker.Config config, String prompt) {
    try {
      ObjectNode body = JSON.createObjectNode();
      body.put("model", config.model());
      body.put("prompt", prompt);
      body.put("stream", false);
      ObjectNode options = body.putObject("options");
      options.put("temperature", 0);
      options.put("num_predict", 8);
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create("http://" + config.host() + ':' + config.port() + "/api/generate"))
          .timeout(config.timeout())
          .header("content-type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
          .build();
      HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200 || response.body().length > MAX_RESPONSE_BYTES)
        return Optional.empty();
      var parsed = JSON.readTree(response.body());
      return parsed.has("response") && parsed.get("response").isTextual()
          ? Optional.of(parsed.get("response").asText()) : Optional.empty();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (Exception exception) {
      return Optional.empty();
    }
  }
}
