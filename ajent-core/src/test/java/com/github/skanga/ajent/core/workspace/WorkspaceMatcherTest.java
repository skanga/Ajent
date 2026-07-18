package com.github.skanga.ajent.core.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorkspaceMatcherTest {
  private static final List<String> FILES = List.of(
      ".env", "assets/Foo.png", "docs/Foo.md", "src/deep/Foo.java", "src/FooBar.java",
      "src/other.java", "Makefile");

  @Test void emptyAndWhitespaceQueriesPreserveAlphabeticSnapshotOrder() {
    assertThat(WorkspaceMatcher.filterFiles(FILES, ""))
        .containsExactly(0, 1, 2, 3, 4, 5, 6);
    assertThat(WorkspaceMatcher.filterFiles(FILES, " \t"))
        .containsExactly(0, 1, 2, 3, 4, 5, 6);
  }

  @Test void fuzzyRankingRewardsBasenamePrefixSourceClassAndShallowPaths() {
    assertThat(WorkspaceMatcher.filterFiles(FILES, "foo"))
        .containsExactly(4, 3, 2, 1);
    assertThat(WorkspaceMatcher.filterFiles(FILES, "sfb")).containsExactly(4);
    assertThat(WorkspaceMatcher.filterFiles(FILES, "missing")).isEmpty();
  }

  @Test void scoringHandlesCamelHumpsBackslashesAndHiddenBuildFiles() {
    assertThat(WorkspaceMatcher.fuzzyScore("src/FooBar.java", "fb"))
        .isGreaterThan(WorkspaceMatcher.fuzzyScore("src/foo/bare.java", "fb"));
    assertThat(WorkspaceMatcher.fuzzyScore("src\\Foo.java", "foo"))
        .isGreaterThan(Integer.MIN_VALUE);
    assertThat(WorkspaceMatcher.fuzzyScore("Makefile", "make"))
        .isGreaterThan(WorkspaceMatcher.fuzzyScore(".make", "make"));
  }

  @Test void symbolFilterIsCaseInsensitiveNameOnlySubstringInSourceOrder() {
    var symbols = List.of(new WorkspaceSymbol("AlphaService", "src/Z.java", 3),
        new WorkspaceSymbol("alphabet", "src/A.java", 8),
        new WorkspaceSymbol("Other", "alpha/path.java", 1));
    assertThat(WorkspaceMatcher.filterSymbols(symbols, "ALPHA")).containsExactly(0, 1);
    assertThat(WorkspaceMatcher.filterSymbols(symbols, "")).containsExactly(0, 1, 2);
    assertThat(WorkspaceMatcher.filterSymbols(symbols, "none")).isEmpty();
  }
}
