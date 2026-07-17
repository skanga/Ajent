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

/** AgenTTY-compatible grep, glob, and definition-search bodies. */
public final class SearchTools {
  private static final int PER_PAGE = 20;
  private static final int MAX_MATCHES = 500;
  private static final long MAX_FILE_BYTES = 8L * 1024L * 1024L;
  private static final Set<String> BINARY_EXTENSIONS = Set.of(".exe", ".dll", ".png", ".jpg",
      ".jpeg", ".gif", ".pdf", ".zip", ".gz", ".jar", ".class", ".wasm", ".lock");
  private static final Set<String> CODE_EXTENSIONS = Set.of(".cpp", ".hpp", ".c", ".h", ".cc",
      ".hh", ".cxx", ".hxx", ".py", ".js", ".ts", ".jsx", ".tsx", ".go", ".rs",
      ".java", ".kt", ".rb", ".swift", ".zig", ".lua");

  private record Match(Path path, int line, String text) {}
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
        String name = path.getFileName().toString();
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
    int flags = args.bool("case_sensitive", false) ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    Pattern regex;
    try {
      regex = Pattern.compile(expression, flags);
    } catch (PatternSyntaxException exception) {
      return failure(ToolErrorKind.INVALID_REGEX,
          "invalid regex '" + expression + "': " + exception.getDescription());
    }
    String fileGlob = args.string("glob", "");
    var matches = new ArrayList<Match>();
    for (Path path : candidates(root, fileGlob, MAX_FILE_BYTES, null)) {
      List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
      for (int index = 0; index < lines.size() && matches.size() < MAX_MATCHES; index++) {
        if (regex.matcher(lines.get(index)).find()) matches.add(new Match(path, index + 1, lines.get(index)));
      }
      if (matches.size() >= MAX_MATCHES) break;
    }
    if (matches.isEmpty()) return success("No matches found. Check the pattern syntax (this is ECMAScript "
        + "regex, not PCRE), try a broader pattern, or use `glob` first to narrow the file set.");
    int offset = Math.max(0, args.integer("offset", 0));
    if (offset >= matches.size()) return success("No matches on this page. Total matches: "
        + matches.size() + ". Try a smaller offset.");
    int end = Math.min(matches.size(), offset + PER_PAGE);
    long files = matches.stream().map(Match::path).distinct().count();
    var output = new StringBuilder("Found ").append(matches.size()).append(matches.size() == 1
        ? " match" : " matches").append(" across ").append(files).append(files == 1
        ? " file.\n\n" : " files.\n\n");
    Path prior = null;
    for (Match match : matches.subList(offset, end)) {
      if (!match.path().equals(prior)) output.append("## Matches in ").append(match.path()).append("\n\n");
      output.append("### L").append(match.line()).append('-').append(match.line()).append("\n```\n")
          .append(match.text()).append("\n```\n\n");
      prior = match.path();
    }
    if (end < matches.size()) output.append("Showing matches ").append(offset + 1).append('-')
        .append(end).append(" of ").append(matches.size()).append(". Use offset: ")
        .append(offset + PER_PAGE).append(" to see the next page.");
    else if (offset > 0) output.append("Showing matches ").append(offset + 1).append('-')
        .append(end).append(" of ").append(matches.size()).append('.');
    else output.append("Showing all ").append(matches.size()).append(" matches.");
    return described(args, output.toString());
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
      paths.filter(Files::isRegularFile).filter(path -> !path.getFileName().toString().startsWith("."))
          .filter(path -> glob.isEmpty() || GlobMatcher.matches(glob, path.getFileName().toString()))
          .filter(path -> extensions == null ? !BINARY_EXTENSIONS.contains(extension(path))
              : extensions.contains(extension(path)))
          .filter(path -> safeSize(path) > 0 && safeSize(path) <= maxBytes)
          .sorted().forEach(result::add);
    }
    return result;
  }

  private Stream<Path> walk(Path root) throws IOException {
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
    return sandbox.isReadable(path) && Files.isDirectory(path) ? path : null;
  }

  private static String extension(Path path) {
    String name = path.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
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
