package com.github.skanga.ajent.tools.rag;

import com.github.skanga.ajent.tools.memory.JsonlMemoryStore;
import java.util.ArrayList;
import java.util.List;

/** Lazily exposes all durable user and project memories as BM25 documents. */
public final class MemoryKnowledgeSource implements KnowledgeSource {
  private final JsonlMemoryStore memory;
  private final RagCorpus corpus = new RagCorpus();
  private int builtKey;
  private boolean built;

  public MemoryKnowledgeSource(JsonlMemoryStore memory) { this.memory = memory; }
  @Override public String name() { return "memory"; }

  @Override public List<RagCorpus.Hit> retrieve(String query, int limit) {
    try {
      ensureBuilt();
      if (corpus.chunkCount() == 0) return List.of();
      return corpus.search(query, limit).stream()
          .map(hit -> new RagCorpus.Hit(hit.chunk(), hit.score(), this)).toList();
    } catch (RuntimeException exception) {
      return List.of();
    }
  }

  private void ensureBuilt() {
    List<JsonlMemoryStore.StoredRecord> user = memory.loadAll("user");
    List<JsonlMemoryStore.StoredRecord> project = memory.loadAll("project");
    int key = user.size() * 31 + project.size();
    for (JsonlMemoryStore.StoredRecord record : user) key ^= record.id().hashCode();
    for (JsonlMemoryStore.StoredRecord record : project) key ^= record.id().hashCode();
    if (built && key == builtKey) return;
    built = true;
    builtKey = key;
    var documents = new ArrayList<RagCorpus.Document>(user.size() + project.size());
    add(user, documents);
    add(project, documents);
    corpus.buildFromMemory(documents);
  }

  private static void add(List<JsonlMemoryStore.StoredRecord> records,
                          List<RagCorpus.Document> documents) {
    for (JsonlMemoryStore.StoredRecord record : records) {
      if (record.text().isEmpty()) continue;
      String body = record.tags().isEmpty() ? record.text()
          : record.text() + ' ' + String.join(" ", record.tags());
      documents.add(new RagCorpus.Document("memory://" + record.scope() + '/' + record.id(), body));
    }
  }
}
