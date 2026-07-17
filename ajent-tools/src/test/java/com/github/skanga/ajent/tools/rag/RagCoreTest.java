package com.github.skanga.ajent.tools.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RagCoreTest {
  @Test
  void portsChunkerAndContextualBreadcrumbCases() {
    String body = "# Introduction\nThis document explains widgets.\n\n## Installation\n"
        + "Run the installer.\n\n## Usage\nInvoke the widget.\n";
    List<RagChunk> chunks = DocumentChunker.chunk("manual.md", body);
    assertThat(chunks).isNotEmpty().allSatisfy(chunk -> {
      assertThat(chunk.path()).isEqualTo("manual.md");
      assertThat(chunk.lineStart()).isPositive();
      assertThat(chunk.lineEnd()).isGreaterThanOrEqualTo(chunk.lineStart());
      assertThat(chunk.context()).startsWith("manual.md");
    });
    assertThat(chunks).extracting(RagChunk::text).anyMatch(text -> text.contains("Installation"))
        .anyMatch(text -> text.contains("Usage"));

    String contextual = "# Installation\n\n## Linux\n\nRun the setup script.\n\n"
        + "## Windows\n\nDouble-click the installer.\n";
    var corpus = new RagCorpus();
    corpus.setChunks(DocumentChunker.chunk("guide.md", contextual));
    assertThat(corpus.search("linux installation", 3)).extracting(hit -> hit.chunk().text())
        .anyMatch(text -> text.contains("setup script"));
    RagChunk chunk = new RagChunk("x", 1, 1, "body", "doc.md › Section", new float[0], null);
    assertThat(chunk.embedInput()).isEqualTo("doc.md › Section\nbody");
  }

  @Test
  void portsBm25RrfCosineAndCorpusCases() {
    var chunks = List.of(new RagChunk("a", 1, 1, "the quick brown fox jumps"),
        new RagChunk("b", 1, 1, "lazy dogs sleep all afternoon"),
        new RagChunk("c", 1, 1, "pelican migration patterns over oceans"),
        new RagChunk("d", 1, 1, "compiler optimization passes and inlining"));
    Bm25Index index = RagAlgorithms.buildBm25(chunks);
    assertThat(RagAlgorithms.searchBm25(index, "pelican", 4).getFirst().document()).isEqualTo(2);
    assertThat(RagAlgorithms.searchBm25(index, "compiler inlining", 4).getFirst().document())
        .isEqualTo(3);
    assertThat(RagAlgorithms.reciprocalRankFusion(List.of(List.of(5, 2, 1, 9),
        List.of(7, 2, 3)), 60, 10)).first().extracting(RagAlgorithms.Score::document)
        .isEqualTo(2);
    assertThat(RagAlgorithms.cosine(new float[] {1, 2, 3}, new float[] {1, 2, 3}))
        .isCloseTo(1, within(1e-6));
    assertThat(RagAlgorithms.cosine(new float[] {1, 0}, new float[] {0, 1})).isZero();
    assertThat(RagAlgorithms.cosine(new float[] {1}, new float[] {1, 2})).isZero();

    var corpus = new RagCorpus();
    corpus.setChunks(List.of(new RagChunk("auth.md", 1, 2, "configure oauth tokens"),
        new RagChunk("deploy.md", 1, 2, "kubernetes deployment manifests"),
        new RagChunk("logging.md", 1, 2, "structured logging severity")));
    assertThat(corpus.hasEmbeddings()).isFalse();
    assertThat(corpus.chunkCount()).isEqualTo(3);
    assertThat(corpus.search("kubernetes deployment", 3).getFirst().chunk().path())
        .isEqualTo("deploy.md");
    assertThat(corpus.searchFused(List.of("kubernetes", "replicas deployment"), 3).getFirst()
        .chunk().path()).isEqualTo("deploy.md");
  }

  @Test
  void preservesFencedBlocksListsAndOverlapContext() {
    String code = "# Installation\n\nRun this:\n\n```bash\nnpm install something\n"
        + "npm run build\nnpm start\n```\n\nThen check output.\n";
    assertThat(DocumentChunker.chunk("install.md", code, 20, 500, 0))
        .extracting(RagChunk::text).anyMatch(text -> text.contains("```bash")
            && text.contains("npm start") && text.contains("```\n"));
    String list = "# Features\n\n- First feature\n  continuation\n- Second feature\n"
        + "- Third feature\n\n# Next Section\n";
    assertThat(DocumentChunker.chunk("features.md", list, 10, 400, 0)).isNotEmpty();
    String fenced = "# Title\n\nintro one\nintro two\nintro three\n```python\ndef a():\n"
        + "    return 1\ndef b():\n    return 2\n```\ntrailing prose\n";
    assertThat(DocumentChunker.chunk("f.md", fenced, 5, 200, 2)).extracting(RagChunk::text)
        .anyMatch(text -> text.contains("```python") && text.contains("return 2")
            && text.contains("```\n"));
  }

  @Test
  void coversEmptyLimitsTieBreaksAndZeroVectors() {
    Bm25Index empty = RagAlgorithms.buildBm25(List.of());
    assertThat(RagAlgorithms.searchBm25(empty, "x", 2)).isEmpty();
    Bm25Index noAverage = new Bm25Index(Map.of("term", List.of(new Bm25Index.Posting(0, 1))),
        new int[] {0}, 0, 1);
    assertThat(RagAlgorithms.searchBm25(noAverage, "term unknown", 1)).hasSize(1);
    assertThat(RagAlgorithms.searchBm25(noAverage, "term", 0)).isEmpty();
    assertThat(RagAlgorithms.reciprocalRankFusion(List.of(), 60, 3)).isEmpty();
    assertThat(RagAlgorithms.reciprocalRankFusion(List.of(List.of(2, 1)), 60, 0)).isEmpty();
    assertThat(RagAlgorithms.cosine(new float[0], new float[0])).isZero();
    assertThat(RagAlgorithms.cosine(new float[] {0, 0}, new float[] {1, 2})).isZero();
    assertThat(RagAlgorithms.tokenize("a B c++ D2 café")).containsExactly("d2", "caf");
    var corpus = new RagCorpus();
    assertThat(corpus.search("query", 3)).isEmpty();
    assertThat(corpus.search("", 3)).isEmpty();
    assertThat(corpus.searchFused(List.of(), 3)).isEmpty();
    assertThat(corpus.searchFused(List.of("query"), 0)).isEmpty();
    assertThat(new RagChunk("x", 1, 1, "body", "", null, null).embedding()).isEmpty();
  }

  @Test
  void capsLongUtf8BreadcrumbsAndIgnoresMalformedHeadings() {
    String document = "# " + "é".repeat(200) + "\nbody\n###not-a-heading\n"
        + "1) numbered\n  continuation\n~~~\ninside\n~~~\n";
    List<RagChunk> chunks = DocumentChunker.chunk("long.md", document, 3, 500, 1);
    assertThat(chunks).isNotEmpty();
    assertThat(chunks).allSatisfy(chunk -> assertThat(
        chunk.context().getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(256));
  }
}
