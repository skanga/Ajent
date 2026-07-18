package com.github.skanga.ajent.tools.attachment;

import com.github.skanga.ajent.domain.Attachment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** AgenTTY-compatible ingestion of raw image pastes and pasted image paths. */
public final class ImagePaste {
  static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

  private ImagePaste() {}

  public static Optional<Attachment> raw(byte[] bytes, String path) {
    Objects.requireNonNull(bytes, "bytes");
    Objects.requireNonNull(path, "path");
    String mediaType = mediaType(bytes);
    if (mediaType == null) return Optional.empty();
    return Optional.of(new Attachment(
        Attachment.Kind.IMAGE, bytes, path, mediaType, "", 0, 0, bytes.length));
  }

  public static Optional<Attachment> path(String pasted, Map<String, String> environment) {
    Objects.requireNonNull(pasted, "pasted");
    Objects.requireNonNull(environment, "environment");
    String candidate = pasted.strip();
    if (candidate.isEmpty() || candidate.indexOf('\n') >= 0 || candidate.length() > 4096) {
      return Optional.empty();
    }
    if (candidate.length() >= 2
        && (candidate.charAt(0) == '\'' || candidate.charAt(0) == '"')
        && candidate.charAt(candidate.length() - 1) == candidate.charAt(0)) {
      candidate = candidate.substring(1, candidate.length() - 1);
    }
    if (candidate.startsWith("file://") && candidate.length() > "file://".length()) {
      candidate = candidate.substring("file://".length());
    }
    candidate = normalize(candidate, environment.getOrDefault("HOME", ""));
    if (candidate.isEmpty()) return Optional.empty();
    try {
      Path file = Path.of(candidate);
      if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) {
        return Optional.empty();
      }
      byte[] bytes = Files.readAllBytes(file);
      return raw(bytes, file.toString());
    } catch (IOException | RuntimeException exception) {
      return Optional.empty();
    }
  }

  static String mediaType(byte[] bytes) {
    if (bytes.length >= 8
        && unsigned(bytes[0]) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
        && bytes[3] == 'G' && unsigned(bytes[4]) == 0x0d && unsigned(bytes[5]) == 0x0a
        && unsigned(bytes[6]) == 0x1a && unsigned(bytes[7]) == 0x0a) return "image/png";
    if (bytes.length >= 3 && unsigned(bytes[0]) == 0xff
        && unsigned(bytes[1]) == 0xd8 && unsigned(bytes[2]) == 0xff) return "image/jpeg";
    if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F'
        && bytes[3] == '8' && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a') {
      return "image/gif";
    }
    if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
        && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B'
        && bytes[11] == 'P') return "image/webp";
    return null;
  }

  private static String normalize(String candidate, String home) {
    String source = candidate;
    var normalized = new StringBuilder(source.length() + home.length());
    if (source.startsWith("~/") && !home.isEmpty()) {
      normalized.append(home);
      source = source.substring(1);
    }
    for (int index = 0; index < source.length(); index++) {
      char current = source.charAt(index);
      if (current == '\\' && index + 1 < source.length()) current = source.charAt(++index);
      normalized.append(current);
    }
    return normalized.toString();
  }

  private static int unsigned(byte value) { return value & 0xff; }
}
