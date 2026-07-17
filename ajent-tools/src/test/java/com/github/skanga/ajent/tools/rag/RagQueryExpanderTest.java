package com.github.skanga.ajent.tools.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class RagQueryExpanderTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private HttpServer server;

  @AfterEach void stopServer() { if (server != null) server.stop(0); }

  @Test void cleansDeduplicatesAndCapsGeneratedLines() {
    var prompt = new AtomicReference<String>();
    RagQueryExpander.GenerationTransport transport = (config, value) -> {
      prompt.set(value);
      return Optional.of("1. Kubernetes deployment\n- k8s replicas\n"
          + "* K8S REPLICAS\n• \"container orchestration\"\n+ x\n'extra variant'");
    };
    var expander = new RagQueryExpander(transport);
    assertThat(expander.expand(new RagQueryExpander.Config("localhost", 11434, "llama", 3),
        "kubernetes deployment")).containsExactly("kubernetes deployment", "k8s replicas",
            "container orchestration", "extra variant");
    assertThat(prompt.get()).contains("output 3 DIFFERENT search queries",
        "User query: kubernetes deployment", "Alternative queries:");
  }

  @Test void alwaysFallsBackToOriginalOnDisabledEmptyOrFailure() {
    RagQueryExpander.GenerationTransport failing = (config, prompt) -> {
      throw new IllegalStateException("offline");
    };
    var expander = new RagQueryExpander(failing);
    assertThat(expander.expand(new RagQueryExpander.Config("h", 1, "", 4), "query"))
        .containsExactly("query");
    assertThat(expander.expand(new RagQueryExpander.Config("h", 1, "m", 0), "query"))
        .containsExactly("query");
    assertThat(expander.expand(new RagQueryExpander.Config("h", 1, "m", 4), "query"))
        .containsExactly("query");
    assertThat(new RagQueryExpander((config, prompt) -> Optional.of("\n1. a\nquery\n"))
        .expand(new RagQueryExpander.Config("h", 1, "m", 4), "query"))
        .containsExactly("query");
  }

  @Test void jdkTransportUsesExactOllamaGenerateContract() throws Exception {
    var captured = new AtomicReference<JsonNode>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/generate", exchange -> {
      captured.set(JSON.readTree(exchange.getRequestBody()));
      byte[] response = "{\"response\":\"variant one\\nvariant two\"}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("content-type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    var transport = new JdkOllamaGenerationTransport(HttpClient.newHttpClient());
    var config = new RagQueryExpander.Config("127.0.0.1", server.getAddress().getPort(), "llama3.2", 2);
    assertThat(new RagQueryExpander(transport).expand(config, "original query"))
        .containsExactly("original query", "variant one", "variant two");
    assertThat(captured.get().path("model").asText()).isEqualTo("llama3.2");
    assertThat(captured.get().path("stream").asBoolean()).isFalse();
    assertThat(captured.get().path("options").path("temperature").asDouble()).isEqualTo(.4);
    assertThat(captured.get().path("options").path("num_predict").asInt()).isEqualTo(256);
  }

  @Test void jdkTransportRejectsNonSuccessAndMalformedResponses() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/generate", exchange -> {
      exchange.sendResponseHeaders(503, 0);
      exchange.close();
    });
    server.start();
    var config = new RagQueryExpander.Config("127.0.0.1", server.getAddress().getPort(), "m", 2);
    assertThat(new JdkOllamaGenerationTransport(HttpClient.newHttpClient()).generate(config, "p"))
        .isEmpty();
  }
}
