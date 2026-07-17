package com.github.skanga.ajent.tools.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class NeuralRerankerTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private HttpServer server;
  @AfterEach void stopServer() { if (server != null) server.stop(0); }

  @Test void promotesNeuralScoresAndSinksPartiallyUnscoredChunks() {
    var source = new KnowledgeSource() {
      @Override public String name() { return "docs"; }
      @Override public List<RagCorpus.Hit> retrieve(String query, int limit) { return List.of(); }
    };
    List<RagCorpus.Hit> hits = List.of(
        new RagCorpus.Hit(new RagChunk("a", 1, 1, "alpha passage"), .9, source),
        new RagCorpus.Hit(new RagChunk("b", 1, 1, "perfect passage"), .2, source),
        new RagCorpus.Hit(null, .8, source));
    NeuralReranker.ScoringTransport transport = (config, prompt) -> prompt.contains("Passage: perfect")
        ? Optional.of("Score: 10/10") : Optional.of("relevance 3");
    List<RagCorpus.Hit> result = new NeuralReranker(transport).rerank("query", hits, 3,
        new NeuralReranker.Config("h", 1, "model", 2, Duration.ofSeconds(1)));
    assertThat(result).extracting(hit -> hit.chunk() == null ? "null" : hit.chunk().path())
        .containsExactly("b", "a", "null");
    assertThat(result).extracting(RagCorpus.Hit::score).containsExactly(1.0, .3, 0.0);
    assertThat(result).allSatisfy(hit -> assertThat(hit.source()).isSameAs(source));
  }

  @Test void disabledEmptyAndTotalOutagePreserveTruncatedUpstreamOrder() {
    List<RagCorpus.Hit> hits = List.of(hit("a", .9), hit("b", .8), hit("c", .7));
    NeuralReranker.ScoringTransport failing = (config, prompt) -> Optional.empty();
    var reranker = new NeuralReranker(failing);
    assertThat(reranker.rerank("q", hits, 2,
        new NeuralReranker.Config("h", 1, "", 4, Duration.ofSeconds(1))))
        .containsExactlyElementsOf(hits.subList(0, 2));
    assertThat(reranker.rerank("q", hits, 3,
        new NeuralReranker.Config("h", 1, "m", 0, Duration.ofSeconds(1))))
        .containsExactlyElementsOf(hits);
    assertThat(reranker.rerank("q", List.of(), 3,
        new NeuralReranker.Config("h", 1, "m", 4, Duration.ofSeconds(1)))).isEmpty();
    assertThat(reranker.rerank("q", hits, 0,
        new NeuralReranker.Config("h", 1, "m", 4, Duration.ofSeconds(1)))).isEmpty();
  }

  @Test void boundsPassageAndClampsFirstIntegerScore() {
    var prompt = new AtomicReference<String>();
    var reranker = new NeuralReranker((config, value) -> {
      prompt.set(value);
      return Optional.of("rating 99");
    });
    List<RagCorpus.Hit> result = reranker.rerank("q", List.of(
        new RagCorpus.Hit(new RagChunk("a", 1, 1, "x".repeat(3000)), 1)), 1,
        new NeuralReranker.Config("h", 1, "m", 99, Duration.ofSeconds(1)));
    assertThat(result.getFirst().score()).isEqualTo(1);
    assertThat(prompt.get()).hasSizeLessThan(2300).contains("output ONLY a single integer", "Score:");
  }

  @Test void jdkScoringTransportUsesExactGenerateOptions() throws Exception {
    var captured = new AtomicReference<JsonNode>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/generate", exchange -> {
      captured.set(JSON.readTree(exchange.getRequestBody()));
      byte[] response = "{\"response\":\"8\"}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    var config = new NeuralReranker.Config("127.0.0.1", server.getAddress().getPort(), "model", 2,
        Duration.ofSeconds(2));
    assertThat(new JdkOllamaScoringTransport(HttpClient.newHttpClient()).score(config, "prompt"))
        .contains("8");
    assertThat(captured.get().path("stream").asBoolean()).isFalse();
    assertThat(captured.get().path("options").path("temperature").asDouble()).isZero();
    assertThat(captured.get().path("options").path("num_predict").asInt()).isEqualTo(8);
  }

  private static RagCorpus.Hit hit(String path, double score) {
    return new RagCorpus.Hit(new RagChunk(path, 1, 1, path + " text"), score);
  }
}
