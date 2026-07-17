package com.github.skanga.ajent.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.skanga.ajent.tools.args.ArgReader;
import com.github.skanga.ajent.tools.edit.FuzzyMatcher;
import com.github.skanga.ajent.tools.runtime.FileChange;
import com.github.skanga.ajent.tools.runtime.ToolError;
import com.github.skanga.ajent.tools.runtime.ToolErrorKind;
import com.github.skanga.ajent.tools.runtime.ToolOutput;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** AgenTTY-compatible read/write/edit/list_dir implementations. */
public final class FileTools {
  private static final long MAX_READ_BYTES = 1024L * 1024L;
  private static final long MAX_WRITE_BYTES = 5L * 1024L * 1024L;
  private static final String STALE_READ = "File unchanged since last read. The content from the "
      + "earlier Read tool_result in this conversation is still current — refer to that instead "
      + "of re-reading.";

  private record ReadKey(Path path, int offset, int limit) {}
  private record Snapshot(FileTime modified, long size) {}
  private record Edit(String oldText, String newText, int line) {}

  private final WorkspaceSandbox sandbox;
  private final Map<ReadKey, FileTime> readCache = new ConcurrentHashMap<>();
  private final Map<Path, Snapshot> snapshots = new ConcurrentHashMap<>();

  public FileTools(WorkspaceSandbox sandbox) { this.sandbox = sandbox; }

  public ToolResult execute(String name, JsonNode arguments) {
    try {
      return switch (name) {
        case "read" -> read(arguments);
        case "write" -> write(arguments);
        case "edit" -> edit(arguments);
        case "list_dir" -> listDirectory(arguments);
        default -> failure(ToolErrorKind.UNKNOWN, "unknown tool: " + name);
      };
    } catch (IOException exception) {
      return failure(ToolErrorKind.IO, exception.getMessage() == null
          ? exception.getClass().getSimpleName() : exception.getMessage());
    } catch (RuntimeException exception) {
      return failure(ToolErrorKind.UNKNOWN, "tool crashed: " + exception.getMessage());
    }
  }

  private ToolResult read(JsonNode arguments) throws IOException {
    var args = new ArgReader(arguments);
    Optional<String> raw = args.requiredString("path");
    if (raw.isEmpty()) return failure(ToolErrorKind.INVALID_ARGS, "path required");
    Path path = sandbox.normalize(raw.orElseThrow());
    ToolResult denied = requireReadable(path, "read");
    if (denied != null) return denied;
    if (!Files.exists(path)) return failure(ToolErrorKind.NOT_FOUND,
        "file not found: " + path + ". Run `list_dir` on the parent directory or `glob` by name to verify.");
    if (!Files.isRegularFile(path)) return failure(ToolErrorKind.NOT_A_FILE,
        "not a regular file: " + path);
    int offset = Math.max(1, args.integer("offset", 1));
    int limit = args.integer("limit", 2000);
    if (limit <= 0) limit = 2000;
    FileTime modified = Files.getLastModifiedTime(path);
    ReadKey key = new ReadKey(path.toRealPath(), offset, limit);
    if (modified.equals(readCache.get(key))) return success(STALE_READ);
    long size = Files.size(path);
    if (size > MAX_READ_BYTES) return failure(ToolErrorKind.TOO_LARGE,
        "file is " + (size / 1024) + " KiB (> 1 MiB cap). Read in chunks via offset/limit "
            + "(or start_line/end_line) — e.g. {\"path\":\"" + path
            + "\",\"offset\":1,\"limit\":500}. For a structural overview, run `grep` for the symbols you need.");
    byte[] bytes = Files.readAllBytes(path);
    if (containsNull(bytes, Math.min(512, bytes.length))) return failure(ToolErrorKind.BINARY,
        "cannot read binary file: " + path + " (" + size
            + " bytes). Use the bash tool with `file`, `hexdump`, or similar.");
    String content = new String(bytes, StandardCharsets.UTF_8);
    List<String> lines = lines(content);
    var output = new StringBuilder();
    int start = Math.min(offset - 1, lines.size());
    int end = Math.min(lines.size(), start + limit);
    for (int index = start; index < end; index++) output.append(lines.get(index)).append('\n');
    int shown = end - start;
    if (offset > 1 || shown < lines.size()) {
      output.append("\n[showing lines ").append(offset).append('-')
          .append(offset + shown - 1).append(" of ").append(lines.size());
      int remaining = lines.size() - (offset + shown - 1);
      if (remaining > 0) output.append("; ").append(remaining)
          .append(" more — pass offset=").append(offset + shown)
          .append(" (or start_line=").append(offset + shown).append(") for the next chunk");
      output.append(']');
    }
    String description = args.string("display_description", "");
    if (!description.isEmpty()) output.insert(0, description + "\n");
    readCache.put(key, modified);
    snapshots.put(path.toRealPath(), new Snapshot(modified, size));
    return success(output.toString());
  }

  private ToolResult write(JsonNode arguments) throws IOException {
    var args = new ArgReader(arguments);
    Optional<String> raw = args.requiredString("path");
    if (raw.isEmpty()) return failure(ToolErrorKind.INVALID_ARGS,
        "path required (received keys: " + describeKeys(arguments) + ")");
    Path path = sandbox.normalize(raw.orElseThrow());
    ToolResult denied = requireWritable(path, "write");
    if (denied != null) return denied;
    if (!args.has("content")) return failure(ToolErrorKind.INVALID_ARGS,
        "content required — no `content` field or known alias was present. Received keys: "
            + describeKeys(arguments) + ". Re-run with the full file body in the `content` field.");
    String content = args.string("content", "");
    byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
    if (contentBytes.length > MAX_WRITE_BYTES) return failure(ToolErrorKind.TOO_LARGE,
        "write body is " + (contentBytes.length / 1024)
            + " KiB (> 5 MiB cap). Split into multiple writes or stage the file via bash (cat > file <<EOF).");
    boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    if (exists && Files.isDirectory(path)) return failure(ToolErrorKind.NOT_A_FILE,
        "'" + path + "' is a directory — write needs a file path.");
    Path parent = path.getParent();
    if (parent != null && Files.exists(parent) && !Files.isDirectory(parent)) {
      return failure(ToolErrorKind.NOT_A_DIRECTORY,
          "parent of '" + path + "' exists but is not a directory.");
    }
    String original = exists && Files.isRegularFile(path)
        ? new String(Files.readAllBytes(path), StandardCharsets.UTF_8) : "";
    if (exists && original.equals(content)) {
      return success("File already matches content — no changes written.");
    }
    FileChange change = change(path, original, content);
    atomicWrite(path, contentBytes);
    FileTime modified = Files.getLastModifiedTime(path);
    snapshots.put(path.toRealPath(), new Snapshot(modified, contentBytes.length));
    String description = args.string("display_description", "");
    String prefix = description.isEmpty() ? "" : description + "\n";
    return success(new ToolOutput(prefix + (exists ? "Overwrote " : "Created ") + path
        + " (" + change.added() + "+ " + change.removed() + "-)", Optional.of(change)));
  }

  private ToolResult edit(JsonNode arguments) throws IOException {
    var args = new ArgReader(arguments);
    Optional<String> raw = args.requiredString("path");
    if (raw.isEmpty()) return failure(ToolErrorKind.INVALID_ARGS, "path required");
    Path path = sandbox.normalize(raw.orElseThrow());
    ToolResult denied = requireWritable(path, "edit");
    if (denied != null) return denied;
    if (!Files.exists(path)) return failure(ToolErrorKind.NOT_FOUND,
        "file not found: " + path + ". To create a new file use the `write` tool; `edit` only modifies existing files.");
    if (!Files.isRegularFile(path)) return failure(ToolErrorKind.NOT_A_FILE,
        "not a regular file: " + path + " (is it a directory or symlink to one?)");
    byte[] bytes = Files.readAllBytes(path);
    if (containsNull(bytes, Math.min(512, bytes.length))) return failure(ToolErrorKind.BINARY,
        "refusing to edit binary file: " + path + " (contains NUL bytes — likely an image, archive, or compiled artifact).");
    List<Edit> edits = parseEdits(arguments, args);
    if (edits.isEmpty()) return failure(ToolErrorKind.INVALID_ARGS, "edits required");
    String original = new String(bytes, StandardCharsets.UTF_8);
    String updated = original;
    int applied = 0;
    int noops = 0;
    for (Edit edit : edits) {
      if (edit.oldText().equals(edit.newText())) { noops++; continue; }
      var match = edit.line() > 0
          ? FuzzyMatcher.find(updated, edit.oldText(), edit.newText(), edit.line() - 1)
          : FuzzyMatcher.find(updated, edit.oldText(), edit.newText());
      if (!match.ok()) {
        String detail = match.count() > 1
            ? "old_text appears " + match.count() + " times in " + path
            + "; include more surrounding lines or a line hint to make it unique."
            : "old_text was not found in " + path;
        return failure(match.count() > 1 ? ToolErrorKind.AMBIGUOUS : ToolErrorKind.NO_MATCH, detail);
      }
      String replacement = match.adjustedNewText().isEmpty()
          ? edit.newText() : match.adjustedNewText();
      updated = updated.substring(0, match.position()) + replacement
          + updated.substring(match.position() + match.length());
      applied++;
    }
    if (applied == 0) return success("No edits were applied — all " + noops
        + " edit(s) had identical old_text and new_text (nothing to change). File on disk is unchanged.");
    FileChange change = change(path, original, updated);
    atomicWrite(path, updated.getBytes(StandardCharsets.UTF_8));
    FileTime modified = Files.getLastModifiedTime(path);
    snapshots.put(path.toRealPath(), new Snapshot(modified, Files.size(path)));
    String description = args.string("display_description", "");
    String prefix = description.isEmpty() ? "" : description + "\n\n";
    return success(new ToolOutput(prefix + "Edited " + path + " (" + change.added()
        + "+ " + change.removed() + "-)", Optional.of(change)));
  }

  private ToolResult listDirectory(JsonNode arguments) throws IOException {
    var args = new ArgReader(arguments);
    String raw = args.string("path", ".");
    Path root = sandbox.normalize(raw);
    ToolResult denied = requireWritable(root, "list_dir");
    if (denied != null) return denied;
    if (!Files.exists(root)) return failure(ToolErrorKind.NOT_FOUND, "directory not found: " + raw);
    if (!Files.isDirectory(root)) return failure(ToolErrorKind.NOT_A_DIRECTORY,
        "not a directory: " + raw);
    boolean recursive = args.bool("recursive", false);
    int maxDepth = Math.clamp(args.integer("max_depth", 3), 1, 16);
    var output = new StringBuilder();
    int count = list(root, recursive, maxDepth, 0, output, new int[] {0});
    if (count == 0) return success("empty directory");
    String description = args.string("display_description", "");
    return success((description.isEmpty() ? "" : description + "\n") + output);
  }

  private int list(
      Path directory, boolean recursive, int maxDepth, int depth,
      StringBuilder output, int[] total) throws IOException {
    var entries = new ArrayList<Path>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      stream.forEach(entries::add);
    }
    entries.sort(Comparator
        .comparing((Path path) -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        .thenComparing(path -> path.getFileName().toString()));
    for (Path entry : entries) {
      if (total[0] > 1000) { output.append("[>1000 entries, truncated]\n"); break; }
      String name = entry.getFileName().toString();
      output.append("  ".repeat(depth));
      if (Files.isSymbolicLink(entry)) {
        output.append(name).append(" -> ").append(Files.readSymbolicLink(entry)).append('\n');
      } else if (Files.isDirectory(entry)) {
        output.append(name).append("/\n");
        total[0]++;
        boolean hiddenNested = depth > 0 && name.startsWith(".");
        if (recursive && depth < maxDepth && !hiddenNested
            && !WorkspaceSandbox.shouldSkipDirectory(name)) {
          list(entry, true, maxDepth, depth + 1, output, total);
        }
        continue;
      } else if (Files.isRegularFile(entry)) {
        output.append(name).append("  ").append(formatSize(Files.size(entry))).append('\n');
      }
      total[0]++;
    }
    return total[0];
  }

  private List<Edit> parseEdits(JsonNode arguments, ArgReader args) {
    var result = new ArrayList<Edit>();
    JsonNode values = arguments == null ? null : arguments.path("edits");
    if (values != null && values.isArray()) {
      for (JsonNode value : values) {
        var edit = new ArgReader(value);
        Optional<String> oldText = edit.requiredString("old_string");
        if (oldText.isEmpty()) continue;
        result.add(new Edit(oldText.orElseThrow(), edit.string("new_string", ""),
            edit.integer("line", 0)));
      }
    } else if (args.has("old_string")) {
      result.add(new Edit(args.string("old_string", ""), args.string("new_string", ""),
          args.integer("line", 0)));
    }
    return result;
  }

  private ToolResult requireReadable(Path path, String tool) {
    if (sandbox.isReadable(path)) return null;
    return failure(ToolErrorKind.OUT_OF_WORKSPACE, "tool '" + tool + "' refused: '" + path
        + "' is outside the workspace root '" + sandbox.workspaceRoot()
        + "' and not under any skill directory.");
  }

  private ToolResult requireWritable(Path path, String tool) {
    if (sandbox.isWithin(path)) return null;
    return failure(ToolErrorKind.OUT_OF_WORKSPACE, "tool '" + tool + "' refused: '" + path
        + "' is outside the workspace root '" + sandbox.workspaceRoot() + "'.");
  }

  private static void atomicWrite(Path target, byte[] content) throws IOException {
    Path parent = target.getParent();
    if (parent != null) Files.createDirectories(parent);
    Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
    try {
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static FileChange change(Path path, String before, String after) {
    List<String> oldLines = splitForDiff(before);
    List<String> newLines = splitForDiff(after);
    int common = lcsLength(oldLines, newLines);
    return new FileChange(path.toAbsolutePath().normalize().toString(),
        newLines.size() - common, oldLines.size() - common, before, after);
  }

  private static int lcsLength(List<String> first, List<String> second) {
    int[] prior = new int[second.size() + 1];
    for (String left : first) {
      int[] current = new int[second.size() + 1];
      for (int index = 1; index <= second.size(); index++) {
        current[index] = left.equals(second.get(index - 1))
            ? prior[index - 1] + 1 : Math.max(prior[index], current[index - 1]);
      }
      prior = current;
    }
    return prior[second.size()];
  }

  private static List<String> splitForDiff(String value) {
    return List.of(value.split("\\n", -1));
  }

  private static List<String> lines(String value) {
    if (value.isEmpty()) return List.of();
    String[] raw = value.split("\\n", -1);
    int count = value.endsWith("\n") ? raw.length - 1 : raw.length;
    var result = new ArrayList<String>(count);
    for (int index = 0; index < count; index++) {
      String line = raw[index];
      result.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
    }
    return result;
  }

  private static boolean containsNull(byte[] bytes, int limit) {
    for (int index = 0; index < limit; index++) if (bytes[index] == 0) return true;
    return false;
  }

  private static String describeKeys(JsonNode arguments) {
    if (arguments == null || !arguments.isObject() || arguments.isEmpty()) {
      return "(no object / empty)";
    }
    var keys = new ArrayList<String>();
    arguments.fieldNames().forEachRemaining(keys::add);
    return String.join(", ", keys);
  }

  private static String formatSize(long bytes) {
    if (bytes < 1024) return bytes + "B";
    if (bytes < 1024L * 1024L) return "%.1fK".formatted(bytes / 1024.0);
    if (bytes < 1024L * 1024L * 1024L) return "%.1fM".formatted(bytes / (1024.0 * 1024.0));
    return "%.1fG".formatted(bytes / (1024.0 * 1024.0 * 1024.0));
  }

  private static ToolResult success(String text) { return success(new ToolOutput(text)); }
  private static ToolResult success(ToolOutput output) { return new ToolResult.Success(output); }
  private static ToolResult failure(ToolErrorKind kind, String detail) {
    return new ToolResult.Failure(new ToolError(kind, detail));
  }
}
