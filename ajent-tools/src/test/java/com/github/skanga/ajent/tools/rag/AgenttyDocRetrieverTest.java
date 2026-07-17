package com.github.skanga.ajent.tools.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.tools.fs.WorkspaceSandbox;
import com.github.skanga.ajent.tools.host.HostServices;
import com.github.skanga.ajent.tools.memory.JsonlMemoryStore;
import com.github.skanga.ajent.tools.memory.MemoryStore;
import com.github.skanga.ajent.tools.skills.SkillEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AgenttyDocRetrieverTest {
  @Test void runsCanonicalFusedRerankMmrCompressFunnel(@TempDir Path root) throws Exception {
    Path home = Files.createDirectories(root.resolve("home"));
    Path work = Files.createDirectories(root.resolve("work"));
    Path docs = Files.createDirectories(work.resolve("docs"));
    Files.writeString(docs.resolve("deploy.md"), "# Deploy\n\n"
        + "kubernetes deployments use replicas pods and rolling updates. "
        + "This second sentence contains deployment verification details.\n");
    Path skill = work.resolve(".agentty/skills/release/SKILL.md");
    Files.createDirectories(skill.getParent());
    Files.writeString(skill, "---\nname: release\ndescription: Release engineering\n---\n"
        + "Use blue green release procedures.\n");
    var store = new JsonlMemoryStore(home, work);
    store.append(new MemoryStore.AppendRequest("production uses canary deployment waves", "project",
        false, List.of("release"), ""));
    var engine = new SkillEngine(home, work, new WorkspaceSandbox(work, work, home));
    var retriever = new AgenttyDocRetriever(docs, new SkillsKnowledgeSource(engine),
        new MemoryKnowledgeSource(store), null, true, true);

    HostServices.DocResponse response = retriever.retrieve(new HostServices.DocQuery("deployment release", 4));
    assertThat(response.error()).isEmpty();
    assertThat(response.hits()).isNotEmpty().hasSizeLessThanOrEqualTo(4)
        .allSatisfy(hit -> {
          assertThat(hit.text()).isNotEmpty();
          assertThat(hit.score()).isNotNegative();
          assertThat(hit.source()).isIn("docs", "skills", "memory");
        });
    assertThat(response.hits()).extracting(HostServices.DocHit::source)
        .containsAnyOf("docs", "skills", "memory");
    assertThat(response.mode()).contains("BM25-only", "reranked", "confidence", docs.toString());
  }

  @Test void reportsNoKnowledgeWhenEverySourceIsDisabled(@TempDir Path root) {
    var retriever = new AgenttyDocRetriever(root.resolve("missing"), null, null, null, false, false);
    HostServices.DocResponse response = retriever.retrieve(new HostServices.DocQuery("anything", 6));
    assertThat(response.hits()).isEmpty();
    assertThat(response.error()).contains("no knowledge configured", "AGENTTY_DOCS_DIR");
  }

  @Test void degradesBackendFailureToSearchDocsError(@TempDir Path root) {
    KnowledgeSource broken = new KnowledgeSource() {
      @Override public String name() { return "broken"; }
      @Override public List<RagCorpus.Hit> retrieve(String query, int limit) {
        throw new IllegalStateException("backend exploded");
      }
    };
    var retriever = new AgenttyDocRetriever(root.resolve("missing"), null, null, broken, false, false);
    assertThat(retriever.retrieve(new HostServices.DocQuery("anything", 2)).error())
        .isEqualTo("search_docs failed: backend exploded");
  }
}
