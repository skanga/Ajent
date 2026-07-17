package com.github.skanga.ajent.tools.rag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RagCorpusCache {
  static final String FILE_NAME = ".agentty_rag_cache.bin";
  private static final int MAGIC_V3 = 0x52414703;
  private static final long MAX_CACHE_BYTES = 512L * 1024 * 1024;
  private static final int MAX_RECORDS = 10_000_000;

  record CachedFile(long size, long modified, List<RagChunk> chunks) {
    CachedFile { chunks = List.copyOf(chunks); }
  }

  private RagCorpusCache() {}

  static Map<String, CachedFile> load(Path root) {
    Path file = root.resolve(FILE_NAME);
    try {
      long size = Files.size(file);
      if (size <= 0 || size > MAX_CACHE_BYTES) return Map.of();
      ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
      if (buffer.getInt() != MAGIC_V3) return Map.of();
      buffer.getInt(); // embedding dimension; each chunk carries its own vector too.
      int fileCount = count(buffer);
      var result = new LinkedHashMap<String, CachedFile>();
      for (int fileIndex = 0; fileIndex < fileCount; fileIndex++) {
        String path = string(buffer);
        long fileSize = buffer.getLong();
        long modified = buffer.getLong();
        int chunkCount = count(buffer);
        var chunks = new ArrayList<RagChunk>(chunkCount);
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
          int lineStart = buffer.getInt();
          int lineEnd = buffer.getInt();
          String text = string(buffer);
          String context = string(buffer);
          int embeddingLength = count(buffer);
          if (embeddingLength > buffer.remaining() / Float.BYTES) throw new IllegalArgumentException();
          float[] embedding = new float[embeddingLength];
          for (int index = 0; index < embeddingLength; index++) embedding[index] = buffer.getFloat();
          chunks.add(new RagChunk(path, lineStart, lineEnd, text, context, embedding, Map.of()));
        }
        result.put(path, new CachedFile(fileSize, modified, chunks));
      }
      return Map.copyOf(result);
    } catch (IOException | RuntimeException exception) {
      return Map.of();
    }
  }

  static void write(Path root, List<RagChunk> chunks) {
    var byPath = new LinkedHashMap<String, List<RagChunk>>();
    for (RagChunk chunk : chunks)
      byPath.computeIfAbsent(chunk.path(), ignored -> new ArrayList<>()).add(chunk);
    var output = new ByteArrayOutputStream();
    writeInt(output, MAGIC_V3);
    writeInt(output, chunks.stream().mapToInt(chunk -> chunk.embedding().length).filter(v -> v > 0)
        .findFirst().orElse(0));
    writeInt(output, byPath.size());
    for (Map.Entry<String, List<RagChunk>> entry : byPath.entrySet()) {
      Path source = root.resolve(entry.getKey());
      long size = 0;
      long modified = 0;
      try {
        size = Files.size(source);
        modified = Files.getLastModifiedTime(source).toMillis();
      } catch (IOException ignored) {
        // Hot-added non-folder documents intentionally persist zero file metadata.
      }
      writeString(output, entry.getKey());
      writeLong(output, size);
      writeLong(output, modified);
      writeInt(output, entry.getValue().size());
      for (RagChunk chunk : entry.getValue()) {
        writeInt(output, chunk.lineStart());
        writeInt(output, chunk.lineEnd());
        writeString(output, chunk.text());
        writeString(output, chunk.context());
        float[] embedding = chunk.embedding();
        writeInt(output, embedding.length);
        for (float value : embedding) writeInt(output, Float.floatToRawIntBits(value));
      }
    }
    try {
      Files.write(root.resolve(FILE_NAME), output.toByteArray(), StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    } catch (IOException ignored) {
      // A cache failure never makes retrieval fail.
    }
  }

  private static int count(ByteBuffer buffer) {
    int value = buffer.getInt();
    if (value < 0 || value > MAX_RECORDS) throw new IllegalArgumentException();
    return value;
  }

  private static String string(ByteBuffer buffer) {
    int length = count(buffer);
    if (length > buffer.remaining()) throw new IllegalArgumentException();
    byte[] value = new byte[length];
    buffer.get(value);
    return new String(value, StandardCharsets.UTF_8);
  }

  private static void writeString(ByteArrayOutputStream output, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    writeInt(output, bytes.length);
    output.writeBytes(bytes);
  }

  private static void writeLong(ByteArrayOutputStream output, long value) {
    writeInt(output, (int) value);
    writeInt(output, (int) (value >>> 32));
  }

  private static void writeInt(ByteArrayOutputStream output, int value) {
    output.write(value);
    output.write(value >>> 8);
    output.write(value >>> 16);
    output.write(value >>> 24);
  }
}
