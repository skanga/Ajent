package com.github.skanga.ajent.tools.rag;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Opt-in Ollama cross-encoder-style reranker with bounded fan-out and fallback. */
public final class NeuralReranker {
  public record Config(String host, int port, String model, int batchSize, Duration timeout) {}

  @FunctionalInterface
  public interface ScoringTransport {
    Optional<String> score(Config config, String prompt);
  }

  private final ScoringTransport transport;
  public NeuralReranker(ScoringTransport transport) { this.transport = transport; }

  public List<RagCorpus.Hit> rerank(String query, List<RagCorpus.Hit> hits, int outputLimit,
                                    Config config) {
    if (config.model().isEmpty() || hits.isEmpty() || outputLimit <= 0)
      return truncate(hits, outputLimit);
    int width = Math.clamp(config.batchSize(), 1, 16);
    var scores = new ArrayList<Optional<Double>>(java.util.Collections.nCopies(hits.size(), Optional.empty()));
    boolean backendAlive = false;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int start = 0; start < hits.size(); start += width) {
        int end = Math.min(start + width, hits.size());
        var futures = new ArrayList<Future<Optional<Double>>>(end - start);
        for (int index = start; index < end; index++) {
          RagChunk chunk = hits.get(index).chunk();
          futures.add(chunk == null ? null : executor.submit(() -> scoreOne(config, query, chunk.text())));
        }
        for (int index = start; index < end; index++) {
          Future<Optional<Double>> future = futures.get(index - start);
          if (future == null) continue;
          try {
            Optional<Double> score = future.get();
            scores.set(index, score);
            if (score.isPresent()) backendAlive = true;
          } catch (ExecutionException exception) {
            // One failed candidate remains unscored; other candidates still rank.
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return truncate(hits, outputLimit);
          }
        }
      }
    }
    if (!backendAlive) return truncate(hits, outputLimit);
    var order = new ArrayList<Integer>();
    for (int index = 0; index < hits.size(); index++) order.add(index);
    order.sort(Comparator.<Integer>comparingDouble(index -> scores.get(index).orElse(0.0)).reversed()
        .thenComparing(Comparator.comparingDouble((Integer index) -> hits.get(index).score()).reversed()));
    return order.stream().limit(Math.min(outputLimit, hits.size())).map(index -> {
      RagCorpus.Hit hit = hits.get(index);
      return new RagCorpus.Hit(hit.chunk(), scores.get(index).orElse(0.0), hit.source());
    }).toList();
  }

  private Optional<Double> scoreOne(Config config, String query, String passage) {
    String bounded = passage.substring(0, Math.min(2000, passage.length()));
    String prompt = "You are a relevance scoring assistant. Given a query and a passage, output ONLY "
        + "a single integer from 0 to 10 indicating how relevant the passage is to the query. "
        + "0=completely irrelevant, 10=perfectly relevant. Output NOTHING else, just the number.\n\n"
        + "Query: " + query + "\n\nPassage: " + bounded + "\n\nScore:";
    Optional<String> response;
    try {
      response = transport == null ? Optional.empty() : transport.score(config, prompt);
    } catch (RuntimeException exception) {
      return Optional.empty();
    }
    if (response.isEmpty()) return Optional.empty();
    Optional<Integer> integer = firstInteger(response.orElseThrow());
    return integer.map(value -> Math.clamp(value / 10.0, 0, 1));
  }

  static Optional<Integer> firstInteger(String value) {
    int start = 0;
    while (start < value.length() && !Character.isDigit(value.charAt(start))) start++;
    if (start >= value.length()) return Optional.empty();
    int end = start;
    while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
    try {
      return Optional.of(Integer.parseInt(value.substring(start, end)));
    } catch (NumberFormatException exception) {
      return Optional.empty();
    }
  }

  private static List<RagCorpus.Hit> truncate(List<RagCorpus.Hit> hits, int outputLimit) {
    return List.copyOf(hits.subList(0, Math.min(hits.size(), Math.max(0, outputLimit))));
  }
}
