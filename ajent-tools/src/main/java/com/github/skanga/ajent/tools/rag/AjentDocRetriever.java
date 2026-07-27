package com.github.skanga.ajent.tools.rag;

import com.github.skanga.ajent.tools.host.HostServices;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Canonical Ajent search_docs retrieval funnel over all configured knowledge sources. */
public final class AjentDocRetriever implements HostServices.DocRetriever {
  private final Path docsRoot;
  private final SkillsKnowledgeSource skills;
  private final MemoryKnowledgeSource memory;
  private final KnowledgeSource mcp;
  private final boolean skillsEnabled;
  private final boolean memoryEnabled;
  private final RagQueryExpander expander;
  private final RagQueryExpander.Config expansionConfig;
  private final NeuralReranker neuralReranker;
  private final NeuralReranker.Config neuralConfig;
  private final RagCorpus docs;
  private final EmbeddingClient.Config embeddingConfig;
  private boolean docsBuilt;

  public AjentDocRetriever(Path docsRoot, SkillsKnowledgeSource skills,
                             MemoryKnowledgeSource memory, KnowledgeSource mcp,
                             boolean skillsEnabled, boolean memoryEnabled) {
    this(docsRoot, skills, memory, mcp, skillsEnabled, memoryEnabled, null, null, null, null);
  }

  public AjentDocRetriever(Path docsRoot, SkillsKnowledgeSource skills,
                             MemoryKnowledgeSource memory, KnowledgeSource mcp,
                             boolean skillsEnabled, boolean memoryEnabled,
                             RagQueryExpander expander, RagQueryExpander.Config expansionConfig) {
    this(docsRoot, skills, memory, mcp, skillsEnabled, memoryEnabled, expander, expansionConfig,
        null, null);
  }

  public AjentDocRetriever(Path docsRoot, SkillsKnowledgeSource skills,
                             MemoryKnowledgeSource memory, KnowledgeSource mcp,
                             boolean skillsEnabled, boolean memoryEnabled,
                             RagQueryExpander expander, RagQueryExpander.Config expansionConfig,
                             NeuralReranker neuralReranker, NeuralReranker.Config neuralConfig) {
    this(docsRoot, skills, memory, mcp, skillsEnabled, memoryEnabled, expander, expansionConfig,
        neuralReranker, neuralConfig, new RagCorpus(), EmbeddingClient.Config.disabled());
  }

  public AjentDocRetriever(Path docsRoot, SkillsKnowledgeSource skills,
                             MemoryKnowledgeSource memory, KnowledgeSource mcp,
                             boolean skillsEnabled, boolean memoryEnabled,
                             RagQueryExpander expander, RagQueryExpander.Config expansionConfig,
                             NeuralReranker neuralReranker, NeuralReranker.Config neuralConfig,
                             RagCorpus docs, EmbeddingClient.Config embeddingConfig) {
    this.docsRoot = docsRoot;
    this.skills = skills;
    this.memory = memory;
    this.mcp = mcp;
    this.skillsEnabled = skillsEnabled;
    this.memoryEnabled = memoryEnabled;
    this.expander = expander;
    this.expansionConfig = expansionConfig;
    this.neuralReranker = neuralReranker;
    this.neuralConfig = neuralConfig;
    this.docs = docs;
    this.embeddingConfig = embeddingConfig;
  }

  @Override public synchronized HostServices.DocResponse retrieve(HostServices.DocQuery query) {
    try {
      if (!docsBuilt) {
        docsBuilt = true;
        if (docsRoot != null && Files.isDirectory(docsRoot)) docs.build(docsRoot, embeddingConfig);
      }
      var router = new KnowledgeRouter();
      CorpusKnowledgeSource docsSource = new CorpusKnowledgeSource("docs", docs, embeddingConfig);
      boolean haveDocs = docs.chunkCount() > 0;
      if (haveDocs) router.add(docsSource);
      if (skillsEnabled && skills != null) router.add(skills);
      if (memoryEnabled && memory != null) router.add(memory);
      if (mcp != null) router.add(mcp);
      if (router.sourceCount() == 0) return new HostServices.DocResponse(List.of(), "",
          "no knowledge configured. Set AJENT_DOCS_DIR to a folder of documents "
              + "(markdown/text/etc.), create ./docs, install skills, or store memories to give "
              + "search_docs something to retrieve from.");

      int limit = query.limit();
      int pool = Math.max(limit * 5, 30);
      int variantCount = 0;
      RagContext context;
      if (expander != null && expansionConfig != null && haveDocs) {
        List<String> queries = expander.expand(expansionConfig, query.query());
        variantCount = Math.max(0, queries.size() - 1);
        var fused = new ArrayList<>(docsSource.retrieveFused(queries, pool));
        var rest = new KnowledgeRouter();
        if (skillsEnabled && skills != null) rest.add(skills);
        if (memoryEnabled && memory != null) rest.add(memory);
        if (mcp != null) rest.add(mcp);
        if (rest.sourceCount() > 0) fused.addAll(rest.retrieve(query.query(), pool));
        context = RagContext.fromHits(query.query(), fused);
      } else {
        context = RagContext.fromHits(query.query(), router.retrieve(query.query(), pool));
      }
      boolean neural = neuralReranker != null && neuralConfig != null;
      var pipeline = new RagPipeline().add(new RagPipeline.RerankStage(
          neural ? Math.max(limit * 3, 12) : Math.max(limit * 2, 8), RagReranker.Weights.DEFAULT));
      if (neural) pipeline.add(new RagPipeline.NeuralRerankStage(neuralReranker,
          Math.max(limit * 2, 8), neuralConfig));
      context = pipeline.add(new RagPipeline.MmrStage(limit, .75))
          .add(new RagPipeline.CompressStage(600)).run(context);

      String mode = (haveDocs && docs.hasEmbeddings() ? "hybrid+ctx" : "BM25-only") + ", "
          + (neural ? "neural-reranked" : "reranked");
      if (variantCount > 0) mode += ", +" + variantCount + " query variants";
      mode += ", confidence " + String.format(Locale.ROOT, "%.2f", context.confidence());
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
