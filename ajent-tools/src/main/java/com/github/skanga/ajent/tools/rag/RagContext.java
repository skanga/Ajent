package com.github.skanga.ajent.tools.rag;

import java.util.List;

/** Structured retrieval output that carries derived compression and confidence state. */
public record RagContext(String query, List<ContextChunk> chunks, double confidence) {
  public record ContextChunk(RagCorpus.Hit hit, String compressed) {
    public String text() {
      if (!compressed.isEmpty()) return compressed;
      return hit.chunk() == null ? "" : hit.chunk().text();
    }
  }

  public RagContext {
    chunks = List.copyOf(chunks);
  }

  public static RagContext fromHits(String query, List<RagCorpus.Hit> hits) {
    List<ContextChunk> chunks = hits.stream().map(hit -> new ContextChunk(hit, "")).toList();
    return new RagContext(query, chunks, confidence(chunks));
  }

  private static double confidence(List<ContextChunk> chunks) {
    if (chunks.isEmpty()) return 0;
    double top = chunks.getFirst().hit().score();
    if (chunks.size() == 1) return Math.clamp(top, 0, 1);
    double sum = 0;
    double squareSum = 0;
    for (ContextChunk chunk : chunks) {
      double score = chunk.hit().score();
      sum += score;
      squareSum += score * score;
    }
    double mean = sum / chunks.size();
    double variance = squareSum / chunks.size() - mean * mean;
    double standardDeviation = variance > 0 ? Math.sqrt(variance) : 0;
    double normalizedDeviation = top > 0 ? standardDeviation / top : 1;
    return Math.clamp(top * (1 - Math.min(normalizedDeviation, 1)), 0, 1);
  }
}
