package com.github.skanga.ajent.tools.rag;

import java.io.ByteArrayOutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/** Pure-Java HNSW cosine index, binary-compatible with AgenTTY's native graph cache. */
public final class HnswIndex {
  private static final int MAGIC = 0x484E5301;
  private static final int MAX_ELEMENTS = 10_000_000;
  private static final int MAX_DIMENSION = 1_000_000;
  private static final int MAX_LAYERS = 64;

  public record Config(int maxNeighbors, int baseMaxNeighbors, int constructionWidth,
                       int searchWidth, double levelMultiplier, long seed) {
    public Config {
      if (maxNeighbors <= 0 || baseMaxNeighbors <= 0 || constructionWidth <= 0
          || searchWidth <= 0 || !Double.isFinite(levelMultiplier) || levelMultiplier <= 0)
        throw new IllegalArgumentException("invalid HNSW configuration");
    }

    public static Config agenttyDefaults() {
      return new Config(16, 32, 200, 64, 1.0 / 0.69314718, 0x9E3779B97F4A7C15L);
    }
  }

  public record SearchHit(int id, float similarity) {}

  private record Candidate(float similarity, int index) {}

  private static final Comparator<Candidate> BEST_FIRST = Comparator
      .comparingDouble(Candidate::similarity).reversed()
      .thenComparing(Comparator.comparingInt(Candidate::index).reversed());
  private static final Comparator<Candidate> WORST_FIRST = Comparator
      .comparingDouble(Candidate::similarity).thenComparingInt(Candidate::index);

  private static final class Node {
    private final int id;
    private final float[] vector;
    private final List<List<Integer>> links;

    private Node(int id, float[] vector, int layerCount) {
      this.id = id;
      this.vector = vector;
      links = new ArrayList<>(layerCount);
      for (int layer = 0; layer < layerCount; layer++) links.add(new ArrayList<>());
    }
  }

  private final Config config;
  private final List<Node> nodes = new ArrayList<>();
  private Random random;
  private int dimension;
  private int maxLayer = -1;
  private int entry;

  public HnswIndex() {
    this(Config.agenttyDefaults());
  }

  public HnswIndex(Config config) {
    this.config = config;
    random = new Random(config.seed());
  }

  public int size() {
    return nodes.size();
  }

  public int dimension() {
    return dimension;
  }

  public boolean isEmpty() {
    return nodes.isEmpty();
  }

  public Config config() {
    return config;
  }

  public void build(List<Integer> ids, List<float[]> embeddings) {
    clear();
    int count = Math.min(ids.size(), embeddings.size());
    for (int index = 0; index < count; index++) {
      float[] embedding = embeddings.get(index);
      if (embedding != null && embedding.length > 0) add(ids.get(index), embedding);
    }
  }

  public void add(int id, float[] vector) {
    if (vector == null || vector.length == 0) return;
    if (dimension == 0) dimension = vector.length;
    else if (vector.length != dimension) return;

    int level = randomLevel();
    var node = new Node(id, normalize(vector), level + 1);
    int currentIndex = nodes.size();
    if (nodes.isEmpty()) {
      nodes.add(node);
      maxLayer = level;
      entry = currentIndex;
      return;
    }

    nodes.add(node);
    int layerEntry = entry;
    for (int layer = maxLayer; layer > level; layer--)
      layerEntry = greedyClosest(node.vector, layerEntry, layer);

    for (int layer = Math.min(level, maxLayer); layer >= 0; layer--) {
      List<Integer> candidates = searchLayer(node.vector, layerEntry,
          config.constructionWidth(), layer);
      List<Integer> chosen = selectNeighbors(node.vector, candidates, maxLinks(layer));
      node.links.get(layer).addAll(chosen);
      for (int neighbor : chosen) {
        List<Integer> neighborLinks = nodes.get(neighbor).links.get(layer);
        neighborLinks.add(currentIndex);
        if (neighborLinks.size() > maxLinks(layer)) {
          List<Integer> pruned = selectNeighbors(nodes.get(neighbor).vector,
              neighborLinks, maxLinks(layer));
          neighborLinks.clear();
          neighborLinks.addAll(pruned);
        }
      }
      if (!candidates.isEmpty()) layerEntry = candidates.getFirst();
    }
    if (level > maxLayer) {
      maxLayer = level;
      entry = currentIndex;
    }
  }

  public List<SearchHit> search(float[] query, int count) {
    return search(query, count, 0);
  }

  public List<SearchHit> search(float[] query, int count, int width) {
    if (nodes.isEmpty() || count <= 0 || query == null || query.length != dimension)
      return List.of();
    float[] normalized = normalize(query);
    int searchWidth = Math.max(width > 0 ? width : config.searchWidth(), count);
    int layerEntry = entry;
    for (int layer = maxLayer; layer > 0; layer--)
      layerEntry = greedyClosest(normalized, layerEntry, layer);
    List<Integer> candidates = searchLayer(normalized, layerEntry, searchWidth, 0);
    var result = new ArrayList<SearchHit>(Math.min(count, candidates.size()));
    for (int index = 0; index < candidates.size() && index < count; index++) {
      Node node = nodes.get(candidates.get(index));
      result.add(new SearchHit(node.id, dot(normalized, node.vector)));
    }
    return List.copyOf(result);
  }

  /** Encodes the exact native little-endian HNSW section appended to AgenTTY RAG caches. */
  public byte[] serialize() {
    var output = new ByteArrayOutputStream();
    putInt(output, MAGIC);
    putInt(output, dimension);
    putInt(output, maxLayer);
    putInt(output, entry);
    putInt(output, nodes.size());
    for (Node node : nodes) {
      putInt(output, node.id);
      putInt(output, node.vector.length);
      for (float value : node.vector) putInt(output, Float.floatToRawIntBits(value));
      putInt(output, node.links.size());
      for (List<Integer> layer : node.links) {
        putInt(output, layer.size());
        for (int neighbor : layer) putInt(output, neighbor);
      }
    }
    return output.toByteArray();
  }

  /** Reads one native HNSW section and advances {@code input} only on success. */
  public boolean deserialize(ByteBuffer input) {
    int start = input.position();
    ByteBuffer cursor = input.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    try {
      if (cursor.getInt() != MAGIC) return failed(input, start);
      int newDimension = cursor.getInt();
      int newMaxLayer = cursor.getInt();
      int newEntry = cursor.getInt();
      int count = cursor.getInt();
      if (newDimension < 0 || newDimension > MAX_DIMENSION || newMaxLayer < -1
          || newMaxLayer >= MAX_LAYERS || count < 0 || count > MAX_ELEMENTS
          || (count == 0 ? newMaxLayer != -1 : Integer.compareUnsigned(newEntry, count) >= 0))
        return failed(input, start);

      var restored = new ArrayList<Node>(count);
      for (int nodeIndex = 0; nodeIndex < count; nodeIndex++) {
        int id = cursor.getInt();
        int vectorLength = bounded(cursor.getInt(), MAX_DIMENSION);
        if (vectorLength != newDimension || cursor.remaining() < vectorLength * Float.BYTES)
          return failed(input, start);
        float[] vector = new float[vectorLength];
        for (int index = 0; index < vectorLength; index++) vector[index] = cursor.getFloat();
        int layerCount = bounded(cursor.getInt(), MAX_LAYERS);
        if (layerCount == 0 || layerCount > newMaxLayer + 1) return failed(input, start);
        var node = new Node(id, vector, layerCount);
        for (int layer = 0; layer < layerCount; layer++) {
          int neighbors = bounded(cursor.getInt(), count);
          if (cursor.remaining() < neighbors * Integer.BYTES) return failed(input, start);
          for (int neighborIndex = 0; neighborIndex < neighbors; neighborIndex++) {
            int neighbor = cursor.getInt();
            if (Integer.compareUnsigned(neighbor, count) >= 0) return failed(input, start);
            node.links.get(layer).add(neighbor);
          }
        }
        restored.add(node);
      }
      nodes.clear();
      nodes.addAll(restored);
      dimension = newDimension;
      maxLayer = newMaxLayer;
      entry = newEntry;
      random = new Random(config.seed());
      input.position(cursor.position());
      return true;
    } catch (BufferUnderflowException | IllegalArgumentException exception) {
      return failed(input, start);
    }
  }

  private int randomLevel() {
    double sample = random.nextDouble();
    if (sample <= 0) sample = 1e-12;
    return Math.min(MAX_LAYERS - 1, (int) (-Math.log(sample) * config.levelMultiplier()));
  }

  private int greedyClosest(float[] query, int start, int layer) {
    int current = start;
    float currentSimilarity = dot(query, nodes.get(current).vector);
    boolean improved = true;
    while (improved) {
      improved = false;
      Node node = nodes.get(current);
      if (layer >= node.links.size()) break;
      for (int neighbor : node.links.get(layer)) {
        float similarity = dot(query, nodes.get(neighbor).vector);
        if (similarity > currentSimilarity) {
          currentSimilarity = similarity;
          current = neighbor;
          improved = true;
        }
      }
    }
    return current;
  }

  private List<Integer> searchLayer(float[] query, int start, int width, int layer) {
    var visited = new HashSet<Integer>(Math.max(16, width * 4));
    var frontier = new PriorityQueue<>(BEST_FIRST);
    var results = new PriorityQueue<>(WORST_FIRST);
    float startSimilarity = dot(query, nodes.get(start).vector);
    var first = new Candidate(startSimilarity, start);
    frontier.add(first);
    results.add(first);
    visited.add(start);

    while (!frontier.isEmpty()) {
      Candidate candidate = frontier.remove();
      if (!results.isEmpty() && candidate.similarity() < results.element().similarity()
          && results.size() >= width) break;
      Node node = nodes.get(candidate.index());
      if (layer >= node.links.size()) continue;
      for (int neighbor : node.links.get(layer)) {
        if (!visited.add(neighbor)) continue;
        float similarity = dot(query, nodes.get(neighbor).vector);
        if (results.size() < width || similarity > results.element().similarity()) {
          var found = new Candidate(similarity, neighbor);
          frontier.add(found);
          results.add(found);
          if (results.size() > width) results.remove();
        }
      }
    }
    var ranked = new ArrayList<>(results);
    ranked.sort(BEST_FIRST);
    return ranked.stream().map(Candidate::index).toList();
  }

  private List<Integer> selectNeighbors(float[] base, List<Integer> candidates, int count) {
    var ranked = new ArrayList<Candidate>(candidates.size());
    for (int candidate : candidates)
      ranked.add(new Candidate(dot(base, nodes.get(candidate).vector), candidate));
    ranked.sort(BEST_FIRST);
    var kept = new ArrayList<Integer>(Math.min(count, ranked.size()));
    for (Candidate candidate : ranked) {
      if (kept.size() >= count) break;
      boolean diverse = true;
      for (int previous : kept) {
        if (dot(nodes.get(candidate.index()).vector, nodes.get(previous).vector)
            > candidate.similarity()) {
          diverse = false;
          break;
        }
      }
      if (diverse) kept.add(candidate.index());
    }
    if (kept.size() < count) {
      for (Candidate candidate : ranked) {
        if (kept.size() >= count) break;
        if (!kept.contains(candidate.index())) kept.add(candidate.index());
      }
    }
    return kept;
  }

  private int maxLinks(int layer) {
    return layer == 0 ? config.baseMaxNeighbors() : config.maxNeighbors();
  }

  private void clear() {
    nodes.clear();
    dimension = 0;
    maxLayer = -1;
    entry = 0;
    random = new Random(config.seed());
  }

  private boolean failed(ByteBuffer input, int start) {
    clear();
    input.position(start);
    return false;
  }

  private static int bounded(int value, int maximum) {
    if (value < 0 || value > maximum) throw new IllegalArgumentException("invalid HNSW count");
    return value;
  }

  private static float[] normalize(float[] vector) {
    double normSquared = 0;
    for (float value : vector) normSquared += (double) value * value;
    float[] result = vector.clone();
    if (normSquared <= 0) return result;
    float inverse = (float) (1 / Math.sqrt(normSquared));
    for (int index = 0; index < result.length; index++) result[index] *= inverse;
    return result;
  }

  private static float dot(float[] left, float[] right) {
    float result = 0;
    for (int index = 0; index < Math.min(left.length, right.length); index++)
      result += left[index] * right[index];
    return result;
  }

  private static void putInt(ByteArrayOutputStream output, int value) {
    output.write(value);
    output.write(value >>> 8);
    output.write(value >>> 16);
    output.write(value >>> 24);
  }
}
