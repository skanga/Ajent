package com.github.skanga.ajent.tools.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic feature-fusion reranking, extractive compression, and MMR. */
public final class RagReranker {
  public record Weights(double fused, double termCoverage, double proximity,
                        double pathMatch, double phraseMatch) {
    public static final Weights DEFAULT = new Weights(.40, .25, .15, .10, .10);
  }

  private record Ranked(RagCorpus.Hit hit, double score, int position) {}
  private record Span(int start, int end) {}

  private RagReranker() {}

  /** Returns lowercase alphanumeric terms of at least two characters, in encounter order. */
  public static List<String> queryTerms(String query) {
    return List.copyOf(new LinkedHashSet<>(RagAlgorithms.tokenize(query)));
  }

  public static List<RagCorpus.Hit> rerank(String query, List<RagCorpus.Hit> hits, int outputLimit) {
    return rerank(query, hits, outputLimit, Weights.DEFAULT);
  }

  public static List<RagCorpus.Hit> rerank(String query, List<RagCorpus.Hit> hits, int outputLimit,
                                           Weights weights) {
    if (hits.isEmpty() || outputLimit <= 0) return List.of();
    List<String> queryTerms = queryTerms(query);
    Set<String> querySet = Set.copyOf(queryTerms);
    String phrase = normalizedPhrase(query);
    int size = hits.size();
    double[] fused = new double[size];
    double[] coverage = new double[size];
    double[] proximity = new double[size];
    double[] path = new double[size];
    double[] phraseMatch = new double[size];

    for (int index = 0; index < size; index++) {
      RagCorpus.Hit hit = hits.get(index);
      fused[index] = hit.score();
      RagChunk chunk = hit.chunk();
      if (chunk == null) continue;
      List<String> chunkTerms = RagAlgorithms.tokenize(chunk.text());
      if (!queryTerms.isEmpty()) {
        var present = new HashSet<String>();
        for (String term : chunkTerms) if (querySet.contains(term)) present.add(term);
        coverage[index] = (double) present.size() / queryTerms.size();
      }
      proximity[index] = proximity(queryTerms, querySet, chunkTerms, coverage[index]);
      String lowerPath = chunk.path().toLowerCase(Locale.ROOT);
      if (queryTerms.stream().anyMatch(lowerPath::contains)) path[index] = 1;
      if (!phrase.isEmpty() && chunk.text().toLowerCase(Locale.ROOT).contains(phrase))
        phraseMatch[index] = 1;
    }

    normalize(fused);
    normalize(coverage);
    normalize(proximity);
    var ranked = new ArrayList<Ranked>(size);
    for (int index = 0; index < size; index++) {
      double score = weights.fused() * fused[index]
          + weights.termCoverage() * coverage[index]
          + weights.proximity() * proximity[index]
          + weights.pathMatch() * path[index]
          + weights.phraseMatch() * phraseMatch[index];
      ranked.add(new Ranked(hits.get(index), score, index));
    }
    ranked.sort(Comparator.comparingDouble(Ranked::score).reversed()
        .thenComparingInt(Ranked::position));
    return ranked.stream().limit(Math.min(outputLimit, size))
        .map(value -> new RagCorpus.Hit(value.hit().chunk(), value.score(), value.hit().source())).toList();
  }

  /** Returns the best contiguous sentence span without rewriting source bytes. */
  public static String compress(String query, String text, int targetCharacters) {
    if (text.isEmpty()) return "";
    if (text.length() <= targetCharacters) return text;
    List<Span> spans = sentenceSpans(text);
    if (spans.isEmpty()) return text.substring(0, Math.max(0, Math.min(targetCharacters, text.length())));
    Set<String> querySet = Set.copyOf(queryTerms(query));
    double[] scores = new double[spans.size()];
    for (int index = 0; index < spans.size(); index++) {
      Span span = spans.get(index);
      String segment = text.substring(span.start(), span.end());
      int hits = 0;
      for (String term : RagAlgorithms.tokenize(segment)) if (querySet.contains(term)) hits++;
      scores[index] = hits > 0 ? hits / (1 + segment.length() / 200.0) : 0;
    }
    int seed = 0;
    for (int index = 1; index < spans.size(); index++)
      if (scores[index] > scores[seed]) seed = index;
    if (scores[seed] <= 0)
      return text.substring(0, Math.max(0, Math.min(targetCharacters, text.length())));

    int low = seed;
    int high = seed;
    while (true) {
      boolean canLeft = low > 0;
      boolean canRight = high + 1 < spans.size();
      if (!canLeft && !canRight) break;
      double leftScore = canLeft ? scores[low - 1] : -1;
      double rightScore = canRight ? scores[high + 1] : -1;
      boolean growLeft = canLeft && (!canRight || leftScore >= rightScore);
      int nextLow = growLeft ? low - 1 : low;
      int nextHigh = growLeft ? high : high + 1;
      if (spanLength(spans, nextLow, nextHigh) > targetCharacters) {
        if (growLeft && canRight && spanLength(spans, low, high + 1) <= targetCharacters) {
          high++;
          continue;
        }
        if (!growLeft && canLeft && spanLength(spans, low - 1, high) <= targetCharacters) {
          low--;
          continue;
        }
        break;
      }
      low = nextLow;
      high = nextHigh;
    }
    int start = spans.get(low).start();
    int end = spans.get(high).end();
    while (start < end && isTrimmedWhitespace(text.charAt(start))) start++;
    return text.substring(start, end);
  }

  /** Greedily balances score relevance against Jaccard similarity to selected hits. */
  public static List<RagCorpus.Hit> mmrDiversify(List<RagCorpus.Hit> hits, int outputLimit,
                                                 double lambda) {
    if (hits.isEmpty() || outputLimit <= 0) return List.of();
    if (hits.size() <= outputLimit) return List.copyOf(hits);
    double balance = Math.clamp(lambda, 0, 1);
    var terms = new ArrayList<Set<String>>(hits.size());
    for (RagCorpus.Hit hit : hits) terms.add(hit.chunk() == null
        ? Set.of() : Set.copyOf(RagAlgorithms.tokenize(hit.chunk().text())));
    double maxRelevance = hits.stream().mapToDouble(RagCorpus.Hit::score).max().orElse(0);
    if (maxRelevance <= 0) maxRelevance = 1;
    boolean[] used = new boolean[hits.size()];
    double[] similarityToSelected = new double[hits.size()];
    var selected = new ArrayList<RagCorpus.Hit>(outputLimit);
    while (selected.size() < outputLimit) {
      double bestMmr = -1e18;
      int best = -1;
      for (int index = 0; index < hits.size(); index++) {
        if (used[index]) continue;
        double relevance = hits.get(index).score() / maxRelevance;
        double mmr = balance * relevance - (1 - balance) * similarityToSelected[index];
        if (mmr > bestMmr) {
          bestMmr = mmr;
          best = index;
        }
      }
      if (best < 0) break;
      used[best] = true;
      selected.add(hits.get(best));
      for (int index = 0; index < hits.size(); index++) if (!used[index])
        similarityToSelected[index] = Math.max(similarityToSelected[index],
            jaccard(terms.get(index), terms.get(best)));
    }
    return List.copyOf(selected);
  }

  private static double proximity(List<String> queryTerms, Set<String> querySet,
                                  List<String> chunkTerms, double coverage) {
    if (queryTerms.size() < 2) return coverage;
    int best = Integer.MAX_VALUE;
    int lastPosition = -1;
    String lastTerm = "";
    for (int position = 0; position < chunkTerms.size(); position++) {
      String term = chunkTerms.get(position);
      if (!querySet.contains(term)) continue;
      if (lastPosition >= 0 && !term.equals(lastTerm)) best = Math.min(best, position - lastPosition);
      lastPosition = position;
      lastTerm = term;
    }
    return best == Integer.MAX_VALUE ? 0 : 1.0 / (1 + best);
  }

  private static String normalizedPhrase(String query) {
    var result = new StringBuilder();
    boolean space = false;
    for (int index = 0; index < query.length(); index++) {
      char character = Character.toLowerCase(query.charAt(index));
      if (character < 128 && Character.isLetterOrDigit(character)) {
        result.append(character);
        space = false;
      } else if (!space && !result.isEmpty()) {
        result.append(' ');
        space = true;
      }
    }
    if (!result.isEmpty() && result.charAt(result.length() - 1) == ' ')
      result.setLength(result.length() - 1);
    return result.toString();
  }

  private static void normalize(double[] values) {
    if (values.length == 0) return;
    double low = values[0];
    double high = values[0];
    for (double value : values) {
      low = Math.min(low, value);
      high = Math.max(high, value);
    }
    double span = high - low;
    if (span <= 0) {
      double normalized = high > 0 ? 1 : 0;
      java.util.Arrays.fill(values, normalized);
      return;
    }
    for (int index = 0; index < values.length; index++) values[index] = (values[index] - low) / span;
  }

  private static List<Span> sentenceSpans(String text) {
    var spans = new ArrayList<Span>();
    int start = 0;
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      if (character != '.' && character != '!' && character != '?' && character != '\n') continue;
      int end = index + 1;
      while (end < text.length() && isTrailingSentenceCharacter(text.charAt(end))) end++;
      if (end > start) spans.add(new Span(start, end));
      start = end;
      index = end - 1;
    }
    if (start < text.length()) spans.add(new Span(start, text.length()));
    return spans;
  }

  private static int spanLength(List<Span> spans, int low, int high) {
    return spans.get(high).end() - spans.get(low).start();
  }

  private static boolean isTrailingSentenceCharacter(char character) {
    return character == '"' || character == ')' || character == '\''
        || character == ' ' || character == '\t';
  }

  private static boolean isTrimmedWhitespace(char character) {
    return character == ' ' || character == '\n' || character == '\t' || character == '\r';
  }

  private static double jaccard(Set<String> left, Set<String> right) {
    if (left.isEmpty() && right.isEmpty()) return 1;
    if (left.isEmpty() || right.isEmpty()) return 0;
    Set<String> smaller = left.size() <= right.size() ? left : right;
    Set<String> larger = left.size() <= right.size() ? right : left;
    int intersection = 0;
    for (String term : smaller) if (larger.contains(term)) intersection++;
    int union = left.size() + right.size() - intersection;
    return union == 0 ? 0 : (double) intersection / union;
  }
}
