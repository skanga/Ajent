package com.github.skanga.ajent.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.skanga.ajent.tools.args.ArgReader;
import com.github.skanga.ajent.tools.fs.WorkspaceSandbox;
import com.github.skanga.ajent.tools.runtime.ToolError;
import com.github.skanga.ajent.tools.runtime.ToolErrorKind;
import com.github.skanga.ajent.tools.runtime.ToolOutput;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/** Ajent-compatible grep, glob, and definition-search bodies. */
public final class SearchTools {
  private static final int PER_PAGE = 20;
  private static final int CONTEXT_LINES = 2;
  private static final int MAX_MATCHES = 500;
  private static final int MAX_OUTPUT_BYTES = 20_000;
  private static final long MAX_FILE_BYTES = 8L * 1024L * 1024L;
  private static final Set<String> BINARY_EXTENSIONS = Set.of(
      ".exe", ".dll", ".lib", ".a", ".o", ".obj", ".pdb", ".so", ".dylib",
      ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico", ".tiff",
      ".pdf", ".zip", ".tar", ".gz", ".bz2", ".xz", ".7z", ".rar",
      ".mp3", ".mp4", ".wav", ".avi", ".mov", ".webm", ".flac", ".ogg",
      ".ttf", ".otf", ".woff", ".woff2", ".eot", ".class", ".jar", ".pyc",
      ".pyo", ".wasm", ".bin", ".iso", ".dat", ".db", ".sqlite", ".sqlite3",
      ".dmg", ".deb", ".rpm", ".msi", ".lock");
  private static final Set<String> CODE_EXTENSIONS = Set.of(".cpp", ".hpp", ".c", ".h", ".cc",
      ".hh", ".cxx", ".hxx", ".py", ".js", ".ts", ".jsx", ".tsx", ".go", ".rs",
      ".java", ".kt", ".rb", ".swift", ".zig", ".lua");

  private record FileMatches(Path path, List<String> lines, List<Integer> matchLines) {}
  private record LineRange(int start, int end) {}
  private final WorkspaceSandbox sandbox;

  public SearchTools(WorkspaceSandbox sandbox) { this.sandbox = sandbox; }

  public ToolResult execute(String name, JsonNode arguments) {
    try {
      return switch (name) {
        case "grep" -> grep(arguments);
        case "glob" -> glob(arguments);
        case "find_definition" -> findDefinition(arguments);
        default -> failure(ToolErrorKind.UNKNOWN, "unknown tool: " + name);
      };
    } catch (IOException exception) {
      return failure(ToolErrorKind.IO, detail(exception));
    } catch (RuntimeException exception) {
      return failure(ToolErrorKind.UNKNOWN, "tool crashed: " + detail(exception));
    }
  }

  private ToolResult glob(JsonNode arguments) throws IOException {
    var args = new ArgReader(arguments);
    String pattern = args.requiredString("pattern").orElse("");
    if (pattern.isBlank()) return invalidPattern(pattern);
    Path root = root(args, "glob");
    if (root == null) return outside(args.string("path", "."), "glob");
    boolean wildcard = pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0
        || pattern.indexOf('[') >= 0;
    var entries = new ArrayList<Path>();
    try (Stream<Path> paths = walk(root)) {
      paths.filter(path -> !path.equals(root)).filter(path -> {
        String name = fileName(path);
        return wildcard ? GlobMatcher.matches(pattern, name) : name.contains(pattern);
      }).limit(501).forEach(entries::add);
    }
    if (entries.isEmpty()) return success("no matches. Try a different pattern, or `list_dir` "
        + "on parent directories to see what exists.");
    entries.sort(Comparator.<Path, Boolean>comparing(Files::isDirectory).reversed()
        .thenComparing(Path::toString));
    var output = new StringBuilder("Found ").append(entries.size()).append(" file(s):\n");
    for (Path path : entries) {
      output.append(path);
      if (Files.isDirectory(path)) output.append('/');
      else if (Files.isSymbolicLink(path)) output.append('@');
      else {
        long size = Files.size(path);
        if (size > 0) output.append("  ").append(formatSize(size));
      }
      output.append('\n');
    }
    if (entries.size() > 500) output.append("[>500, truncated]\n");
    return described(args, output.toString());
  }

  private ToolResult grep(JsonNode arguments) throws IOException {
    var args = new ArgReader(arguments);
    String expression = args.requiredString("pattern").orElse("");
    if (expression.isBlank()) return invalidPattern(expression);
    Path root = root(args, "grep");
    if (root == null) return outside(args.string("path", "."), "grep");
    int flags = args.bool("case_sensitive", false) ? 0 : Pattern.CASE_INSENSITIVE;
    Pattern regex;
    try {
      regex = Pattern.compile(isLiteral(expression) ? Pattern.quote(expression) : expression, flags);
    } catch (PatternSyntaxException exception) {
      return failure(ToolErrorKind.INVALID_REGEX,
          "invalid regex '" + expression + "': " + exception.getDescription());
    }
    String fileGlob = args.string("glob", "");
    List<Path> candidates = candidates(root, fileGlob, MAX_FILE_BYTES, null);
    if (candidates.isEmpty()) return success("No matches found. The directory may be empty or "
        + "every file was filtered (binary extension, size cap, or hidden).");
    var files = new ArrayList<FileMatches>();
    int total = 0;
    for (Path path : candidates) {
      byte[] bytes = Files.readAllBytes(path);
      if (containsNull(bytes, Math.min(bytes.length, 4096))) continue;
      String content = new String(bytes, StandardCharsets.UTF_8);
      List<String> lines = contentLines(content);
      var lineMatches = new ArrayList<Integer>();
      var matcher = regex.matcher(content);
      int cursor = 0;
      int line = 0;
      while (matcher.find() && total < MAX_MATCHES) {
        while (cursor < matcher.start()) {
          if (content.charAt(cursor++) == '\n') line++;
        }
        lineMatches.add(line);
        total++;
      }
      if (!lineMatches.isEmpty()) files.add(new FileMatches(path, lines, lineMatches));
      if (total >= MAX_MATCHES) break;
    }
    if (total == 0) return success("No matches found. Check the pattern syntax (this is ECMAScript "
        + "regex, not PCRE — no look-behind, no named groups), try a broader pattern, or use "
        + "`glob` first to narrow the file set.");
    int offset = Math.max(0, args.integer("offset", 0));
    if (offset >= total) return success("No matches on this page. Total matches: "
        + total + ". Try a smaller offset.");
    var output = new StringBuilder("Found ").append(total).append(total == 1
        ? " match" : " matches").append(total >= MAX_MATCHES ? "+" : "")
        .append(" across ").append(files.size()).append(files.size() == 1
        ? " file.\n\n" : " files.\n\n");
    int shown = 0;
    int skipped = 0;
    boolean sizeCapped = false;
    for (FileMatches file : files) {
      if (shown >= PER_PAGE) break;
      if (utf8Length(output) >= MAX_OUTPUT_BYTES) {
        sizeCapped = true;
        break;
      }
      var ranges = new ArrayList<LineRange>();
      for (int line : file.matchLines()) {
        if (skipped < offset) {
          skipped++;
          continue;
        }
        if (shown >= PER_PAGE) break;
        int start = Math.max(0, line - CONTEXT_LINES);
        int end = Math.min(file.lines().size() - 1, line + CONTEXT_LINES);
        if (!ranges.isEmpty() && start <= ranges.getLast().end() + 1) {
          LineRange prior = ranges.removeLast();
          ranges.add(new LineRange(prior.start(), Math.max(prior.end(), end)));
        } else {
          ranges.add(new LineRange(start, end));
        }
        shown++;
      }
      if (ranges.isEmpty()) continue;
      output.append("## Matches in ").append(file.path()).append("\n\n");
      for (LineRange range : ranges) {
        int firstMatchLine = file.matchLines().stream()
            .filter(line -> line >= range.start() && line <= range.end()).findFirst()
            .orElse(range.start());
        String symbol = enclosingSymbol(file.lines(), firstMatchLine + 1);
        output.append("### ");
        if (!symbol.isEmpty()) output.append(symbol).append(" › ");
        output.append('L').append(range.start() + 1).append('-').append(range.end() + 1)
            .append("\n```\n");
        for (int line = range.start(); line <= range.end(); line++)
          output.append(file.lines().get(line)).append('\n');
        output.append("```\n\n");
      }
    }
    if (sizeCapped) output.append("[output capped at ").append(MAX_OUTPUT_BYTES)
        .append(" bytes — narrow the pattern or use offset to page]\n\n");
    int remaining = total - (offset + shown);
    if (remaining > 0) output.append("Showing matches ").append(offset + 1).append('-')
        .append(offset + shown).append(" of ").append(total)
        .append(total >= MAX_MATCHES ? "+ (scan limit reached)" : "").append(". Use offset: ")
        .append(offset + PER_PAGE).append(" to see the next page.");
    else if (shown == 0) return success("No matches on this page. Total matches: "
        + total + ". Try a smaller offset.");
    else output.append("Showing all ").append(total).append(" matches.");
    return described(args, output.toString());
  }

  private static boolean isLiteral(String expression) {
    return expression.chars().noneMatch(character -> ".^$*+?()[]{}|\\".indexOf(character) >= 0);
  }

  private static List<String> contentLines(String content) {
    var lines = new ArrayList<>(List.of(content.split("\\n", -1)));
    if (content.endsWith("\n") && !lines.isEmpty() && lines.getLast().isEmpty()) {
      lines.removeLast();
    }
    return List.copyOf(lines);
  }

  private static boolean containsNull(byte[] bytes, int limit) {
    for (int index = 0; index < limit; index++) if (bytes[index] == 0) return true;
    return false;
  }

  private static int utf8Length(CharSequence text) {
    return text.toString().getBytes(StandardCharsets.UTF_8).length;
  }

  private static String enclosingSymbol(List<String> lines, int matchLineOneBased) {
    if (matchLineOneBased < 2 || matchLineOneBased > lines.size()) return "";
    int matchIndex = matchLineOneBased - 1;
    int bestIndent = indentation(lines.get(matchIndex));
    String fallback = "";
    for (int index = matchIndex - 1; index >= Math.max(0, matchIndex - 400); index--) {
      String raw = stripCr(lines.get(index));
      if (raw.isBlank()) continue;
      int indent = indentation(raw);
      if (indent >= bestIndent) continue;
      String candidate = raw.stripLeading();
      int kind = symbolKind(candidate);
      if (kind == 2) return truncateBreadcrumb(candidate);
      if (kind == 1) {
        if (fallback.isEmpty()) fallback = truncateBreadcrumb(candidate);
        bestIndent = indent;
        continue;
      }
      bestIndent = indent;
    }
    return fallback;
  }

  private static int indentation(String line) {
    int width = 0;
    for (int index = 0; index < line.length(); index++) {
      if (line.charAt(index) == ' ') width++;
      else if (line.charAt(index) == '\t') width += 4;
      else break;
    }
    return width;
  }

  private static int symbolKind(String line) {
    for (String keyword : List.of("fn ", "def ", "class ", "struct ", "enum ", "impl ",
        "trait ", "interface ", "namespace ", "function", "func ", "public ", "private ",
        "protected ", "static ", "void ", "template", "module ", "export ", "type "))
      if (line.contains(keyword)) return 2;
    for (String control : List.of("for ", "for(", "while ", "while(", "if ", "if(", "else",
        "switch ", "switch(", "do ", "do{", "try", "catch", "} else", "} catch", "loop ",
        "loop{", "match ", "match("))
      if (line.startsWith(control)) return 0;
    return line.endsWith("{") || line.endsWith("(") ? 1 : 0;
  }

  private static String truncateBreadcrumb(String line) {
    if (line.getBytes(StandardCharsets.UTF_8).length <= 100) return line;
    int end = Math.min(99, line.length());
    while (end > 0 && line.substring(0, end).getBytes(StandardCharsets.UTF_8).length > 99) end--;
    if (end > 0 && Character.isHighSurrogate(line.charAt(end - 1))) end--;
    return line.substring(0, end) + '…';
  }

  private static String stripCr(String line) {
    return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
  }

  private ToolResult findDefinition(JsonNode arguments) throws IOException {
    var args = new ArgReader(arguments);
    String symbol = args.requiredString("symbol").orElse("");
    if (symbol.isEmpty()) return failure(ToolErrorKind.INVALID_ARGS, "symbol required");
    Path root = root(args, "find_definition");
    if (root == null) return outside(args.string("path", "."), "find_definition");
    String escaped = Pattern.quote(symbol);
    Pattern definition = Pattern.compile("(?:\\b(?:class|struct|enum|union|namespace|typedef|using|def|"
        + "function|const|let|var|type|interface|export|func|fn|trait|mod|static)\\s+" + escaped
        + "\\b)|(?:#define\\s+" + escaped + "\\b)|(?:\\b\\w[\\w:*&<> ]*\\s+" + escaped
        + "\\s*\\()" );
    var output = new StringBuilder();
    int count = 0;
    for (Path path : candidates(root, "", 512L * 1024L, CODE_EXTENSIONS)) {
      List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
      for (int index = 0; index < lines.size() && count <= 50; index++) {
        if (definition.matcher(lines.get(index)).find()) {
          output.append(path).append(':').append(index + 1).append(": ").append(lines.get(index))
              .append('\n');
          count++;
        }
      }
    }
    if (count == 0) return success("no definitions found for '" + symbol + "'");
    if (count > 50) output.append("[>50 definitions, truncated]\n");
    return described(args, output.toString());
  }

  private List<Path> candidates(Path root, String glob, long maxBytes, Set<String> extensions)
      throws IOException {
    var result = new ArrayList<Path>();
    try (Stream<Path> paths = walk(root)) {
      paths.filter(Files::isRegularFile).filter(path -> !fileName(path).startsWith("."))
          .filter(path -> glob.isEmpty() || GlobMatcher.matches(glob, fileName(path)))
          .filter(path -> extensions == null ? !BINARY_EXTENSIONS.contains(extension(path))
              : extensions.contains(extension(path)))
          .filter(path -> safeSize(path) > 0 && safeSize(path) <= maxBytes)
          .sorted().forEach(result::add);
    }
    return result;
  }

  private Stream<Path> walk(Path root) throws IOException {
    if (!Files.isDirectory(root)) return Stream.empty();
    return Files.walk(root).filter(path -> path.equals(root) || path.getParent() == null
        || !hasSkippedAncestor(root, path));
  }

  private static boolean hasSkippedAncestor(Path root, Path path) {
    Path relative = root.relativize(path);
    for (Path component : relative) {
      if (WorkspaceSandbox.shouldSkipDirectory(component.toString())) return true;
    }
    return false;
  }

  private Path root(ArgReader args, String operation) {
    Path path = sandbox.normalize(args.string("path", "."));
    return sandbox.isWithin(path) ? path : null;
  }

  private static String extension(Path path) {
    String name = fileName(path);
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
  }

  private static String fileName(Path path) {
    Path value = path.getFileName();
    return value == null ? path.toString() : value.toString();
  }

  private static long safeSize(Path path) {
    try { return Files.size(path); } catch (IOException exception) { return -1; }
  }

  private static String formatSize(long bytes) {
    if (bytes < 1024) return bytes + "B";
    if (bytes < 1024L * 1024L) return "%.1fK".formatted(bytes / 1024.0);
    return "%.1fM".formatted(bytes / (1024.0 * 1024.0));
  }

  private static ToolResult invalidPattern(String pattern) {
    return failure(ToolErrorKind.INVALID_ARGS, pattern.isEmpty() ? "pattern required"
        : "pattern must not be blank (received only whitespace)");
  }

  private static ToolResult outside(String path, String operation) {
    return failure(ToolErrorKind.OUT_OF_WORKSPACE, operation + ": path is outside workspace: " + path);
  }

  private static ToolResult described(ArgReader args, String text) {
    String description = args.string("display_description", "");
    return success(description.isEmpty() ? text : description + '\n' + text);
  }

  private static ToolResult success(String text) { return new ToolResult.Success(new ToolOutput(text)); }
  private static ToolResult failure(ToolErrorKind kind, String detail) {
    return new ToolResult.Failure(new ToolError(kind, detail));
  }
  private static String detail(Exception exception) {
    return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
  }
}
