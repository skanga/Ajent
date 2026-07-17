package com.github.skanga.ajent.tools.rag;

import com.github.skanga.ajent.tools.skills.Skill;
import com.github.skanga.ajent.tools.skills.SkillEngine;
import java.util.ArrayList;
import java.util.List;

/** Lazily exposes installed Agent Skills as BM25 knowledge documents. */
public final class SkillsKnowledgeSource implements KnowledgeSource {
  private final SkillEngine skills;
  private final RagCorpus corpus = new RagCorpus();
  private int builtKey;
  private boolean built;

  public SkillsKnowledgeSource(SkillEngine skills) { this.skills = skills; }
  @Override public String name() { return "skills"; }

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
    List<Skill> all = skills.all();
    int key = all.size();
    for (Skill skill : all) key ^= skill.name().hashCode();
    if (built && key == builtKey) return;
    built = true;
    builtKey = key;
    var documents = new ArrayList<RagCorpus.Document>();
    for (Skill skill : all) {
      if (skill.body().isEmpty()) continue;
      String body = skill.description().isEmpty() ? skill.body()
          : skill.description() + "\n\n" + skill.body();
      documents.add(new RagCorpus.Document("skill://" + skill.name() + "/SKILL.md", body));
    }
    corpus.buildFromMemory(documents);
  }
}
