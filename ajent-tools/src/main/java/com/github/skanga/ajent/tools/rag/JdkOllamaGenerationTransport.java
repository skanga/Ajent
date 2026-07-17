package com.github.skanga.ajent.tools.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/** JDK HTTP adapter for Ollama's non-streaming generation endpoint. */
public final class JdkOllamaGenerationTransport implements RagQueryExpander.GenerationTransport {
  private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient client;

  public JdkOllamaGenerationTransport(HttpClient client) { this.client = client; }

  @Override public Optional<String> generate(RagQueryExpander.Config config, String prompt) {
    try {
      ObjectNode body = JSON.createObjectNode();
      body.put("model", config.model());
      body.put("prompt", prompt);
      body.put("stream", false);
      ObjectNode options = body.putObject("options");
      options.put("temperature", .4);
      options.put("num_predict", 256);
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create("http://" + config.host() + ':' + config.port() + "/api/generate"))
          .timeout(Duration.ofSeconds(30))
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
