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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/** Token-budgeted PageRank map over source definitions and cross-file references. */
public final class RepoMapTools {
  private static final int MAX_FILES = 5000;
  private static final int MAX_DEFINITIONS = 64;
  private static final Set<String> SOURCE_EXTENSIONS = Set.of(".cpp", ".hpp", ".c", ".h",
      ".cc", ".hh", ".cxx", ".hxx", ".py", ".js", ".ts", ".jsx", ".tsx", ".mjs",
      ".go", ".rs", ".java", ".kt", ".rb", ".swift", ".zig", ".lua", ".cs", ".scala",
      ".ex", ".exs", ".ml", ".hs", ".proto");
  private static final Set<String> STOP_WORDS = Set.of("int", "char", "bool", "void", "auto",
      "const", "static", "return", "true", "false", "null", "nullptr", "None", "self",
      "this", "std", "string", "size_t", "vector", "for", "while", "else", "break",
      "continue", "public", "private", "protected", "class", "struct", "def", "function",
      "import", "from", "include", "namespace", "using", "new", "delete", "sizeof",
      "typedef", "template", "typename");
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]\\w{2,}");
  private static final List<Pattern> DEFINITIONS = List.of(
      Pattern.compile("^\\s*(?:template\\s*<[^>]*>\\s*)?(?:class|struct|enum(?:\\s+class)?|union|namespace|interface|trait|impl|module)\\s+([A-Za-z_]\\w*)"),
      Pattern.compile("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:def|fn|func|function)\\s+([A-Za-z_]\\w*)"),
      Pattern.compile("^\\s*(?:export\\s+)?(?:async\\s+)?(?:const|let|var|type)\\s+([A-Za-z_]\\w*)\\s*[=:<]"),
      Pattern.compile("^[A-Za-z_][\\w:<>,\\s*&]*?\\s+([A-Za-z_]\\w*)\\s*\\([^;]*(?:\\)\\s*(?:const\\s*)?(?:noexcept\\s*)?\\{|\\)\\s*$)"),
      Pattern.compile("^\\s*#define\\s+([A-Za-z_]\\w*)"));

  private record Definition(String name, int line, String signature) {}
  private static final class Node {
    private final String relative;
    private final String body;
    private final List<Definition> definitions;
    private final Map<Integer, Integer> edges = new HashMap<>();
    private double rank;
    private Node(String relative, String body, List<Definition> definitions) {
      this.relative = relative;
      this.body = body;
      this.definitions = definitions;
    }
  }

  private final WorkspaceSandbox sandbox;
  public RepoMapTools(WorkspaceSandbox sandbox) { this.sandbox = sandbox; }

  public ToolResult execute(JsonNode arguments) {
    try {
      var args = new ArgReader(arguments);
      Path root = sandbox.normalize(args.string("path", "."));
      if (!sandbox.isReadable(root) || !Files.isDirectory(root)) {
        return failure(ToolErrorKind.OUT_OF_WORKSPACE, "repo_map: path is outside workspace: " + root);
      }
      int budget = Math.clamp(args.integer("budget", 8000), 1000, 60000);
      String focus = args.string("focus", "");
      List<Node> graph = buildGraph(root);
      if (graph.isEmpty()) return failure(ToolErrorKind.NOT_FOUND,
          "repo_map: no source files found under " + root);
      link(graph);
      rank(graph, focus);
      return success(render(graph, focus, budget));
    } catch (IOException exception) {
      return failure(ToolErrorKind.IO, exception.getMessage() == null
          ? exception.getClass().getSimpleName() : exception.getMessage());
    }
  }

  private static List<Node> buildGraph(Path root) throws IOException {
    var graph = new ArrayList<Node>();
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.filter(Files::isRegularFile).filter(p -> !skipped(root, p))
          .filter(p -> SOURCE_EXTENSIONS.contains(extension(p))).filter(p -> size(p) <= 512 * 1024)
          .sorted().limit(MAX_FILES).toList()) {
        String body = Files.readString(path, StandardCharsets.UTF_8);
        graph.add(new Node(root.relativize(path).toString(), body, definitions(body)));
      }
    }
    return graph;
  }

  private static List<Definition> definitions(String body) {
    var result = new ArrayList<Definition>();
    String[] lines = body.split("\\R", -1);
    for (int index = 0; index < lines.length && result.size() < MAX_DEFINITIONS; index++) {
      if (lines[index].length() >= 500) continue;
      for (Pattern pattern : DEFINITIONS) {
        var matcher = pattern.matcher(lines[index]);
        if (matcher.find()) {
          String name = matcher.group(1);
          if (name.length() >= 3 && !STOP_WORDS.contains(name)) {
            String signature = lines[index].strip();
            result.add(new Definition(name, index + 1,
                signature.substring(0, Math.min(120, signature.length()))));
          }
          break;
        }
      }
    }
    return result;
  }

  private static void link(List<Node> graph) {
    var sites = new HashMap<String, List<Integer>>();
    for (int index = 0; index < graph.size(); index++) {
      for (Definition definition : graph.get(index).definitions) {
        sites.computeIfAbsent(definition.name(), ignored -> new ArrayList<>()).add(index);
      }
    }
    for (int index = 0; index < graph.size(); index++) {
      var identifiers = new HashSet<String>();
      var matcher = IDENTIFIER.matcher(graph.get(index).body);
      while (matcher.find()) identifiers.add(matcher.group());
      for (String identifier : identifiers) {
        List<Integer> targets = sites.get(identifier);
        if (STOP_WORDS.contains(identifier) || targets == null || targets.size() > 8) continue;
        for (int target : targets) if (target != index) graph.get(index).edges.merge(target, 1, Integer::sum);
      }
    }
  }

  private static void rank(List<Node> graph, String focus) {
    int count = graph.size();
    double[] restart = new double[count];
    Arrays.fill(restart, 1.0);
    List<String> terms = Arrays.stream(focus.toLowerCase(Locale.ROOT).split("\\s+"))
        .filter(term -> term.length() >= 3).toList();
    if (!terms.isEmpty()) {
      for (int index = 0; index < count; index++) {
        String haystack = (graph.get(index).relative + " " + graph.get(index).definitions.stream()
            .map(Definition::name).reduce("", (left, right) -> left + " " + right))
            .toLowerCase(Locale.ROOT);
        if (terms.stream().anyMatch(haystack::contains)) restart[index] = 20.0;
      }
    }
    double sum = Arrays.stream(restart).sum();
    for (int index = 0; index < count; index++) restart[index] /= sum;
    double[] ranks = new double[count];
    Arrays.fill(ranks, 1.0 / count);
    for (int iteration = 0; iteration < 24; iteration++) {
      double[] next = new double[count];
      double dangling = 0;
      for (int index = 0; index < count; index++) {
        Node node = graph.get(index);
        if (node.edges.isEmpty()) { dangling += ranks[index]; continue; }
        double weight = node.edges.values().stream().mapToInt(Integer::intValue).sum();
        for (var edge : node.edges.entrySet()) {
          next[edge.getKey()] += .85 * ranks[index] * edge.getValue() / weight;
        }
      }
      for (int index = 0; index < count; index++) {
        next[index] += .15 * restart[index] + .85 * dangling * restart[index];
      }
      ranks = next;
    }
    for (int index = 0; index < count; index++) graph.get(index).rank = ranks[index];
  }

  private static String render(List<Node> graph, String focus, int budget) {
    List<Node> order = IntStream.range(0, graph.size()).mapToObj(graph::get)
        .sorted(Comparator.comparingDouble((Node node) -> node.rank).reversed()
            .thenComparing(node -> node.relative)).toList();
    var output = new StringBuilder("Repository map (").append(graph.size()).append(" files ranked");
    if (!focus.isEmpty()) output.append(", focused on '").append(focus).append('\'');
    output.append("; PageRank over the def/ref graph):\n\n");
    int emitted = 0;
    for (Node node : order) {
      var block = new StringBuilder(node.relative).append(":\n");
      int shown = 0;
      for (Definition definition : node.definitions) {
        block.append("  L").append(definition.line()).append(": ").append(definition.signature()).append('\n');
        if (++shown >= 24) break;
      }
      if (output.length() + block.length() > budget && emitted > 0) break;
      output.append(block);
      emitted++;
      if (output.length() >= budget) break;
    }
    return output.append("\n(").append(emitted).append(" of ").append(graph.size())
        .append(" files shown, budget ").append(budget).append(" bytes. Re-run with `focus` to "
            + "re-center the map, `budget` to widen it.)").toString();
  }

  private static boolean skipped(Path root, Path path) {
    Path relative = root.relativize(path);
    int index = 0;
    for (Path component : relative) {
      String name = component.toString();
      if (WorkspaceSandbox.shouldSkipDirectory(name)
          || (index < relative.getNameCount() - 1 && name.startsWith("."))) return true;
      index++;
    }
    return false;
  }

  private static String extension(Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) return "";
    String name = fileName.toString();
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
  }
  private static long size(Path path) {
    try { return Files.size(path); } catch (IOException exception) { return Long.MAX_VALUE; }
  }
  private static ToolResult success(String text) { return new ToolResult.Success(new ToolOutput(text)); }
  private static ToolResult failure(ToolErrorKind kind, String detail) {
    return new ToolResult.Failure(new ToolError(kind, detail));
  }
}
