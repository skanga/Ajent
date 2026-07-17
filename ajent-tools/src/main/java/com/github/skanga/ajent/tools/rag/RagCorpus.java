package com.github.skanga.ajent.tools.rag;

import java.util.ArrayList;
import java.util.List;

public final class RagCorpus {
  public record Hit(RagChunk chunk, double score) {}
  private List<RagChunk> chunks = List.of();
  private Bm25Index bm25 = RagAlgorithms.buildBm25(List.of());

  public void setChunks(List<RagChunk> value) {
    chunks = List.copyOf(value);
    bm25 = RagAlgorithms.buildBm25(chunks);
  }
  public int chunkCount() { return chunks.size(); }
  public boolean hasEmbeddings() { return chunks.stream().anyMatch(chunk -> chunk.embedding().length > 0); }

  public List<Hit> search(String query, int limit) {
    if (chunks.isEmpty() || query.isEmpty() || limit <= 0) return List.of();
    List<Integer> ranked = RagAlgorithms.searchBm25(bm25, query, Math.max(limit * 4, limit)).stream()
        .map(RagAlgorithms.Score::document).toList();
    return RagAlgorithms.reciprocalRankFusion(List.of(ranked), 60, limit).stream()
        .map(score -> new Hit(chunks.get(score.document()), score.score())).toList();
  }

  public List<Hit> searchFused(List<String> queries, int limit) {
    if (queries.isEmpty() || limit <= 0) return List.of();
    var lists = new ArrayList<List<Integer>>();
    for (String query : queries) lists.add(RagAlgorithms.searchBm25(bm25, query,
        Math.max(limit * 4, limit)).stream().map(RagAlgorithms.Score::document).toList());
    return RagAlgorithms.reciprocalRankFusion(lists, 60, limit).stream()
        .map(score -> new Hit(chunks.get(score.document()), score.score())).toList();
  }
}
