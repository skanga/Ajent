package com.github.skanga.ajent.tools.rag;

import java.util.List;

/** A named, provenance-preserving source of searchable knowledge. */
public interface KnowledgeSource {
  String name();
  List<RagCorpus.Hit> retrieve(String query, int limit);
}
