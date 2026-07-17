package com.github.skanga.ajent.tools.rag;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class RagCorpus {
  private static final int DOCUMENT_CAP = 4 * 1024 * 1024;
  private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(".md", ".markdown", ".txt",
      ".text", ".rst", ".org", ".adoc", ".asciidoc", ".csv", ".tsv", ".json", ".yaml",
      ".yml", ".html", ".htm", ".tex");

  public record Hit(RagChunk chunk, double score) {}
  public record Document(String path, String body) {}
  private List<RagChunk> chunks = List.of();
  private Bm25Index bm25 = RagAlgorithms.buildBm25(List.of());
  private Path root;

  public void setChunks(List<RagChunk> value) {
    chunks = List.copyOf(value);
    bm25 = RagAlgorithms.buildBm25(chunks);
  }
  public int chunkCount() { return chunks.size(); }
  public boolean hasEmbeddings() { return chunks.stream().anyMatch(chunk -> chunk.embedding().length > 0); }

  /** Builds a folder-backed corpus, reusing AgenTTY v3 cache entries when size and mtime match. */
  public void build(Path value) {
    root = value;
    var rebuilt = new ArrayList<RagChunk>();
    if (!Files.isDirectory(value)) {
      setChunks(rebuilt);
      return;
    }
    Map<String, RagCorpusCache.CachedFile> cached = RagCorpusCache.load(value);
    try {
      Files.walkFileTree(value, new SimpleFileVisitor<>() {
        @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
          if (!directory.equals(value) && directory.getFileName().toString().startsWith("."))
            return FileVisitResult.SKIP_SUBTREE;
          return FileVisitResult.CONTINUE;
        }

        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
          if (!attributes.isRegularFile() || !isDocument(file)) return FileVisitResult.CONTINUE;
          String relative = value.relativize(file).toString();
          RagCorpusCache.CachedFile entry = cached.get(relative);
          long modified = attributes.lastModifiedTime().toMillis();
          if (entry != null && entry.size() == attributes.size() && entry.modified() == modified
              && !entry.chunks().isEmpty()) {
            rebuilt.addAll(entry.chunks());
          } else {
            String body = readDocument(file);
            if (!body.isEmpty()) rebuilt.addAll(DocumentChunker.chunk(relative, body));
          }
          return FileVisitResult.CONTINUE;
        }

        @Override public FileVisitResult visitFileFailed(Path file, IOException exception) {
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException ignored) {
      // AgenTTY treats traversal failures as a partial or empty corpus.
    }
    setChunks(rebuilt);
    flushCache();
  }

  /** Adds or replaces one document and rebuilds the lexical index. */
  public int addDocument(String path, String body) {
    removeDocument(path);
    List<RagChunk> added = DocumentChunker.chunk(path, body);
    if (added.isEmpty()) return 0;
    var updated = new ArrayList<>(chunks);
    updated.addAll(added);
    setChunks(updated);
    return added.size();
  }

  /** Removes every chunk from a source path and returns the removed count. */
  public int removeDocument(String path) {
    int before = chunks.size();
    List<RagChunk> retained = chunks.stream().filter(chunk -> !chunk.path().equals(path)).toList();
    int removed = before - retained.size();
    if (removed > 0) setChunks(retained);
    return removed;
  }

  /** Replaces the corpus with caller-owned in-memory documents. */
  public int buildFromMemory(List<Document> documents) {
    root = null;
    var replacement = new ArrayList<RagChunk>();
    for (Document document : documents) if (!document.body().isEmpty())
      replacement.addAll(DocumentChunker.chunk(document.path(), document.body()));
    setChunks(replacement);
    return replacement.size();
  }

  public void flushCache() {
    if (root != null) RagCorpusCache.write(root, chunks);
  }

  public List<Hit> search(String query, int limit) {
    if (chunks.isEmpty() || query.isEmpty() || limit <= 0) return List.of();
    List<Integer> ranked = RagAlgorithms.searchBm25(bm25, query, Math.max(limit * 4, limit)).stream()
        .map(RagAlgorithms.Score::document).toList();
    return RagAlgorithms.reciprocalRankFusion(List.of(ranked), 60, limit).stream()
        .map(score -> new Hit(chunks.get(score.document()), score.score())).toList();
  }

  public List<Hit> searchFused(List<String> queries, int limit) {
    if (queries.isEmpty() || limit <= 0) return List.of();
    var lists = new ArrayList<List<Integer>>();
    for (String query : queries) lists.add(RagAlgorithms.searchBm25(bm25, query,
        Math.max(limit * 4, limit)).stream().map(RagAlgorithms.Score::document).toList());
    return RagAlgorithms.reciprocalRankFusion(lists, 60, limit).stream()
        .map(score -> new Hit(chunks.get(score.document()), score.score())).toList();
  }

  private static boolean isDocument(Path path) {
    String name = path.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot >= 0 && DOCUMENT_EXTENSIONS.contains(name.substring(dot).toLowerCase(Locale.ROOT));
  }

  private static String readDocument(Path path) {
    try (InputStream input = Files.newInputStream(path)) {
      return new String(input.readNBytes(DOCUMENT_CAP), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      return "";
    }
  }
}
