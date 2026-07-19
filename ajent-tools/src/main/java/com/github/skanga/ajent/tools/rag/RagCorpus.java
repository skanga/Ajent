package com.github.skanga.ajent.tools.rag;

import com.github.skanga.ajent.provider.EnvironmentHttpClient;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RagCorpus {
  private static final int DOCUMENT_CAP = 4 * 1024 * 1024;
  private static final int EMBEDDING_BATCH = 64;
  private static final int HNSW_THRESHOLD = 2_000;
  private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(".md", ".markdown", ".txt",
      ".text", ".rst", ".org", ".adoc", ".asciidoc", ".csv", ".tsv", ".json", ".yaml",
      ".yml", ".html", ".htm", ".tex");

  public record Hit(RagChunk chunk, double score, KnowledgeSource source) {
    public Hit(RagChunk chunk, double score) { this(chunk, score, null); }
  }
  public record Document(String path, String body) {}

  private final EmbeddingClient embeddingClient;
  private List<RagChunk> chunks = List.of();
  private Bm25Index bm25 = RagAlgorithms.buildBm25(List.of());
  private HnswIndex hnsw = new HnswIndex();
  private boolean hnswBuilt;
  private int embeddingDimension;
  private Path root;

  public RagCorpus() {
    this(new JdkOllamaEmbeddingClient(EnvironmentHttpClient.builder(System.getenv())
        .connectTimeout(Duration.ofSeconds(3)).build()));
  }

  public RagCorpus(EmbeddingClient embeddingClient) {
    this.embeddingClient = embeddingClient;
  }

  public void setChunks(List<RagChunk> value) {
    chunks = List.copyOf(value);
    embeddingDimension = firstEmbeddingDimension(chunks);
    bm25 = RagAlgorithms.buildBm25(chunks);
    hnsw = new HnswIndex();
    hnswBuilt = false;
  }

  public int chunkCount() { return chunks.size(); }
  public boolean hasEmbeddings() { return embeddingDimension > 0; }
  public int embeddingDimension() { return embeddingDimension; }
  boolean hasApproximateIndex() { return hnswBuilt; }

  public void build(Path value) {
    build(value, EmbeddingClient.Config.disabled());
  }

  /** Builds a folder-backed corpus, incrementally embedding and reusing the native v3 cache. */
  public void build(Path value, EmbeddingClient.Config embedding) {
    root = value;
    if (!Files.isDirectory(value)) {
      setChunks(List.of());
      return;
    }
    RagCorpusCache.LoadResult cache = RagCorpusCache.loadState(value);
    Map<String, RagCorpusCache.CachedFile> cachedFiles = cache.files();
    var rebuilt = new ArrayList<RagChunk>();
    try {
      Files.walkFileTree(value, new SimpleFileVisitor<>() {
        @Override public FileVisitResult preVisitDirectory(Path directory,
                                                            BasicFileAttributes attributes) {
          if (!directory.equals(value) && directory.getFileName().toString().startsWith("."))
            return FileVisitResult.SKIP_SUBTREE;
          return FileVisitResult.CONTINUE;
        }

        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
          if (!attributes.isRegularFile() || !isDocument(file)) return FileVisitResult.CONTINUE;
          String relative = value.relativize(file).toString();
          RagCorpusCache.CachedFile entry = cachedFiles.get(relative);
          long modified = RagCorpusCache.nativeModified(attributes.lastModifiedTime());
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

    chunks = sanitizeEmbeddings(embedMissing(rebuilt, embedding, EMBEDDING_BATCH));
    embeddingDimension = firstEmbeddingDimension(chunks);
    bm25 = RagAlgorithms.buildBm25(chunks);
    if (embeddingDimension > 0 && chunks.size() >= HNSW_THRESHOLD
        && !cache.graph().isEmpty() && cache.graph().dimension() == embeddingDimension
        && cache.signature() != 0 && cache.signature() == RagCorpusCache.signature(chunks)) {
      hnsw = cache.graph();
      hnswBuilt = true;
    } else {
      rebuildHnsw();
    }
    flushCache();
  }

  public int addDocument(String path, String body) {
    return addDocument(path, body, EmbeddingClient.Config.disabled());
  }

  /** Adds or replaces one document, embeds it when configured, and realigns both indices. */
  public int addDocument(String path, String body, EmbeddingClient.Config embedding) {
    removeDocument(path);
    List<RagChunk> added = DocumentChunker.chunk(path, body);
    if (added.isEmpty()) return 0;
    added = embedMissing(added, embedding, Integer.MAX_VALUE);
    var updated = new ArrayList<>(chunks);
    updated.addAll(added);
    installAndRebuild(updated, false);
    return added.size();
  }

  public int removeDocument(String path) {
    int before = chunks.size();
    List<RagChunk> retained = chunks.stream().filter(chunk -> !chunk.path().equals(path)).toList();
    int removed = before - retained.size();
    if (removed > 0) installAndRebuild(retained, false);
    return removed;
  }

  public int buildFromMemory(List<Document> documents) {
    return buildFromMemory(documents, EmbeddingClient.Config.disabled());
  }

  /** Replaces the corpus with caller-owned documents and embeds in native-size batches. */
  public int buildFromMemory(List<Document> documents, EmbeddingClient.Config embedding) {
    root = null;
    var replacement = new ArrayList<RagChunk>();
    for (Document document : documents) if (!document.body().isEmpty())
      replacement.addAll(DocumentChunker.chunk(document.path(), document.body()));
    installAndRebuild(embedMissing(replacement, embedding, EMBEDDING_BATCH), true);
    return chunks.size();
  }

  public void flushCache() {
    if (root != null) RagCorpusCache.write(root, chunks, hnswBuilt ? hnsw : null);
  }

  public List<Hit> search(String query, int limit) {
    return search(query, EmbeddingClient.Config.disabled(), limit);
  }

  public List<Hit> search(String query, EmbeddingClient.Config embedding, int limit) {
    if (chunks.isEmpty() || limit <= 0) return List.of();
    var lists = new ArrayList<List<Integer>>();
    rankedLists(query, embedding, pool(limit), lists);
    return materialize(lists, limit);
  }

  public List<Hit> searchFused(List<String> queries, int limit) {
    return searchFused(queries, EmbeddingClient.Config.disabled(), limit);
  }

  public List<Hit> searchFused(List<String> queries, EmbeddingClient.Config embedding, int limit) {
    if (chunks.isEmpty() || queries.isEmpty() || limit <= 0) return List.of();
    var lists = new ArrayList<List<Integer>>();
    int pool = pool(limit);
    for (String query : queries) rankedLists(query, embedding, pool, lists);
    return materialize(lists, limit);
  }

  private void rankedLists(String query, EmbeddingClient.Config embedding, int pool,
                           List<List<Integer>> lists) {
    lists.add(RagAlgorithms.searchBm25(bm25, query, pool).stream()
        .map(RagAlgorithms.Score::document).toList());
    if (embeddingDimension == 0 || embedding.model().isEmpty()) return;
    Optional<List<float[]>> embedded = safeEmbed(embedding, List.of(query));
    if (embedded.isEmpty() || embedded.get().size() != 1
        || embedded.get().getFirst().length != embeddingDimension) return;
    float[] queryVector = embedded.get().getFirst();
    List<Integer> dense;
    if (hnswBuilt) {
      int width = Math.max(pool > Integer.MAX_VALUE / 2 ? Integer.MAX_VALUE : pool * 2, 64);
      dense = hnsw.search(queryVector, pool, width).stream().map(HnswIndex.SearchHit::id).toList();
    } else {
      var scores = new ArrayList<RagAlgorithms.Score>();
      for (int index = 0; index < chunks.size(); index++) {
        float[] vector = chunks.get(index).embedding();
        if (vector.length == embeddingDimension)
          scores.add(new RagAlgorithms.Score(index, RagAlgorithms.cosine(queryVector, vector)));
      }
      scores.sort(Comparator.comparingDouble(RagAlgorithms.Score::score).reversed()
          .thenComparingInt(RagAlgorithms.Score::document));
      dense = scores.stream().limit(pool).map(RagAlgorithms.Score::document).toList();
    }
    if (!dense.isEmpty()) lists.add(dense);
  }

  private List<Hit> materialize(List<List<Integer>> lists, int limit) {
    return RagAlgorithms.reciprocalRankFusion(lists, 60, limit).stream()
        .map(score -> new Hit(chunks.get(score.document()), score.score())).toList();
  }

  private void installAndRebuild(List<RagChunk> value, boolean enforceOneDimension) {
    chunks = List.copyOf(enforceOneDimension ? sanitizeEmbeddings(value) : value);
    embeddingDimension = firstEmbeddingDimension(chunks);
    bm25 = RagAlgorithms.buildBm25(chunks);
    rebuildHnsw();
  }

  private void rebuildHnsw() {
    hnsw = new HnswIndex();
    hnswBuilt = false;
    if (embeddingDimension == 0 || chunks.size() < HNSW_THRESHOLD) return;
    var ids = new ArrayList<Integer>(chunks.size());
    var vectors = new ArrayList<float[]>(chunks.size());
    for (int index = 0; index < chunks.size(); index++) {
      float[] vector = chunks.get(index).embedding();
      if (vector.length == embeddingDimension) {
        ids.add(index);
        vectors.add(vector);
      }
    }
    hnsw.build(ids, vectors);
    hnswBuilt = !hnsw.isEmpty();
  }

  private List<RagChunk> embedMissing(List<RagChunk> input, EmbeddingClient.Config embedding,
                                      int batchSize) {
    var result = new ArrayList<>(input);
    if (embedding.model().isEmpty() || result.isEmpty()) return result;
    var missing = new ArrayList<Integer>();
    for (int index = 0; index < result.size(); index++)
      if (result.get(index).embedding().length == 0) missing.add(index);
    int boundedBatch = Math.max(1, batchSize);
    for (int offset = 0; offset < missing.size(); offset += boundedBatch) {
      int end = Math.min(offset + boundedBatch, missing.size());
      List<Integer> indices = missing.subList(offset, end);
      List<String> texts = indices.stream().map(index -> result.get(index).embedInput()).toList();
      Optional<List<float[]>> vectors = safeEmbed(embedding, texts);
      if (vectors.isEmpty() || vectors.get().size() != texts.size()) break;
      for (int index = 0; index < indices.size(); index++) {
        int chunkIndex = indices.get(index);
        result.set(chunkIndex, withEmbedding(result.get(chunkIndex), vectors.get().get(index)));
      }
    }
    return result;
  }

  private Optional<List<float[]>> safeEmbed(EmbeddingClient.Config config, List<String> texts) {
    try {
      return embeddingClient.embed(config, texts);
    } catch (RuntimeException exception) {
      return Optional.empty();
    }
  }

  private static List<RagChunk> sanitizeEmbeddings(List<RagChunk> value) {
    int dimension = firstEmbeddingDimension(value);
    if (dimension == 0) return List.copyOf(value);
    return value.stream().map(chunk -> chunk.embedding().length == dimension ? chunk
        : withEmbedding(chunk, new float[0])).toList();
  }

  private static RagChunk withEmbedding(RagChunk chunk, float[] embedding) {
    return new RagChunk(chunk.path(), chunk.lineStart(), chunk.lineEnd(), chunk.text(),
        chunk.context(), embedding, chunk.metadata());
  }

  private static int firstEmbeddingDimension(List<RagChunk> value) {
    return value.stream().map(RagChunk::embedding).mapToInt(vector -> vector.length)
        .filter(length -> length > 0).findFirst().orElse(0);
  }

  private static int pool(int limit) {
    return (int) Math.min(Integer.MAX_VALUE, Math.max((long) limit * 8, 32));
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
