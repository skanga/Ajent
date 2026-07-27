package com.github.skanga.ajent.tools.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RagCorpusFilesystemTest {
  @TempDir Path temporaryDirectory;

  @Test void addsUpdatesAndRemovesDocumentsWithoutAFullBuild() {
    var corpus = new RagCorpus();
    int first = corpus.addDocument("k8s.md", "kubernetes deployment replicas scaling\n");
    int second = corpus.addDocument("db.md", "database transactions isolation levels\n");
    assertThat(first).isPositive();
    assertThat(second).isPositive();
    assertThat(corpus.chunkCount()).isEqualTo(first + second);
    assertThat(corpus.search("kubernetes scaling", 5).getFirst().chunk().path()).isEqualTo("k8s.md");

    assertThat(corpus.addDocument("k8s.md", "kubernetes deployment pods containers NEW CONTENT\n"))
        .isEqualTo(first);
    assertThat(corpus.chunkCount()).isEqualTo(first + second);
    assertThat(corpus.search("NEW CONTENT", 5).getFirst().chunk().path()).isEqualTo("k8s.md");
    assertThat(corpus.removeDocument("db.md")).isEqualTo(second);
    assertThat(corpus.removeDocument("missing.md")).isZero();
    assertThat(corpus.search("database transactions", 5))
        .noneMatch(hit -> hit.chunk().path().equals("db.md"));
    assertThat(corpus.addDocument("empty.md", "")).isZero();
  }

  @Test void buildsFromMemoryByReplacingExistingContent() {
    var corpus = new RagCorpus();
    corpus.addDocument("old.md", "obsolete phrase");
    assertThat(corpus.buildFromMemory(List.of(
        new RagCorpus.Document("one.md", "alpha knowledge"),
        new RagCorpus.Document("empty.md", ""),
        new RagCorpus.Document("two.md", "beta knowledge")))).isEqualTo(2);
    assertThat(corpus.search("obsolete", 5)).isEmpty();
    assertThat(corpus.search("beta", 5).getFirst().chunk().path()).isEqualTo("two.md");
  }

  @Test void folderCacheRoundTripsAndRefreshesChangedFiles() throws IOException {
    write("k8s.md", "# K8s\n\nkubernetes deployment replicas pods scaling\n");
    write("db.md", "# DB\n\ndatabase transactions isolation btree indexes\n");
    write("auth.md", "# Auth\n\noauth tokens authentication authorization scopes\n");

    var first = new RagCorpus();
    first.build(temporaryDirectory);
    assertThat(first.chunkCount()).isPositive();
    assertThat(temporaryDirectory.resolve(".ajent_rag_cache.bin")).exists();
    assertThat(first.search("kubernetes scaling", 3).getFirst().chunk().path()).isEqualTo("k8s.md");

    var cached = new RagCorpus();
    cached.build(temporaryDirectory);
    assertThat(cached.chunkCount()).isEqualTo(first.chunkCount());
    assertThat(cached.search("oauth authorization", 3).getFirst().chunk().path()).isEqualTo("auth.md");

    write("db.md", "# DB\n\ndatabase WALWALWAL write ahead logging recovery checkpoint durability\n");
    var refreshed = new RagCorpus();
    refreshed.build(temporaryDirectory);
    assertThat(refreshed.search("write ahead logging checkpoint", 3).getFirst().chunk().path())
        .isEqualTo("db.md");
    assertThat(refreshed.search("btree isolation", 5))
        .noneMatch(hit -> hit.chunk().path().equals("db.md") && hit.chunk().text().contains("btree"));
    assertThat(refreshed.search("kubernetes pods", 3).getFirst().chunk().path()).isEqualTo("k8s.md");
  }

  @Test void indexesOnlyKnowledgeExtensionsAndPrunesHiddenDirectories() throws IOException {
    write("notes.TXT", "visible unique knowledge");
    write("source.java", "excluded source token");
    Files.createDirectories(temporaryDirectory.resolve(".hidden"));
    Files.writeString(temporaryDirectory.resolve(".hidden/secret.md"), "hidden secret token");
    Files.write(temporaryDirectory.resolve(".ajent_rag_cache.bin"), new byte[] {1, 2, 3});

    var corpus = new RagCorpus();
    corpus.build(temporaryDirectory);
    assertThat(corpus.search("visible unique", 3)).isNotEmpty();
    assertThat(corpus.search("excluded source", 3)).isEmpty();
    assertThat(corpus.search("hidden secret", 3)).isEmpty();
  }

  @Test void missingFolderBuildsAnEmptyCorpusAndFlushWithoutRootIsSafe() throws IOException {
    var corpus = new RagCorpus();
    corpus.setChunks(List.of(new RagChunk("old", 1, 1, "old")));
    corpus.build(temporaryDirectory.resolve("missing"));
    assertThat(corpus.chunkCount()).isZero();
    corpus.flushCache();
  }

  @Test void cachePersistsAndRestoresTheNativeHnswTrailer() {
    var chunks = List.of(
        new RagChunk("a.md", 1, 1, "alpha", "", new float[] {1, 0}, null),
        new RagChunk("b.md", 1, 1, "beta", "", new float[] {0, 1}, null));
    var graph = new HnswIndex();
    graph.build(List.of(0, 1), chunks.stream().map(RagChunk::embedding).toList());

    RagCorpusCache.write(temporaryDirectory, chunks, graph);
    RagCorpusCache.LoadResult restored = RagCorpusCache.loadState(temporaryDirectory);

    assertThat(restored.signature()).isEqualTo(RagCorpusCache.signature(chunks)).isNotZero();
    assertThat(restored.embeddingDimension()).isEqualTo(2);
    assertThat(restored.graph().search(new float[] {0, 1}, 1).getFirst().id()).isEqualTo(1);
    assertThat(RagCorpusCache.load(temporaryDirectory)).hasSize(2);
    RagCorpusCache.write(temporaryDirectory, chunks);
    assertThat(RagCorpusCache.loadState(temporaryDirectory).graph().isEmpty()).isTrue();
  }

  private void write(String relative, String body) throws IOException {
    Files.writeString(temporaryDirectory.resolve(relative), body);
  }
}
