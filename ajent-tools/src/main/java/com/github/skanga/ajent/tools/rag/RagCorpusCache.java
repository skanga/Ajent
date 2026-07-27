package com.github.skanga.ajent.tools.rag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RagCorpusCache {
  static final String FILE_NAME = ".ajent_rag_cache.bin";
  private static final int MAGIC_V3 = 0x52414703;
  private static final long MAX_CACHE_BYTES = 512L * 1024 * 1024;
  private static final int MAX_RECORDS = 10_000_000;

  record CachedFile(long size, long modified, List<RagChunk> chunks) {
    CachedFile { chunks = List.copyOf(chunks); }
  }
  record LoadResult(Map<String, CachedFile> files, int embeddingDimension, long signature,
                    HnswIndex graph) {
    LoadResult { files = Map.copyOf(files); }
  }

  private RagCorpusCache() {}

  static Map<String, CachedFile> load(Path root) {
    return loadState(root).files();
  }

  static LoadResult loadState(Path root) {
    Path file = root.resolve(FILE_NAME);
    try {
      long size = Files.size(file);
      if (size <= 0 || size > MAX_CACHE_BYTES) return empty();
      ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
      if (buffer.getInt() != MAGIC_V3) return empty();
      int embeddingDimension = count(buffer);
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
      long signature = 0;
      var graph = new HnswIndex();
      if (buffer.hasRemaining()) {
        if (buffer.remaining() < Long.BYTES) return new LoadResult(result, embeddingDimension, 0,
            new HnswIndex());
        signature = buffer.getLong();
        if (!graph.deserialize(buffer)) graph = new HnswIndex();
      }
      return new LoadResult(result, embeddingDimension, signature, graph);
    } catch (IOException | RuntimeException exception) {
      return empty();
    }
  }

  static void write(Path root, List<RagChunk> chunks) {
    write(root, chunks, null);
  }

  static void write(Path root, List<RagChunk> chunks, HnswIndex graph) {
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
        modified = nativeModified(Files.getLastModifiedTime(source));
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
    if (graph != null && !graph.isEmpty()) {
      writeLong(output, signature(chunks));
      output.writeBytes(graph.serialize());
    }
    try {
      Files.write(root.resolve(FILE_NAME), output.toByteArray(), StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    } catch (IOException ignored) {
      // A cache failure never makes retrieval fail.
    }
  }

  static long signature(List<RagChunk> chunks) {
    long hash = 0xcbf29ce484222325L;
    for (RagChunk chunk : chunks) {
      hash = mix(hash, chunk.path().getBytes(StandardCharsets.UTF_8));
      hash = mixInt(hash, chunk.lineStart());
      hash = mixInt(hash, chunk.lineEnd());
      hash = mixInt(hash, chunk.embedding().length);
    }
    return hash;
  }

  /** MSVC's file_clock stores 100 ns ticks from the Windows FILETIME epoch. */
  static long nativeModified(FileTime value) {
    if (!System.getProperty("os.name", "").startsWith("Windows")) return value.toMillis();
    var instant = value.toInstant();
    return (instant.getEpochSecond() + 11_644_473_600L) * 10_000_000L
        + instant.getNano() / 100;
  }

  private static LoadResult empty() {
    return new LoadResult(Map.of(), 0, 0, new HnswIndex());
  }

  private static long mixInt(long hash, int value) {
    for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
      hash ^= (value >>> shift) & 0xffL;
      hash *= 0x100000001b3L;
    }
    return hash;
  }

  private static long mix(long hash, byte[] value) {
    for (byte item : value) {
      hash ^= item & 0xffL;
      hash *= 0x100000001b3L;
    }
    return hash;
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
