package com.github.skanga.ajent.tools.fs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Metadata-preserving atomic replacement used by filesystem tools and review rollback. */
final class AtomicFileWriter {
  private AtomicFileWriter() {}

  static void write(Path target, byte[] content) throws IOException {
    Path staged = stage(target, content);
    try {
      replace(staged, target);
    } finally {
      Files.deleteIfExists(staged);
    }
  }

  static Path stage(Path target, byte[] content) throws IOException {
    Path parent = target.toAbsolutePath().normalize().getParent();
    if (parent == null) throw new IOException("target has no parent: " + target);
    Files.createDirectories(parent);
    Path staged = Files.createTempFile(parent, "." + target.getFileName(), ".ajent-stage");
    boolean existing = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
    try {
      if (existing) {
        Files.copy(target, staged, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
      }
      try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING)) {
        ByteBuffer buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      return staged;
    } catch (IOException | RuntimeException exception) {
      Files.deleteIfExists(staged);
      throw exception;
    }
  }

  static void replace(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
