package com.github.skanga.ajent.tools.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.github.skanga.ajent.tools.fs.WorkspaceSandbox;
import com.github.skanga.ajent.tools.memory.JsonlMemoryStore;
import com.github.skanga.ajent.tools.memory.MemoryStore;
import com.github.skanga.ajent.tools.skills.SkillEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class KnowledgePipelineTest {
  @Test void corpusSourceStampsProvenanceAndSingleSourceRouterShortCircuits() {
    RagCorpus corpus = corpus();
    var source = new CorpusKnowledgeSource("docs", corpus);
    assertThat(source.name()).isEqualTo("docs");
    assertThat(source.retrieve("kubernetes deployment replicas", 5))
        .isNotEmpty().allSatisfy(hit -> assertThat(hit.source()).isSameAs(source));

    var router = new KnowledgeRouter().add(source).add(null);
    List<RagCorpus.Hit> direct = source.retrieve("kubernetes deployment", 3);
    assertThat(router.sourceCount()).isOne();
    assertThat(router.retrieve("kubernetes deployment", 3)).isEqualTo(direct);
    assertThat(router.retrieve("query", 0)).isEmpty();
    assertThat(new KnowledgeRouter().retrieve("query", 3)).isEmpty();
  }

  @Test void multiSourceRouterFusesAndKeepsProvenance() {
    var docs = new CorpusKnowledgeSource("docs", corpus());
    KnowledgeSource fake = fixed("fake", chunk("fake.md", 1, 2,
        "kubernetes notes from the fake source about replicas"));
    List<RagCorpus.Hit> hits = new KnowledgeRouter().add(docs).add(fake)
        .retrieve("kubernetes replicas", 10);
    assertThat(hits).hasSizeGreaterThanOrEqualTo(2)
        .allSatisfy(hit -> assertThat(hit.score()).isPositive());
    assertThat(hits).extracting(RagCorpus.Hit::source).contains(docs, fake);
  }

  @Test void routerCollapsesSameLogicalChunkAndReinforcesItsScore() {
    KnowledgeSource first = fixed("a", chunk("shared.md", 1, 5, "shared content"));
    KnowledgeSource second = fixed("b", chunk("shared.md", 1, 5, "shared content"));
    KnowledgeSource unique = fixed("c", chunk("unique.md", 1, 3, "unique content"));
    List<RagCorpus.Hit> hits = new KnowledgeRouter().add(first).add(second).add(unique)
        .retrieve("content", 10);
    assertThat(hits).extracting(hit -> hit.chunk().path()).containsExactly("shared.md", "unique.md");
    assertThat(hits.getFirst().score()).isGreaterThan(hits.getLast().score());
  }

  @Test void pipelineReranksCompressesAndPreservesProvenance() {
    var source = new CorpusKnowledgeSource("docs", corpus());
    RagContext seed = RagContext.fromHits("kubernetes deployment replicas",
        source.retrieve("kubernetes deployment replicas", 30));
    var pipeline = new RagPipeline()
        .add(new RagPipeline.RerankStage(2, RagReranker.Weights.DEFAULT))
        .add(new RagPipeline.CompressStage(80));
    RagContext result = pipeline.run(seed);
    assertThat(pipeline.stageCount()).isEqualTo(2);
    assertThat(result.chunks()).hasSizeLessThanOrEqualTo(2).isNotEmpty();
    RagContext.ContextChunk top = result.chunks().getFirst();
    assertThat(top.hit().chunk().path()).isEqualTo("k8s.md");
    assertThat(top.compressed()).isNotEmpty();
    assertThat(top.text()).isEqualTo(top.compressed());
    assertThat(top.compressed()).hasSizeLessThanOrEqualTo(top.hit().chunk().text().length());
    assertThat(top.hit().source()).isSameAs(source);
  }

  @Test void contextChunkFallsBackToTheFullBodyWhenCompressionIsAbsent() {
    var source = new CorpusKnowledgeSource("docs", corpus());
    RagContext context = RagContext.fromHits("kubernetes", source.retrieve("kubernetes", 1));
    assertThat(context.chunks()).isNotEmpty();
    RagContext.ContextChunk chunk = context.chunks().getFirst();
    assertThat(chunk.compressed()).isEmpty();
    assertThat(chunk.text()).isEqualTo(chunk.hit().chunk().text());
  }

  @Test void retrieveNormalizeAndMmrStagesComposeInInsertionOrder() {
    var source = new CorpusKnowledgeSource("docs", corpus());
    RagContext result = new RagPipeline()
        .add(new RagPipeline.NormalizeQueryStage(new RagPipeline.NormalizeConfig(true, true)))
        .add(new RagPipeline.RetrieveStage(source, 5))
        .add(new RagPipeline.MmrStage(1, .7))
        .run(RagContext.fromHits("  How Do I   Configure  KUBERNETES?  \n", List.of()));
    assertThat(result.query()).isEqualTo("how do i configure kubernetes?");
    assertThat(result.chunks()).hasSize(1);
    assertThat(new RagPipeline.NormalizeQueryStage(new RagPipeline.NormalizeConfig(true, false))
        .process(RagContext.fromHits("Configure  OAUTH", List.of())).query())
        .isEqualTo("configure  oauth");
    assertThat(new RagPipeline().add(null).stageCount()).isZero();
  }

  @Test void mcpResourceSourceBuildsLazilyCachesAndRefreshes() {
    var reads = new AtomicInteger();
    List<McpResourceKnowledgeSource.ResourceRef> refs = List.of(
        new McpResourceKnowledgeSource.ResourceRef("mcp://wiki/networking", "Networking"),
        new McpResourceKnowledgeSource.ResourceRef("mcp://wiki/storage", "Storage"),
        new McpResourceKnowledgeSource.ResourceRef("mcp://wiki/missing", "Missing"));
    var source = new McpResourceKnowledgeSource("mcp", () -> refs, uri -> {
      reads.incrementAndGet();
      if (uri.endsWith("networking")) return Optional.of("networking routes firewall rules ingress");
      if (uri.endsWith("storage")) return Optional.of("storage volumes snapshots replication");
      return Optional.empty();
    });
    assertThat(source.indexedChunks()).isZero();
    assertThat(source.retrieve("firewall ingress rules", 3).getFirst().chunk().path())
        .isEqualTo("mcp://wiki/networking");
    assertThat(source.indexedChunks()).isPositive();
    assertThat(reads).hasValue(3);
    source.retrieve("storage volumes", 3);
    assertThat(reads).hasValue(3);
    source.refresh();
    source.retrieve("networking", 1);
    assertThat(reads).hasValue(6);

    var empty = new McpResourceKnowledgeSource("mcp", null, null);
    assertThat(empty.retrieve("anything", 3)).isEmpty();
    assertThat(empty.indexedChunks()).isZero();
  }

  @Test void mcpResourceSourceReindexesOnGenerationChangesAndDropsRemovedResources() {
    var generation = new AtomicLong();
    var refs = new java.util.concurrent.atomic.AtomicReference<>(List.of(
        new McpResourceKnowledgeSource.ResourceRef("mcp://one", "One")));
    var source = new McpResourceKnowledgeSource("mcp", refs::get,
        uri -> Optional.of("generation aware sentinel"),
        new RagCorpus((config, texts) -> Optional.empty()),
        EmbeddingClient.Config.disabled(), generation::get);

    assertThat(source.retrieve("sentinel", 2)).singleElement()
        .satisfies(hit -> assertThat(hit.chunk().path()).isEqualTo("mcp://one"));
    refs.set(List.of());
    generation.incrementAndGet();
    assertThat(source.retrieve("sentinel", 2)).isEmpty();
    assertThat(source.indexedChunks()).isZero();
  }

  @Test void metadataFiltersMatchOriginalCompositionRules() {
    RagChunk api = chunk("api/auth.md", 1, 10, "oauth", Map.of("type", "api", "category", "security"));
    RagChunk tutorial = chunk("tutorials/intro.md", 1, 10, "intro",
        Map.of("type", "tutorial", "category", "basics"));
    RagChunk users = chunk("api/users.md", 1, 10, "users",
        Map.of("type", "api", "category", "management"));
    assertThat(RagFilters.metadataEquals("type", "api")).accepts(api, users).rejects(tutorial);
    assertThat(RagFilters.metadataContains("category", "SECURITY")).accepts(api).rejects(tutorial);
    assertThat(RagFilters.pathContains("api/")).accepts(api, users).rejects(tutorial);
    assertThat(RagFilters.allOf(java.util.Arrays.asList(RagFilters.metadataEquals("type", "api"),
        RagFilters.metadataContains("category", "secur"), null))).accepts(api).rejects(users);
    assertThat(RagFilters.anyOf(java.util.Arrays.asList(RagFilters.metadataEquals("type", "tutorial"),
        RagFilters.metadataContains("category", "management"), null)))
        .rejects(api).accepts(tutorial, users);
  }

  @Test void skillsAndBothMemoryScopesAreLazyKnowledgeSources(@TempDir Path root) throws Exception {
    Path home = Files.createDirectories(root.resolve("home"));
    Path work = Files.createDirectories(root.resolve("work"));
    Path skillFile = work.resolve(".ajent/skills/pdf/SKILL.md");
    Files.createDirectories(skillFile.getParent());
    Files.writeString(skillFile, "---\nname: pdf\ndescription: Extract tabular PDF data\n---\n"
        + "Use pdfplumber for tables and OCR fallback.\n");
    var engine = new SkillEngine(home, work, new WorkspaceSandbox(work, work, home));
    var skills = new SkillsKnowledgeSource(engine);
    assertThat(skills.retrieve("pdf tables", 3)).first().satisfies(hit -> {
      assertThat(hit.source()).isSameAs(skills);
      assertThat(hit.chunk().path()).isEqualTo("skill://pdf/SKILL.md");
    });

    var store = new JsonlMemoryStore(home, work);
    store.append(new MemoryStore.AppendRequest("production database uses serializable isolation",
        "user", false, List.of("postgres"), ""));
    store.append(new MemoryStore.AppendRequest("project deploys through blue green releases",
        "project", false, List.of("delivery"), ""));
    var memories = new MemoryKnowledgeSource(store);
    assertThat(memories.retrieve("serializable postgres", 3).getFirst()).satisfies(hit -> {
      assertThat(hit.source()).isSameAs(memories);
      assertThat(hit.chunk().path()).startsWith("memory://user/");
    });
    assertThat(memories.retrieve("blue green delivery", 3).getFirst().chunk().path())
        .startsWith("memory://project/");
    store.append(new MemoryStore.AppendRequest("unique canary rollout fact", "project", false,
        List.of(), ""));
    assertThat(memories.retrieve("unique canary", 3)).isNotEmpty();
  }

  private static RagCorpus corpus() {
    var corpus = new RagCorpus();
    corpus.setChunks(List.of(
        chunk("k8s.md", 1, 10, "kubernetes deployment scales replicas across the cluster. "
            + "Containers and pods are orchestrated by the control plane. "
            + "A deployment manifest declares the desired replica count."),
        chunk("net.md", 1, 10, "tcp handshake establishes a reliable byte stream over ip."),
        chunk("db.md", 1, 10, "the database stores rows in tables indexed by a btree.")));
    return corpus;
  }

  private static KnowledgeSource fixed(String name, RagChunk chunk) {
    return new KnowledgeSource() {
      @Override public String name() { return name; }
      @Override public List<RagCorpus.Hit> retrieve(String query, int limit) {
        return limit <= 0 ? List.of() : List.of(new RagCorpus.Hit(chunk, 1, this));
      }
    };
  }

  private static RagChunk chunk(String path, int start, int end, String text) {
    return chunk(path, start, end, text, Map.of());
  }

  private static RagChunk chunk(String path, int start, int end, String text,
                                Map<String, String> metadata) {
    return new RagChunk(path, start, end, text, "", new float[0], metadata);
  }
}
