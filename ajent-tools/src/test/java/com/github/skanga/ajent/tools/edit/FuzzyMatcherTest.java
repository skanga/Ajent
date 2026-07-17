package com.github.skanga.ajent.tools.edit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FuzzyMatcherTest {
  @Test void exactUniqueMatch() { assertThat(FuzzyMatcher.find("alpha\nbeta\ngamma\n", "beta")).satisfies(r -> { assertThat(r.ok()).isTrue(); assertThat(r.position()).isEqualTo(6); assertThat(r.length()).isEqualTo(4); }); }
  @Test void ambiguousMatchNeedsHint() { assertThat(FuzzyMatcher.find("foo\nfoo\nfoo\n", "foo")).satisfies(r -> { assertThat(r.ok()).isFalse(); assertThat(r.count()).isGreaterThanOrEqualTo(2); }); }
  @Test void lineHintSelectsMatch() { assertThat(FuzzyMatcher.find("fn first(){}\nfn second(){}\nfn third(){}\n", "fn second(){}", "", 1)).satisfies(r -> { assertThat(r.ok()).isTrue(); assertThat(r.position()).isEqualTo(13); assertThat(r.length()).isEqualTo(13); }); }
  @Test void typoIsTolerated() { var file = "fn foo1(a: usize) -> usize {\n    40\n}\n\nfn foo2(b: usize) -> usize {\n    42\n}\n"; assertThat(FuzzyMatcher.find(file, "fn foo1(a: usize) -> u32 {\n40\n}").position()).isZero(); }
  @Test void indentationDriftIsMatchedAndReplacementIsReindented() { var file = "class C {\n    fn m() {\n        return 1;\n    }\n}\n"; var result = FuzzyMatcher.find(file, "fn m() {\n    return 1;\n}", "fn m() {\n    return 2;\n}"); assertThat(result.ok()).isTrue(); assertThat(result.adjustedNewText()).contains("    fn m() {").contains("        return 2;"); }
  @Test void unrelatedContentIsRejected() { var result = FuzzyMatcher.find("alpha\nbeta\ngamma\n", "totally unrelated content that doesnt belong"); assertThat(result.ok()).isFalse(); assertThat(result.count()).isZero(); }
  @Test void trailingWhitespaceAndCrLfAreTolerated() { assertThat(FuzzyMatcher.find("fn first() {    \n    body();    \n}    \n", "fn first() {\n    body();\n}").ok()).isTrue(); assertThat(FuzzyMatcher.find("alpha\r\nbeta\r\ngamma\r\n", "beta").ok()).isTrue(); }
  @Test void crLfMultilineMatchesLfNeedle() { var file = "fn first() {\r\n    return 1;\r\n}\r\nfn second() {\r\n    return 2;\r\n}\r\n"; assertThat(FuzzyMatcher.find(file, "fn second() {\n    return 2;\n}").ok()).isTrue(); }
  @Test void smartQuotesAreNormalized() { assertThat(FuzzyMatcher.find("print(‘hello’)\n", "print('hello')").ok()).isTrue(); }
  @Test void dynamicProgrammingMaySkipAnInsertedLine() { var file = "class Something {\n    one() { return 1; }\n    two() { return 2222; }\n    three() { return 333; }\n    four() { return 4444; }\n    five() { return 5555; }\n    six() { return 6666; }\n    seven() { return 7; }\n    eight() { return 8; }\n}\n"; var needle = "two() { return 2222; }\nfour() { return 4444; }\nfive() { return 5555; }\nsix() { return 6666; }\n"; assertThat(FuzzyMatcher.find(file, needle).ok()).isTrue(); }
  @Test void emptyNeedleAndEmptyFilesDoNotMatch() { assertThat(FuzzyMatcher.find("abc", "").ok()).isFalse(); assertThat(FuzzyMatcher.find("", "abc").ok()).isFalse(); }
  @Test void exactMatchDoesNotAdjustReplacementIndentation() { var result = FuzzyMatcher.find("  exact\n", "exact", "replacement"); assertThat(result.ok()).isTrue(); assertThat(result.adjustedNewText()).isEmpty(); }
  @Test void oversizedDynamicProgrammingMatrixFailsConservatively() { String file = "x\n".repeat(2000); String needle = "y\n".repeat(1000); assertThat(FuzzyMatcher.find(file, needle).ok()).isFalse(); }
  @Test void smartDoubleQuotesAndDashesAreNormalized() { assertThat(FuzzyMatcher.find("say(“hi”)\n", "say(\"hi\")").ok()).isTrue(); assertThat(FuzzyMatcher.find("a—b – c\n", "a--b - c").ok()).isTrue(); }
  @Test void blankReplacementLinesRemainBlankDuringIndentAdjustment() { var result = FuzzyMatcher.find("class C {\n    a\n\n    b\n}\n", "a\n\nb", "x\n\ny"); assertThat(result.adjustedNewText()).isEqualTo("    x\n\n    y"); }
}
