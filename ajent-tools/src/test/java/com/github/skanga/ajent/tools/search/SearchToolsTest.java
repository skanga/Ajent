package com.github.skanga.ajent.tools.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.tools.fs.WorkspaceSandbox;
import com.github.skanga.ajent.tools.runtime.ToolErrorKind;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchToolsTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void portsPinnedSearchToolContract(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("alpha.cpp"), "#include <cstdio>\n"
        + "int compute_total(int a, int b) {\n"
        + "    return a + b;  // NEEDLE_marker here\n}\n");
    Files.writeString(root.resolve("beta.txt"), "plain text\nwith a NEEDLE_marker too\n");
    Files.createDirectory(root.resolve("sub"));
    Files.writeString(root.resolve("sub/gamma.py"),
        "def compute_total(x):\n    return x * 2\n");
    var tools = tools(root);

    assertThat(success(tools.execute("grep", JSON.createObjectNode()
        .put("pattern", "NEEDLE_marker").put("path", root.toString()))))
        .contains("alpha.cpp", "beta.txt", "Found 2 matches");
    assertThat(success(tools.execute("grep", JSON.createObjectNode()
        .put("pattern", "needle_MARKER").put("path", root.toString())
        .put("glob", "*.cpp")))).contains("alpha.cpp").doesNotContain("beta.txt");
    assertThat(failure(tools.execute("grep", JSON.createObjectNode()
        .put("pattern", "   "))).error().kind()).isEqualTo(ToolErrorKind.INVALID_ARGS);

    assertThat(success(tools.execute("glob", JSON.createObjectNode()
        .put("pattern", "*.py").put("path", root.toString())))).contains("gamma.py");
    assertThat(success(tools.execute("glob", JSON.createObjectNode()
        .put("pattern", "alpha").put("path", root.toString())))).contains("alpha.cpp");
    assertThat(success(tools.execute("find_definition", JSON.createObjectNode()
        .put("symbol", "compute_total").put("path", root.toString()))))
        .contains("compute_total", "alpha.cpp", "gamma.py");
  }

  @Test
  void preservesGlobRegexPaginationAndBoundarySemantics(@TempDir Path root,
      @TempDir Path outside) throws Exception {
    Files.writeString(root.resolve("Alpha.java"), "class Alpha {\n  void VALUE() {}\n}\n");
    Files.writeString(root.resolve("notes.md"), "value\nVALUE\nvalue\n");
    Files.createDirectory(root.resolve("target"));
    Files.writeString(root.resolve("target/hidden.java"), "class Hidden {}\n");
    var tools = tools(root);

    assertThat(GlobMatcher.matches("[A-Z]*.java", "Alpha.java")).isTrue();
    assertThat(GlobMatcher.matches("[!a-z]*.java", "Alpha.java")).isTrue();
    assertThat(success(tools.execute("glob", JSON.createObjectNode().put("pattern", "*.java"))))
        .contains("Alpha.java").doesNotContain("hidden.java");
    assertThat(success(tools.execute("grep", JSON.createObjectNode().put("pattern", "VALUE")
        .put("case_sensitive", true).put("offset", 1)))).contains("Showing all 2 matches");
    assertThat(failure(tools.execute("grep", JSON.createObjectNode().put("pattern", "[")
        .put("case_sensitive", true))).error().kind()).isEqualTo(ToolErrorKind.INVALID_REGEX);
    assertThat(failure(tools.execute("glob", JSON.createObjectNode()
        .put("pattern", "*").put("path", outside.toString()))).error().kind())
        .isEqualTo(ToolErrorKind.OUT_OF_WORKSPACE);
    var sandbox = new WorkspaceSandbox(root, root, root);
    sandbox.allowReadRoot(outside);
    assertThat(failure(new SearchTools(sandbox).execute("grep", JSON.createObjectNode()
        .put("pattern", "x").put("path", outside.toString()))).error().kind())
        .isEqualTo(ToolErrorKind.OUT_OF_WORKSPACE);

    Path absent = root.resolve("not-created");
    assertThat(success(tools.execute("glob", JSON.createObjectNode()
        .put("pattern", "*").put("path", absent.toString())))).startsWith("no matches");
    assertThat(success(tools.execute("grep", JSON.createObjectNode()
        .put("pattern", "x").put("path", absent.toString())))).contains("every file was filtered");
  }

  @Test
  void grepPortsContextMergingOccurrenceCountsAndSymbolBreadcrumbs(@TempDir Path root)
      throws Exception {
    Files.writeString(root.resolve("Service.java"), String.join("\n",
        "class Service {",
        "  void calculate() {",
        "    int before = 1;",
        "    String text = \"NEEDLE then NEEDLE\";",
        "    int after = 2;",
        "  }",
        "}") + '\n');

    String result = success(tools(root).execute("grep", JSON.createObjectNode()
        .put("pattern", "NEEDLE").put("case_sensitive", true)));

    assertThat(result).contains("Found 2 matches across 1 file.",
        "### void calculate() { › L2-6", "int before = 1", "int after = 2",
        "Showing all 2 matches.");
    assertThat(result).containsOnlyOnce("### void calculate() { › L2-6");
  }

  @Test
  void grepDoesNotRenderATerminalNewlineAsAnExtraContextRow(@TempDir Path root)
      throws Exception {
    Files.writeString(root.resolve("three.txt"), "alpha\nNEEDLE\ngamma\n");

    String result = success(tools(root).execute("grep", JSON.createObjectNode()
        .put("pattern", "NEEDLE").put("case_sensitive", true)));

    assertThat(result).contains("### L1-3\n```\nalpha\nNEEDLE\ngamma\n```")
        .doesNotContain("L1-4", "gamma\n\n```");
  }

  @Test
  void grepPinsScanLimitAndTwentyMatchPagination(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("many.txt"), "hit ".repeat(501));

    String result = success(tools(root).execute("grep", JSON.createObjectNode()
        .put("pattern", "hit").put("case_sensitive", true)));

    assertThat(result).contains("Found 500 matches+ across 1 file.",
        "Showing matches 1-20 of 500+ (scan limit reached). Use offset: 20");
  }

  @Test
  void grepEvaluatesRegexAgainstTheWholeFileRatherThanEachLine(@TempDir Path root)
      throws Exception {
    Files.writeString(root.resolve("anchors.txt"), "first\nsecond\n");
    var tools = tools(root);

    assertThat(success(tools.execute("grep", JSON.createObjectNode()
        .put("pattern", "^.").put("case_sensitive", true))))
        .contains("Found 1 match across 1 file.");
    assertThat(success(tools.execute("grep", JSON.createObjectNode()
        .put("pattern", "first\\nsecond").put("case_sensitive", true))))
        .contains("Found 1 match across 1 file.");
  }

  @Test
  void grepHandlesFallbackAndLongUtf8BreadcrumbsAndNulContent(@TempDir Path root)
      throws Exception {
    Files.writeString(root.resolve("fallback.txt"),
        "outer {\n  if (condition) {\n    \n    hit\n  }\n}\n");
    String longSymbol = "void " + "é".repeat(60) + "() {";
    Files.writeString(root.resolve("long.java"), longSymbol + "\n\thit\n}\n");
    var tools = tools(root);

    String breadcrumbs = success(tools.execute("grep", JSON.createObjectNode()
        .put("pattern", "hit").put("case_sensitive", true)));
    assertThat(breadcrumbs).contains("outer { ›", "void ").contains("… ›")
        .doesNotContain(longSymbol + " ›");

    Path nulRoot = root.resolve("nul-content");
    Files.createDirectory(nulRoot);
    Files.write(nulRoot.resolve("binary.txt"), new byte[] {'h', 'i', 't', 0, 'x'});
    assertThat(success(tools.execute("grep", JSON.createObjectNode()
        .put("pattern", "hit").put("path", nulRoot.toString()))))
        .startsWith("No matches found. Check the pattern syntax");
  }

  @Test
  void grepCapsRenderedPagesBetweenFilesAndExplainsFullyFilteredTrees(@TempDir Path root)
      throws Exception {
    var large = new StringBuilder();
    for (int index = 0; index < 10; index++)
      large.append("hit ").append("x".repeat(2_500)).append("\nplain\nplain\nplain\nplain\n");
    Files.writeString(root.resolve("a.txt"), large);
    Files.writeString(root.resolve("b.txt"), "hit final\n");

    String capped = success(tools(root).execute("grep", JSON.createObjectNode()
        .put("pattern", "hit").put("case_sensitive", true)));
    assertThat(capped).contains("[output capped at 20000 bytes",
        "Showing matches 1-10 of 11. Use offset: 20").doesNotContain("hit final");

    Path filtered = root.resolve("filtered");
    Files.createDirectory(filtered);
    Files.writeString(filtered.resolve("only.dat"), "hit");
    assertThat(success(tools(root).execute("grep", JSON.createObjectNode()
        .put("pattern", "hit").put("path", filtered.toString()))))
        .isEqualTo("No matches found. The directory may be empty or every file was filtered "
            + "(binary extension, size cap, or hidden).");
  }

  @Test
  void coversNoMatchDescriptionsAliasesAndGlobFormatting(@TempDir Path root) throws Exception {
    Files.createDirectory(root.resolve("srcdir"));
    Files.writeString(root.resolve("small.txt"), "abc");
    Files.write(root.resolve("medium.dat"), new byte[2048]);
    var tools = tools(root);

    assertThat(success(tools.execute("glob", JSON.createObjectNode().put("query", "src*"))))
        .contains("srcdir/");
    assertThat(success(tools.execute("glob", JSON.createObjectNode().put("pattern", "*.txt")
        .put("display_description", "Finding text")))).startsWith("Finding text\nFound")
        .contains("small.txt  3B");
    assertThat(success(tools.execute("glob", JSON.createObjectNode().put("pattern", "*.dat"))))
        .contains("medium.dat  2.0K");
    assertThat(success(tools.execute("glob", JSON.createObjectNode().put("pattern", "absent"))))
        .startsWith("no matches");
    assertThat(success(tools.execute("grep", JSON.createObjectNode().put("pattern", "absent"))))
        .startsWith("No matches found");
    assertThat(success(tools.execute("grep", JSON.createObjectNode().put("pattern", "abc")
        .put("offset", 99)))).contains("Try a smaller offset");
    assertThat(success(tools.execute("find_definition", JSON.createObjectNode()
        .put("symbol", "Missing")))).contains("no definitions found");
    assertThat(failure(tools.execute("find_definition", JSON.createObjectNode()))
        .error().kind()).isEqualTo(ToolErrorKind.INVALID_ARGS);
    assertThat(failure(tools.execute("other", JSON.createObjectNode()))
        .error().kind()).isEqualTo(ToolErrorKind.UNKNOWN);
  }

  @Test
  void matchesPinnedGlobBacktrackingAndCharacterClassRules() {
    assertThat(GlobMatcher.matches("*", "anything")).isTrue();
    assertThat(GlobMatcher.matches("a?c", "abc")).isTrue();
    assertThat(GlobMatcher.matches("a?c", "ac")).isFalse();
    assertThat(GlobMatcher.matches("a*c", "abbbc")).isTrue();
    assertThat(GlobMatcher.matches("a*d", "abbbc")).isFalse();
    assertThat(GlobMatcher.matches("[a-c]at", "bat")).isTrue();
    assertThat(GlobMatcher.matches("[!a-c]at", "bat")).isFalse();
    assertThat(GlobMatcher.matches("[abc", "[abc")).isTrue();
    assertThat(GlobMatcher.matches("abc", "abcd")).isFalse();
    assertThat(GlobMatcher.matches("abcd", "abc")).isFalse();
  }

  private static SearchTools tools(Path root) {
    return new SearchTools(new WorkspaceSandbox(root, root, root));
  }

  private static String success(ToolResult result) {
    return ((ToolResult.Success) result).output().text();
  }

  private static ToolResult.Failure failure(ToolResult result) {
    return (ToolResult.Failure) result;
  }
}
