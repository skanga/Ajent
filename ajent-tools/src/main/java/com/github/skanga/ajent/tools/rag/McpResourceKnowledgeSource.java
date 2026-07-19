package com.github.skanga.ajent.tools.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Lazily indexes injected MCP resources behind the common knowledge-source seam. */
public final class McpResourceKnowledgeSource implements KnowledgeSource {
  public record ResourceRef(String uri, String label) {}

  private final String name;
  private final Supplier<List<ResourceRef>> list;
  private final Function<String, Optional<String>> read;
  private final RagCorpus corpus;
  private final EmbeddingClient.Config embedding;
  private final LongSupplier generation;
  private boolean built;
  private long builtGeneration = Long.MIN_VALUE;

  public McpResourceKnowledgeSource(String name, Supplier<List<ResourceRef>> list,
                                    Function<String, Optional<String>> read) {
    this(name, list, read, new RagCorpus(), EmbeddingClient.Config.disabled(), () -> 0);
  }

  public McpResourceKnowledgeSource(String name, Supplier<List<ResourceRef>> list,
                                    Function<String, Optional<String>> read,
                                    RagCorpus corpus, EmbeddingClient.Config embedding,
                                    LongSupplier generation) {
    this.name = name;
    this.list = list;
    this.read = read;
    this.corpus = corpus;
    this.embedding = embedding;
    this.generation = generation;
  }

  @Override public String name() { return name; }

  @Override public synchronized List<RagCorpus.Hit> retrieve(String query, int limit) {
    long currentGeneration = generation.getAsLong();
    if (currentGeneration != builtGeneration) {
      built = false;
      builtGeneration = currentGeneration;
    }
    if (!built) buildIndex();
    if (corpus.chunkCount() == 0) return List.of();
    return corpus.search(query, embedding, limit).stream()
        .map(hit -> new RagCorpus.Hit(hit.chunk(), hit.score(), this)).toList();
  }

  public synchronized void refresh() { built = false; }
  public synchronized int indexedChunks() { return corpus.chunkCount(); }

  private void buildIndex() {
    built = true;
    if (list == null || read == null) {
      corpus.buildFromMemory(List.of(), embedding);
      return;
    }
    List<ResourceRef> resources;
    try {
      resources = list.get();
    } catch (RuntimeException exception) {
      corpus.buildFromMemory(List.of(), embedding);
      return;
    }
    if (resources == null || resources.isEmpty()) {
      corpus.buildFromMemory(List.of(), embedding);
      return;
    }
    var documents = new ArrayList<RagCorpus.Document>();
    for (ResourceRef resource : resources) {
      Optional<String> body;
      try {
        body = read.apply(resource.uri());
      } catch (RuntimeException exception) {
        continue;
      }
      if (body != null && body.isPresent() && !body.orElseThrow().isEmpty())
        documents.add(new RagCorpus.Document(resource.uri(), body.orElseThrow()));
    }
    corpus.buildFromMemory(documents, embedding);
  }
}
