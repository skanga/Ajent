package com.github.skanga.ajent.tools.rag;

import java.util.List;

/** Thin knowledge-source adapter over the built-in hybrid corpus. */
public record CorpusKnowledgeSource(String name, RagCorpus corpus,
                                    EmbeddingClient.Config embedding) implements KnowledgeSource {
  public CorpusKnowledgeSource(String name, RagCorpus corpus) {
    this(name, corpus, EmbeddingClient.Config.disabled());
  }

  @Override public List<RagCorpus.Hit> retrieve(String query, int limit) {
    return corpus.search(query, embedding, limit).stream()
        .map(hit -> new RagCorpus.Hit(hit.chunk(), hit.score(), this)).toList();
  }

  public List<RagCorpus.Hit> retrieveFused(List<String> queries, int limit) {
    return corpus.searchFused(queries, embedding, limit).stream()
        .map(hit -> new RagCorpus.Hit(hit.chunk(), hit.score(), this)).toList();
  }
}
