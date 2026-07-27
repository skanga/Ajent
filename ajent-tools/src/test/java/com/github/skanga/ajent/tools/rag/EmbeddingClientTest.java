package com.github.skanga.ajent.tools.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class EmbeddingClientTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private HttpServer server;

  @AfterEach void stopServer() { if (server != null) server.stop(0); }

  @Test void jdkClientUsesTheOllamaBatchEmbeddingContract() throws Exception {
    var captured = new AtomicReference<JsonNode>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/embed", exchange -> {
      captured.set(JSON.readTree(exchange.getRequestBody()));
      byte[] response = "{\"embeddings\":[[1,2],[3.5,4]]}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();

    var client = new JdkOllamaEmbeddingClient(HttpClient.newHttpClient());
    var config = new EmbeddingClient.Config("127.0.0.1", server.getAddress().getPort(), "nomic");
    assertThat(client.embed(config, List.of("one", "two")).orElseThrow())
        .satisfiesExactly(vector -> assertThat(vector).containsExactly(1, 2),
            vector -> assertThat(vector).containsExactly(3.5f, 4));
    assertThat(captured.get().path("model").asText()).isEqualTo("nomic");
    assertThat(captured.get().path("input")).hasSize(2);
    assertThat(captured.get().path("input").get(0).asText()).isEqualTo("one");
  }

  @Test void jdkClientDegradesOnDisabledBadStatusAndMalformedShapes() throws Exception {
    var client = new JdkOllamaEmbeddingClient(HttpClient.newHttpClient());
    assertThat(client.embed(new EmbeddingClient.Config("127.0.0.1", 1, ""), List.of("x")))
        .isEmpty();
    assertThat(client.embed(new EmbeddingClient.Config("127.0.0.1", 1, "m"), List.of()))
        .isEmpty();

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/embed", exchange -> {
      byte[] response = "{\"embeddings\":[[1,\"bad\"]]}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    var config = new EmbeddingClient.Config("127.0.0.1", server.getAddress().getPort(), "m");
    assertThat(client.embed(config, List.of("x"))).isEmpty();
  }

  @Test void jdkClientHandlesEverySupportedResponseEnvelopeAndFailureMode() throws Exception {
    var status = new java.util.concurrent.atomic.AtomicInteger(200);
    var body = new AtomicReference<>("{\"embedding\":[[9,8]]}");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/embed", exchange -> {
      byte[] response = body.get().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status.get(), response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    var client = new JdkOllamaEmbeddingClient(HttpClient.newHttpClient());
    var config = new EmbeddingClient.Config("127.0.0.1", server.getAddress().getPort(), "m");
    assertThat(client.embed(config, List.of("x")).orElseThrow().getFirst())
        .containsExactly(9, 8);
    body.set("{}");
    assertThat(client.embed(config, List.of("x"))).isEmpty();
    body.set("{\"embeddings\":[]}");
    assertThat(client.embed(config, List.of("x"))).isEmpty();
    body.set("{\"embeddings\":[7]}");
    assertThat(client.embed(config, List.of("x"))).isEmpty();
    status.set(503);
    assertThat(client.embed(config, List.of("x"))).isEmpty();
    int closedPort = server.getAddress().getPort();
    server.stop(0);
    server = null;
    assertThat(client.embed(new EmbeddingClient.Config("127.0.0.1", closedPort, "m"),
        List.of("x"))).isEmpty();
  }

  @Test void embeddingConfigurationRejectsInvalidEndpointsAndNormalizesNullModel() {
    assertThat(new EmbeddingClient.Config("host", 1, null).model()).isEmpty();
    assertThatIllegalArgumentException().isThrownBy(() -> new EmbeddingClient.Config(null, 1, "m"));
    assertThatIllegalArgumentException().isThrownBy(() -> new EmbeddingClient.Config(" ", 1, "m"));
    assertThatIllegalArgumentException().isThrownBy(() -> new EmbeddingClient.Config("h", 0, "m"));
    assertThatIllegalArgumentException().isThrownBy(
        () -> new EmbeddingClient.Config("h", 65_536, "m"));
    assertThat(EmbeddingClient.Config.ajentDefaults())
        .isEqualTo(new EmbeddingClient.Config("127.0.0.1", 11_434, "nomic-embed-text"));
    assertThat(EmbeddingClient.Config.fromEnvironment(java.util.Map.of(
        "AJENT_EMBED_MODEL", "custom", "AJENT_OLLAMA_HOST", "ollama.local:1234")))
        .isEqualTo(new EmbeddingClient.Config("ollama.local", 1234, "custom"));
    assertThat(EmbeddingClient.Config.fromEnvironment(java.util.Map.of(
        "AJENT_OLLAMA_HOST", "ollama.local:not-a-port")))
        .isEqualTo(new EmbeddingClient.Config("ollama.local", 11_434, "nomic-embed-text"));
  }

  @Test void corpusFusesLexicalAndDenseResultsAndRejectsRaggedQueries() {
    EmbeddingClient client = (config, texts) -> Optional.of(texts.stream()
        .map(text -> text.equals("semantic request") ? new float[] {0, 1} : new float[] {1, 0})
        .toList());
    var corpus = new RagCorpus(client);
    corpus.setChunks(List.of(
        new RagChunk("lexical.md", 1, 1, "semantic request", "", new float[] {1, 0}, null),
        new RagChunk("dense.md", 1, 1, "unrelated words", "", new float[] {0, 1}, null)));
    EmbeddingClient.Config enabled = new EmbeddingClient.Config("h", 1, "m");

    assertThat(corpus.embeddingDimension()).isEqualTo(2);
    assertThat(corpus.search("semantic request", enabled, 2)).extracting(hit -> hit.chunk().path())
        .containsExactly("lexical.md", "dense.md");
    EmbeddingClient ragged = (config, texts) -> Optional.of(List.of(new float[] {1, 2, 3}));
    var raggedCorpus = new RagCorpus(ragged);
    raggedCorpus.setChunks(List.of(new RagChunk("x", 1, 1, "exactword", "",
        new float[] {1, 2}, null)));
    assertThat(raggedCorpus.search("exactword", enabled, 2)).hasSize(1);
  }

  @Test void memoryBuildEmbedsInBatchesOfSixtyFourAndActivatesHnswAtTwoThousand() {
    var batchSizes = new java.util.ArrayList<Integer>();
    EmbeddingClient client = (config, texts) -> {
      batchSizes.add(texts.size());
      return Optional.of(texts.stream().map(text -> new float[] {
          text.hashCode() % 101, (text.hashCode() >>> 8) % 97, 1}).toList());
    };
    var documents = new java.util.ArrayList<RagCorpus.Document>();
    for (int index = 0; index < 2_000; index++)
      documents.add(new RagCorpus.Document("doc-" + index + ".md", "knowledge item " + index));
    var corpus = new RagCorpus(client);

    assertThat(corpus.buildFromMemory(documents, new EmbeddingClient.Config("h", 1, "m")))
        .isEqualTo(2_000);
    assertThat(batchSizes).allMatch(size -> size <= 64).hasSize(32);
    assertThat(corpus.hasApproximateIndex()).isTrue();
    assertThat(corpus.search("knowledge item 1999", new EmbeddingClient.Config("h", 1, "m"), 5))
        .isNotEmpty();
  }

  @Test void corpusDegradesToLexicalSearchWhenTheEmbeddingSeamThrows() {
    var corpus = new RagCorpus((config, texts) -> { throw new IllegalStateException("offline"); });
    corpus.setChunks(List.of(new RagChunk("fallback.md", 1, 1, "lexical fallback", "",
        new float[] {1, 0}, null)));
    assertThat(corpus.search("lexical", new EmbeddingClient.Config("h", 1, "m"), 2))
        .extracting(hit -> hit.chunk().path()).containsExactly("fallback.md");
  }
}
