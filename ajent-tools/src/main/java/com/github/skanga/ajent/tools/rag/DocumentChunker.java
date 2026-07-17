package com.github.skanga.ajent.tools.rag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DocumentChunker {
  private record Heading(int level, String title) {}
  private static final class Context {
    private boolean inFence;
    private int listIndent = -1;
    private final List<Heading> headings = new ArrayList<>();
    private Context copy() {
      var copy = new Context(); copy.inFence = inFence; copy.listIndent = listIndent;
      copy.headings.addAll(headings); return copy;
    }
  }
  private DocumentChunker() {}

  public static List<RagChunk> chunk(String path, String body) {
    return chunk(path, body, 40, 1600, 4);
  }

  public static List<RagChunk> chunk(String path, String body, int maxLines, int maxCharacters,
      int overlapLines) {
    String[] lines = body.split("\n", -1);
    var output = new ArrayList<RagChunk>();
    var context = new Context();
    int cursor = 0;
    while (cursor < lines.length) {
      int begin = cursor;
      int characters = 0;
      int taken = 0;
      Context chunkContext = context.copy();
      while (cursor < lines.length) {
        int length = lines[cursor].length() + 1;
        boolean overflow = taken >= maxLines && !chunkContext.inFence
            || characters + length > maxCharacters;
        if (overflow && taken > 0) break;
        if (taken > 0 && safeBreak(lines[cursor], chunkContext) && !chunkContext.inFence) break;
        update(lines[cursor], chunkContext);
        characters += length;
        taken++;
        cursor++;
        if (taken >= maxLines && !chunkContext.inFence || characters >= maxCharacters) break;
      }
      int end = cursor;
      var text = new StringBuilder(characters);
      for (int index = begin; index < end; index++) text.append(lines[index]).append('\n');
      if (!text.toString().isBlank()) output.add(new RagChunk(path, begin + 1, end, text.toString(),
          breadcrumb(path, chunkContext), new float[0], Map.of()));
      int next = end < lines.length && overlapLines > 0 && end > begin + overlapLines
          ? end - overlapLines : end;
      if (next <= begin) next = begin + 1;
      for (int index = begin; index < next && index < end; index++) update(lines[index], context);
      cursor = next;
    }
    return output;
  }

  private static boolean safeBreak(String line, Context context) {
    if (context.inFence) return false;
    if (line.isBlank() || heading(line) != null) return true;
    if (context.listIndent >= 0) return listItem(line) && indent(line) <= context.listIndent;
    return false;
  }

  private static void update(String line, Context context) {
    if (fence(line)) { context.inFence = !context.inFence; return; }
    if (context.inFence) return;
    Heading heading = heading(line);
    if (heading != null) {
      while (!context.headings.isEmpty()
          && context.headings.getLast().level() >= heading.level()) context.headings.removeLast();
      context.headings.add(heading);
      context.listIndent = -1;
      return;
    }
    if (listItem(line)) context.listIndent = indent(line);
    else if (line.isBlank()) context.listIndent = -1;
  }

  private static Heading heading(String line) {
    String stripped = line.stripLeading();
    int level = 0;
    while (level < stripped.length() && stripped.charAt(level) == '#') level++;
    if (level < 1 || level > 6 || level < stripped.length()
        && !Character.isWhitespace(stripped.charAt(level))) return null;
    String title = stripped.substring(level).strip().replaceFirst("#+$", "").strip();
    return title.isEmpty() ? null : new Heading(level, title);
  }
  private static boolean fence(String line) {
    String stripped = line.stripLeading();
    return stripped.startsWith("```") || stripped.startsWith("~~~");
  }
  private static boolean listItem(String line) {
    String stripped = line.stripLeading();
    if (stripped.matches("[-*+] .*")) return true;
    return stripped.matches("\\d+[.)] .*" );
  }
  private static int indent(String line) { return line.length() - line.stripLeading().length(); }
  private static String breadcrumb(String path, Context context) {
    var output = new StringBuilder(path);
    context.headings.forEach(heading -> output.append(" › ").append(heading.title()));
    byte[] bytes = output.toString().getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= 256) return output.toString();
    int end = 256;
    while (end > 0 && (bytes[end] & 0xC0) == 0x80) end--;
    return new String(bytes, 0, end, StandardCharsets.UTF_8);
  }
}
