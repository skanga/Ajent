package com.github.skanga.ajent.core.workspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** AgenTTY's deterministic @file fuzzy ranking and #symbol filtering. */
public final class WorkspaceMatcher {
  private static final Set<String> SOURCE_EXTENSIONS = Set.of(
      "c", "cc", "cpp", "cxx", "c++", "h", "hh", "hpp", "hxx", "h++", "rs", "go",
      "py", "ts", "tsx", "js", "jsx", "mjs", "cjs", "java", "kt", "kts", "swift",
      "m", "mm", "rb", "php", "lua", "zig", "scala", "clj", "cljs", "cljc", "ex",
      "exs", "erl", "hrl", "ml", "mli", "hs", "dart", "nim", "v", "sv", "vhd",
      "sh", "bash", "zsh", "fish", "ps1", "r", "jl", "sol", "tf", "hcl");
  private static final Set<String> BUILD_EXTENSIONS = Set.of(
      "cmake", "mk", "make", "bazel", "bzl", "gradle", "sbt", "ninja");
  private static final Set<String> DOC_EXTENSIONS = Set.of(
      "md", "mdx", "rst", "txt", "adoc", "org", "json", "json5", "jsonc", "yaml", "yml",
      "toml", "ini", "conf", "cfg", "properties", "env", "xml", "html", "htm", "css",
      "scss", "sass", "less", "sql", "proto", "graphql", "gql");
  private static final Set<String> ASSET_EXTENSIONS = Set.of(
      "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "tiff", "pdf", "psd",
      "ai", "mp3", "mp4", "mov", "avi", "mkv", "webm", "wav", "ogg", "flac", "m4a",
      "aac", "opus", "zip", "tar", "gz", "tgz", "xz", "bz2", "7z", "rar", "bin",
      "iso", "img", "dmg", "exe", "dll", "so", "dylib", "a", "o", "obj", "class",
      "jar", "war", "pyc", "pyo", "wasm", "lock", "sum", "midi", "mid");
  private static final Set<String> BUILD_FILES = Set.of(
      "cmakelists.txt", "makefile", "gnumakefile", "build", "workspace", "dockerfile",
      "cargo.toml", "go.mod", "package.json", "pyproject.toml", "pnpm-lock.yaml", "yarn.lock",
      "poetry.lock", "gemfile", "rakefile");

  private WorkspaceMatcher() {}

  public static List<Integer> filterFiles(List<String> files, String query) {
    String needle = query.replace(" ", "").replace("\t", "").toLowerCase(Locale.ROOT);
    if (needle.isEmpty()) return java.util.stream.IntStream.range(0, files.size()).boxed().toList();
    var scored = new ArrayList<Scored>();
    for (int index = 0; index < files.size(); index++) {
      int score = fuzzyScore(files.get(index), needle);
      if (score != Integer.MIN_VALUE) scored.add(new Scored(score, index));
    }
    scored.sort(Comparator.comparingInt(Scored::score).reversed());
    return scored.stream().map(Scored::index).toList();
  }

  public static List<Integer> filterSymbols(List<WorkspaceSymbol> symbols, String query) {
    if (query.isEmpty()) return java.util.stream.IntStream.range(0, symbols.size()).boxed().toList();
    String needle = query.toLowerCase(Locale.ROOT);
    var matches = new ArrayList<Integer>();
    for (int index = 0; index < symbols.size(); index++) {
      if (symbols.get(index).name().toLowerCase(Locale.ROOT).contains(needle)) matches.add(index);
    }
    return List.copyOf(matches);
  }

  static int fuzzyScore(String path, String needle) {
    if (needle.isEmpty()) return 0;
    if (needle.length() > path.length()) return Integer.MIN_VALUE;
    String base = filename(path);
    int baseOffset = path.length() - base.length();
    int bonus = 0;
    if (base.equalsIgnoreCase(needle)) bonus += 200;
    else if (startsWithIgnoreCase(base, needle)) bonus += 120;
    if (startsWithIgnoreCase(path, needle)) bonus += 60;

    int score = 0;
    int pathIndex = 0;
    int needleIndex = 0;
    boolean previousMatched = false;
    int skipped = 0;
    while (needleIndex < needle.length() && pathIndex < path.length()) {
      char expected = needle.charAt(needleIndex);
      char actual = asciiLower(path.charAt(pathIndex));
      if (actual == expected) {
        score += 16;
        if (previousMatched) score += 18;
        if (wordBoundary(path, pathIndex)) score += 30;
        if (pathIndex >= baseOffset) score += 12;
        previousMatched = true;
        needleIndex++;
      } else {
        previousMatched = false;
        skipped++;
      }
      pathIndex++;
    }
    if (needleIndex < needle.length()) return Integer.MIN_VALUE;
    score -= skipped;
    score -= 2 * path.chars().filter(value -> value == '/' || value == '\\').count();
    return score + classBias(path) + bonus;
  }

  private static int classBias(String path) {
    String base = filename(path);
    int bias = base.startsWith(".") ? -50 : 0;
    if (BUILD_FILES.contains(base.toLowerCase(Locale.ROOT))) return bias + 60;
    int dot = base.lastIndexOf('.');
    if (dot <= 0 || dot + 1 == base.length()) return bias;
    String extension = base.substring(dot + 1).toLowerCase(Locale.ROOT);
    if (SOURCE_EXTENSIONS.contains(extension)) return bias + 90;
    if (BUILD_EXTENSIONS.contains(extension)) return bias + 60;
    if (DOC_EXTENSIONS.contains(extension)) return bias + 30;
    if (ASSET_EXTENSIONS.contains(extension)) return bias - 50;
    return bias;
  }

  private static boolean wordBoundary(String value, int index) {
    if (index == 0) return true;
    char previous = value.charAt(index - 1);
    char current = value.charAt(index);
    return previous == '/' || previous == '\\' || previous == '_' || previous == '-'
        || previous == '.' || previous == ' '
        || previous >= 'a' && previous <= 'z' && current >= 'A' && current <= 'Z';
  }

  private static boolean startsWithIgnoreCase(String value, String prefix) {
    return value.length() >= prefix.length()
        && value.regionMatches(true, 0, prefix, 0, prefix.length());
  }

  private static String filename(String path) {
    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return slash < 0 ? path : path.substring(slash + 1);
  }

  private static char asciiLower(char value) {
    return value >= 'A' && value <= 'Z' ? (char) (value + ('a' - 'A')) : value;
  }

  private record Scored(int score, int index) {}
}
