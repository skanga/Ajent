package com.github.skanga.ajent.tools.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RagAlgorithms {
  public record Score(int document, double score) {}
  private RagAlgorithms() {}

  public static Bm25Index buildBm25(List<RagChunk> chunks) {
    var postings = new HashMap<String, List<Bm25Index.Posting>>();
    int[] lengths = new int[chunks.size()];
    long total = 0;
    for (int document = 0; document < chunks.size(); document++) {
      var tokens = new ArrayList<>(tokenize(chunks.get(document).text()));
      tokens.addAll(tokenize(chunks.get(document).context()));
      lengths[document] = tokens.size();
      total += tokens.size();
      var frequencies = new HashMap<String, Integer>();
      tokens.forEach(token -> frequencies.merge(token, 1, Integer::sum));
      int id = document;
      frequencies.forEach((term, frequency) -> postings.computeIfAbsent(term,
          ignored -> new ArrayList<>()).add(new Bm25Index.Posting(id, frequency)));
    }
    return new Bm25Index(postings, lengths, chunks.isEmpty() ? 0
        : (double) total / chunks.size(), chunks.size());
  }

  public static List<Score> searchBm25(Bm25Index index, String query, int limit) {
    if (index.documentCount() == 0 || limit <= 0) return List.of();
    var scores = new HashMap<Integer, Double>();
    int[] lengths = index.documentLengths();
    for (String term : tokenize(query)) {
      List<Bm25Index.Posting> postings = index.postings().get(term);
      if (postings == null || postings.isEmpty()) continue;
      double frequency = postings.size();
      double idf = Math.max(0, Math.log((index.documentCount() - frequency + .5)
          / (frequency + .5) + 1));
      for (Bm25Index.Posting posting : postings) {
        double tf = posting.termFrequency();
        double average = index.averageDocumentLength() > 0 ? index.averageDocumentLength() : 1;
        double norm = tf * 2.5 / (tf + 1.5 * (.25 + .75 * lengths[posting.document()] / average));
        scores.merge(posting.document(), idf * norm, Double::sum);
      }
    }
    return scores.entrySet().stream().map(entry -> new Score(entry.getKey(), entry.getValue()))
        .sorted(Comparator.comparingDouble(Score::score).reversed().thenComparingInt(Score::document))
        .limit(limit).toList();
  }

  public static List<Score> reciprocalRankFusion(List<List<Integer>> rankedLists,
      double constant, int limit) {
    var scores = new HashMap<Integer, Double>();
    for (List<Integer> list : rankedLists) for (int rank = 0; rank < list.size(); rank++)
      scores.merge(list.get(rank), 1.0 / (constant + rank + 1), Double::sum);
    return scores.entrySet().stream().map(entry -> new Score(entry.getKey(), entry.getValue()))
        .sorted(Comparator.comparingDouble(Score::score).reversed().thenComparingInt(Score::document))
        .limit(Math.max(0, limit)).toList();
  }

  public static double cosine(float[] left, float[] right) {
    if (left.length == 0 || left.length != right.length) return 0;
    double dot = 0;
    double leftNorm = 0;
    double rightNorm = 0;
    for (int index = 0; index < left.length; index++) {
      dot += left[index] * right[index];
      leftNorm += left[index] * left[index];
      rightNorm += right[index] * right[index];
    }
    return leftNorm <= 0 || rightNorm <= 0 ? 0 : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
  }

  static List<String> tokenize(String text) {
    var result = new ArrayList<String>();
    var token = new StringBuilder();
    for (int index = 0; index <= text.length(); index++) {
      char character = index < text.length() ? text.charAt(index) : ' ';
      if (character < 128 && Character.isLetterOrDigit(character)) token.append(Character.toLowerCase(character));
      else {
        if (token.length() >= 2) result.add(token.toString());
        token.setLength(0);
      }
    }
    return result;
  }
}
