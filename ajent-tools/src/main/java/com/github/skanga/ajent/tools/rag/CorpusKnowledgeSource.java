package com.github.skanga.ajent.tools.rag;

import java.util.List;

/** Thin knowledge-source adapter over the built-in corpus. */
public record CorpusKnowledgeSource(String name, RagCorpus corpus) implements KnowledgeSource {
  @Override public List<RagCorpus.Hit> retrieve(String query, int limit) {
    return corpus.search(query, limit).stream()
        .map(hit -> new RagCorpus.Hit(hit.chunk(), hit.score(), this)).toList();
  }

  public List<RagCorpus.Hit> retrieveFused(List<String> queries, int limit) {
    return corpus.searchFused(queries, limit).stream()
        .map(hit -> new RagCorpus.Hit(hit.chunk(), hit.score(), this)).toList();
  }
}
