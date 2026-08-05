package com.github.skanga.ajent.domain;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Ajent-compatible attachment placeholders, wire expansion, and compact labels. */
public final class AttachmentText {
  public static final char SENTINEL = '\u0001';
  private static final int FILE_LIMIT = 256 * 1024;

  private AttachmentText() {}

  @FunctionalInterface
  public interface BodyResolver {
    byte[] body(Attachment attachment);
  }

  public static String placeholder(int index) {
    if (index < 0) throw new IllegalArgumentException("index cannot be negative");
    return SENTINEL + "ATT:" + index + SENTINEL;
  }

  public static int placeholderLengthAt(String text, int position) {
    if (position < 0 || position >= text.length() || text.charAt(position) != SENTINEL
        || position + 7 > text.length() || !text.startsWith("\u0001ATT:", position)) return 0;
    int cursor = position + 5;
    if (cursor >= text.length() || !digit(text.charAt(cursor))) return 0;
    while (cursor < text.length() && digit(text.charAt(cursor))) cursor++;
    return cursor < text.length() && text.charAt(cursor) == SENTINEL
        ? cursor + 1 - position : 0;
  }

  public static int placeholderLengthEndingAt(String text, int position) {
    if (position <= 0 || position > text.length() || text.charAt(position - 1) != SENTINEL) return 0;
    int cursor = position - 2;
    while (cursor > 0 && digit(text.charAt(cursor))) cursor--;
    if (text.charAt(cursor) != ':' || cursor < 4
        || !text.regionMatches(cursor - 4, "\u0001ATT", 0, 4)) return 0;
    int start = cursor - 4;
    int length = placeholderLengthAt(text, start);
    return length == position - start ? length : 0;
  }

  public static int placeholderIndex(String text, int position) {
    int length = placeholderLengthAt(text, position);
    if (length == 0) return -1;
    try {
      return Integer.parseInt(text.substring(position + 5, position + length - 1));
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }

  public static String expand(String text, List<Attachment> attachments) {
    return expand(text, attachments, Attachment::body);
  }

  public static String expand(
      String text, List<Attachment> attachments, BodyResolver resolver) {
    return replace(text, attachments, false, resolver);
  }

  public static String display(String text, List<Attachment> attachments) {
    return replace(text, attachments, true, Attachment::body);
  }

  public static String chipLabel(Attachment attachment) {
    return switch (attachment.kind()) {
      case FILE_REF -> "@" + filename(attachment.path());
      case SYMBOL -> "#" + attachment.name() + " \u00b7 " + filename(attachment.path())
          + ":" + attachment.lineNumber();
      case OUTPUT -> sized("Output: " + clippedCommand(attachment.name()), attachment);
      case IMAGE -> imageLabel(attachment);
      case PASTE -> pasteLabel(attachment);
    };
  }

  private static String replace(
      String text, List<Attachment> attachments, boolean chips, BodyResolver resolver) {
    var output = new StringBuilder(text.length() + attachments.size() * 64);
    int cursor = 0;
    while (cursor < text.length()) {
      if (text.charAt(cursor) == SENTINEL) {
        int length = placeholderLengthAt(text, cursor);
        if (length > 0) {
          int index = placeholderIndex(text, cursor);
          if (index >= 0 && index < attachments.size()) {
            if (chips) output.append('[').append(chipLabel(attachments.get(index))).append(']');
            else appendBody(output, attachments.get(index), resolver);
          }
          cursor += length;
          if (!chips && cursor < text.length()) output.append('\n');
          continue;
        }
        cursor++;
        continue;
      }
      output.append(text.charAt(cursor++));
    }
    return chips ? output.toString() : collapseNewlines(output);
  }

  private static void appendBody(
      StringBuilder output, Attachment attachment, BodyResolver resolver) {
    if (!output.isEmpty() && output.charAt(output.length() - 1) != '\n') output.append('\n');
    if (!output.isEmpty() && (output.length() < 2 || output.charAt(output.length() - 2) != '\n')) {
      output.append('\n');
    }
    output.append(renderBody(attachment, resolver));
    if (output.isEmpty() || output.charAt(output.length() - 1) != '\n') output.append('\n');
  }

  private static String renderBody(Attachment attachment, BodyResolver resolver) {
    String body = new String(resolver.body(attachment), StandardCharsets.UTF_8);
    return switch (attachment.kind()) {
      case PASTE -> body;
      case FILE_REF -> "// path: " + attachment.path() + "\n" + truncateFile(body);
      case SYMBOL -> symbolBody(attachment, body);
      case IMAGE -> "[image: " + (attachment.path().isEmpty() ? "<inline>" : attachment.path()) + "]";
      case OUTPUT -> "I ran:\n```sh\n" + attachment.name() + "\n```\noutput:\n```\n"
          + body + (body.isEmpty() || body.endsWith("\n") ? "" : "\n") + "```";
    };
  }

  private static String truncateFile(String body) {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= FILE_LIMIT) return body;
    int end = FILE_LIMIT;
    while (end > 0 && (bytes[end] & 0xc0) == 0x80) end--;
    return new String(bytes, 0, end, StandardCharsets.UTF_8) + "\n[\u2026 file truncated, "
        + (bytes.length - FILE_LIMIT) + " bytes elided]";
  }

  private static String symbolBody(Attachment attachment, String body) {
    int target = Math.max(1, attachment.lineNumber());
    int start = Math.max(1, target - 5);
    int end = target + 15;
    String[] lines = body.split("\n", -1);
    var result = new StringBuilder("// symbol: ").append(attachment.name()).append(" (")
        .append(attachment.path()).append(':').append(attachment.lineNumber()).append(")\n");
    for (int line = start; line <= end && line <= lines.length; line++) {
      result.append(lines[line - 1]).append('\n');
    }
    return result.toString();
  }

  private static String pasteLabel(Attachment attachment) {
    String body = new String(attachment.body(), StandardCharsets.UTF_8);
    if (attachment.lineCount() <= 1) {
      String preview = body.replaceAll("[\\n\\t\\r]+", " ").stripTrailing();
      if (preview.length() > 50) preview = preview.substring(0, 50) + "\u2026";
      return preview.isEmpty() ? "Pasted text \u00b7 " + attachment.byteCount() + " B"
          : "Pasted: " + preview;
    }
    return sized("Pasted text", attachment);
  }

  private static String sized(String prefix, Attachment attachment) {
    String bytes = attachment.byteCount() >= 1024
        ? attachment.byteCount() / 1024 + " KB" : attachment.byteCount() + " B";
    return prefix + " \u00b7 " + attachment.lineCount() + " lines \u00b7 " + bytes;
  }

  private static String imageLabel(Attachment attachment) {
    String bytes = attachment.byteCount() >= 1024
        ? attachment.byteCount() / 1024 + " KB" : attachment.byteCount() + " B";
    return "Image \u00b7 " + imageLocation(attachment.path()) + " \u00b7 "
        + (attachment.mediaType().isEmpty() ? "image" : attachment.mediaType())
        + " \u00b7 " + bytes;
  }

  private static String clippedCommand(String command) {
    String normalized = command.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    return normalized.length() > 32 ? normalized.substring(0, 32) + "\u2026" : normalized;
  }

  private static String imageLocation(String path) {
    return path.startsWith("<") ? path : filename(path);
  }

  private static String filename(String path) {
    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return slash < 0 ? path : path.substring(slash + 1);
  }

  private static String collapseNewlines(StringBuilder source) {
    var result = new StringBuilder(source.length());
    int run = 0;
    for (int index = 0; index < source.length(); index++) {
      char value = source.charAt(index);
      if (value == '\n') {
        if (++run <= 2) result.append(value);
      } else {
        run = 0;
        result.append(value);
      }
    }
    return result.toString();
  }

  private static boolean digit(char value) { return value >= '0' && value <= '9'; }
}
