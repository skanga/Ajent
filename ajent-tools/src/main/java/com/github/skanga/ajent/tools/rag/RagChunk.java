package com.github.skanga.ajent.tools.rag;

import java.util.Map;

public record RagChunk(String path, int lineStart, int lineEnd, String text, String context,
                       float[] embedding, Map<String, String> metadata) {
  public RagChunk {
    embedding = embedding == null ? new float[0] : embedding.clone();
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }
  public RagChunk(String path, int lineStart, int lineEnd, String text) {
    this(path, lineStart, lineEnd, text, "", new float[0], Map.of());
  }
  @Override public float[] embedding() { return embedding.clone(); }
  public String embedInput() { return context.isEmpty() ? text : context + '\n' + text; }
}
