package com.github.skanga.ajent.tools.rag;

import java.util.List;
import java.util.Map;

public record Bm25Index(Map<String, List<Posting>> postings, int[] documentLengths,
                        double averageDocumentLength, int documentCount, boolean stemmed) {
  public Bm25Index {
    postings = Map.copyOf(postings);
    documentLengths = documentLengths.clone();
  }
  public Bm25Index(Map<String, List<Posting>> postings, int[] documentLengths,
                   double averageDocumentLength, int documentCount) {
    this(postings, documentLengths, averageDocumentLength, documentCount, false);
  }
  @Override public int[] documentLengths() { return documentLengths.clone(); }
  public record Posting(int document, int termFrequency) {}
}
