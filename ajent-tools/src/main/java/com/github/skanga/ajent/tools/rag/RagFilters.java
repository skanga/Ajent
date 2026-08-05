package com.github.skanga.ajent.tools.rag;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** Metadata and path predicates matching Ajent's document filters. */
public final class RagFilters {
  private RagFilters() {}

  public static Predicate<RagChunk> metadataEquals(String key, String value) {
    return chunk -> value.equals(chunk.metadata().get(key));
  }

  public static Predicate<RagChunk> metadataContains(String key, String substring) {
    String needle = substring.toLowerCase(Locale.ROOT);
    return chunk -> {
      String value = chunk.metadata().get(key);
      return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    };
  }

  public static Predicate<RagChunk> pathContains(String substring) {
    return chunk -> chunk.path().contains(substring);
  }

  public static Predicate<RagChunk> allOf(List<Predicate<RagChunk>> filters) {
    return chunk -> {
      for (Predicate<RagChunk> filter : filters) if (filter != null && !filter.test(chunk)) return false;
      return true;
    };
  }

  public static Predicate<RagChunk> anyOf(List<Predicate<RagChunk>> filters) {
    return chunk -> {
      for (Predicate<RagChunk> filter : filters) if (filter != null && filter.test(chunk)) return true;
      return false;
    };
  }
}
