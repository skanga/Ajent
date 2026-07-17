package com.github.skanga.ajent.tools.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fans retrieval across sources and fuses logical chunks with canonical RRF. */
public final class KnowledgeRouter {
  private final List<KnowledgeSource> sources = new ArrayList<>();

  public KnowledgeRouter add(KnowledgeSource source) {
    if (source != null) sources.add(source);
    return this;
  }

  public int sourceCount() { return sources.size(); }
  public List<RagCorpus.Hit> retrieve(String query, int limit) { return retrieve(query, limit, 0); }

  public List<RagCorpus.Hit> retrieve(String query, int limit, int perSourceLimit) {
    if (sources.isEmpty() || limit <= 0) return List.of();
    if (sources.size() == 1) return sources.getFirst().retrieve(query, limit);
    int sourceLimit = perSourceLimit == 0 ? limit : perSourceLimit;
    var pool = new ArrayList<RagCorpus.Hit>();
    var lists = new ArrayList<List<Integer>>();
    Map<String, Integer> identifiers = new HashMap<>();
    for (KnowledgeSource source : sources) {
      var ids = new ArrayList<Integer>();
      Set<Integer> seen = new HashSet<>();
      for (RagCorpus.Hit hit : source.retrieve(query, sourceLimit)) {
        String key = key(hit);
        Integer identifier = key.isEmpty() ? null : identifiers.get(key);
        if (identifier == null) {
          identifier = pool.size();
          pool.add(hit);
          if (!key.isEmpty()) identifiers.put(key, identifier);
        }
        if (seen.add(identifier)) ids.add(identifier);
      }
      if (!ids.isEmpty()) lists.add(ids);
    }
    if (pool.isEmpty()) return List.of();
    return RagAlgorithms.reciprocalRankFusion(lists, 60, limit).stream().map(score -> {
      RagCorpus.Hit hit = pool.get(score.document());
      return new RagCorpus.Hit(hit.chunk(), score.score(), hit.source());
    }).toList();
  }

  private static String key(RagCorpus.Hit hit) {
    RagChunk chunk = hit.chunk();
    return chunk == null ? "" : chunk.path() + '\0' + chunk.lineStart() + ':' + chunk.lineEnd();
  }
}
