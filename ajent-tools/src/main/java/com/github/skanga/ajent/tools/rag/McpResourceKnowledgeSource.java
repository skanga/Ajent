package com.github.skanga.ajent.tools.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** Lazily indexes injected MCP resources behind the common knowledge-source seam. */
public final class McpResourceKnowledgeSource implements KnowledgeSource {
  public record ResourceRef(String uri, String label) {}

  private final String name;
  private final Supplier<List<ResourceRef>> list;
  private final Function<String, Optional<String>> read;
  private final RagCorpus corpus = new RagCorpus();
  private boolean built;

  public McpResourceKnowledgeSource(String name, Supplier<List<ResourceRef>> list,
                                    Function<String, Optional<String>> read) {
    this.name = name;
    this.list = list;
    this.read = read;
  }

  @Override public String name() { return name; }

  @Override public List<RagCorpus.Hit> retrieve(String query, int limit) {
    if (!built) buildIndex();
    if (corpus.chunkCount() == 0) return List.of();
    return corpus.search(query, limit).stream()
        .map(hit -> new RagCorpus.Hit(hit.chunk(), hit.score(), this)).toList();
  }

  public void refresh() { built = false; }
  public int indexedChunks() { return corpus.chunkCount(); }

  private void buildIndex() {
    built = true;
    if (list == null || read == null) return;
    List<ResourceRef> resources;
    try {
      resources = list.get();
    } catch (RuntimeException exception) {
      return;
    }
    if (resources == null || resources.isEmpty()) return;
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
    if (!documents.isEmpty()) corpus.buildFromMemory(documents);
  }
}
