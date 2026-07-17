package com.github.skanga.ajent.tools.rag;

import com.github.skanga.ajent.tools.host.HostServices;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Canonical AgenTTY search_docs retrieval funnel over all configured knowledge sources. */
public final class AgenttyDocRetriever implements HostServices.DocRetriever {
  private final Path docsRoot;
  private final SkillsKnowledgeSource skills;
  private final MemoryKnowledgeSource memory;
  private final KnowledgeSource mcp;
  private final boolean skillsEnabled;
  private final boolean memoryEnabled;
  private final RagCorpus docs = new RagCorpus();
  private boolean docsBuilt;

  public AgenttyDocRetriever(Path docsRoot, SkillsKnowledgeSource skills,
                             MemoryKnowledgeSource memory, KnowledgeSource mcp,
                             boolean skillsEnabled, boolean memoryEnabled) {
    this.docsRoot = docsRoot;
    this.skills = skills;
    this.memory = memory;
    this.mcp = mcp;
    this.skillsEnabled = skillsEnabled;
    this.memoryEnabled = memoryEnabled;
  }

  @Override public synchronized HostServices.DocResponse retrieve(HostServices.DocQuery query) {
    try {
      if (!docsBuilt) {
        docsBuilt = true;
        if (docsRoot != null && Files.isDirectory(docsRoot)) docs.build(docsRoot);
      }
      var router = new KnowledgeRouter();
      CorpusKnowledgeSource docsSource = new CorpusKnowledgeSource("docs", docs);
      boolean haveDocs = docs.chunkCount() > 0;
      if (haveDocs) router.add(docsSource);
      if (skillsEnabled && skills != null) router.add(skills);
      if (memoryEnabled && memory != null) router.add(memory);
      if (mcp != null) router.add(mcp);
      if (router.sourceCount() == 0) return new HostServices.DocResponse(List.of(), "",
          "no knowledge configured. Set AGENTTY_DOCS_DIR to a folder of documents "
              + "(markdown/text/etc.), create ./docs, install skills, or store memories to give "
              + "search_docs something to retrieve from.");

      int limit = query.limit();
      int pool = Math.max(limit * 5, 30);
      RagContext context = RagContext.fromHits(query.query(), router.retrieve(query.query(), pool));
      context = new RagPipeline()
          .add(new RagPipeline.RerankStage(Math.max(limit * 2, 8), RagReranker.Weights.DEFAULT))
          .add(new RagPipeline.MmrStage(limit, .75))
          .add(new RagPipeline.CompressStage(600))
          .run(context);

      String mode = "BM25-only, reranked, confidence "
          + String.format(Locale.ROOT, "%.2f", context.confidence());
      if (context.confidence() < .25)
        mode += " (LOW — treat results as leads, verify with grep/read)";
      if (haveDocs && docsRoot != null) mode += " from " + docsRoot;
      var hits = new ArrayList<HostServices.DocHit>();
      for (RagContext.ContextChunk chunk : context.chunks()) {
        RagChunk value = chunk.hit().chunk();
        if (value == null) continue;
        KnowledgeSource source = chunk.hit().source();
        hits.add(new HostServices.DocHit(source == null ? "docs" : source.name(), value.path(),
            value.lineStart(), value.lineEnd(), chunk.hit().score(), chunk.text()));
      }
      return new HostServices.DocResponse(hits, mode, "");
    } catch (RuntimeException exception) {
      return new HostServices.DocResponse(List.of(), "",
          "search_docs failed: " + exception.getMessage());
    }
  }
}
