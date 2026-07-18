package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class StreamingMarkdownTest {
  @Test
  void classifiesOnlyConformantGfmTablesAsTables() {
    String good = """
        Source layout (src/):

        | Dir | Likely role |
        |-----|-------------|
        | airgap/ | SSH airgap / SOCKS5 relay |
        | diff/ | Diff rendering for edits |
        """;
    String malformed = """
        Source layout (src/): | Dir | Likely role | airgap/ | SSH airgap |
        |---|---|
        | diff/ | Diff rendering |
        """;

    assertThat(kinds(good)).contains(StreamingMarkdown.BlockKind.TABLE);
    assertThat(kinds(malformed)).doesNotContain(StreamingMarkdown.BlockKind.TABLE);
  }

  @Test
  void rendersGfmBlocksAndInlineStylesForTheTerminal() {
    String source = """
        # Heading

        Plain **bold** *italic* ~~gone~~ [link](https://example.com) and `code`.

        - [x] shipped
        - pending

        > quoted text

        ```java
        int answer = 42;
        ```

        | Name | State |
        |------|-------|
        | Ajent | ready |
        """;

    var lines = MarkdownTerminalRenderer.render(source, 60);

    assertThat(lines).extracting(MarkdownTerminalRenderer.Line::text)
        .contains("Heading", "Plain bold italic gone link and code.",
            "  [x] shipped", "  • pending", "│ quoted text", "int answer = 42;")
        .anyMatch(line -> line.contains("┌") && line.contains("┬"))
        .anyMatch(line -> line.contains("Ajent") && line.contains("ready"));
    MarkdownTerminalRenderer.Line heading = lines.stream()
        .filter(line -> line.text().equals("Heading")).findFirst().orElseThrow();
    assertThat(heading.spans()).allSatisfy(span -> {
      assertThat(span.style().bold()).isTrue();
      assertThat(span.style().foreground()).isEqualTo(TerminalColor.cyan());
    });
    MarkdownTerminalRenderer.Line prose = lines.stream()
        .filter(line -> line.text().startsWith("Plain")).findFirst().orElseThrow();
    assertThat(prose.spans()).anySatisfy(span -> {
      assertThat(span.text()).isEqualTo("bold");
      assertThat(span.style().bold()).isTrue();
    }).anySatisfy(span -> {
      assertThat(span.text()).isEqualTo("italic");
      assertThat(span.style().italic()).isTrue();
    }).anySatisfy(span -> {
      assertThat(span.text()).isEqualTo("gone");
      assertThat(span.style().strikethrough()).isTrue();
    }).anySatisfy(span -> {
      assertThat(span.text()).isEqualTo("link");
      assertThat(span.style().underline()).isTrue();
    }).anySatisfy(span -> {
      assertThat(span.text()).isEqualTo("code");
      assertThat(span.style().foreground()).isEqualTo(TerminalColor.cyan());
    });
  }

  @Test
  void rejectsNonpositiveRenderWidth() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> MarkdownTerminalRenderer.render("text", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exposesNativeBlockKindsSourceExtentsAndIncrementalLifecycle() {
    String source = """
        paragraph

        ## heading

        > quote

        3. item

        ---

            indented code

        | A | B |
        |---|---|
        | 1 | 2 |
        """;
    var markdown = new StreamingMarkdown();
    markdown.setLive(true);
    markdown.append(source.substring(0, source.length() / 2));
    markdown.append(source.substring(source.length() / 2));

    assertThat(markdown.isLive()).isTrue();
    assertThat(markdown.content()).isEqualTo(source);
    assertThat(markdown.blocks()).extracting(StreamingMarkdown.Block::kind)
        .containsExactly(StreamingMarkdown.BlockKind.PARAGRAPH,
            StreamingMarkdown.BlockKind.HEADING,
            StreamingMarkdown.BlockKind.BLOCKQUOTE,
            StreamingMarkdown.BlockKind.LIST,
            StreamingMarkdown.BlockKind.HORIZONTAL_RULE,
            StreamingMarkdown.BlockKind.CODE_BLOCK,
            StreamingMarkdown.BlockKind.TABLE);
    assertThat(markdown.blocks()).allSatisfy(block ->
        assertThat(source.substring(block.sourceStart(), block.sourceEnd()))
            .isEqualTo(block.text()));
    markdown.finish();
    assertThat(markdown.isLive()).isFalse();
  }

  @Test
  void rendersOrderedUncheckedIndentedRuleWrappingAndUnicode() {
    String source = """
            indented 跳 code

        7. first item with enough words to wrap
        8. second

        - [ ] waiting

        ---
        """;

    var lines = MarkdownTerminalRenderer.render(source, 18);

    assertThat(lines).extracting(MarkdownTerminalRenderer.Line::text)
        .contains("  7. first item", "     with enough", "  8. second", "  [ ] waiting",
            "indented 跳 code", "─".repeat(18));
    assertThat(lines).allSatisfy(line -> assertThat(
        UnicodeWidth.stringWidth(line.text(), UnicodeWidth.Mode.MODERN))
        .isLessThanOrEqualTo(18));
  }

  @Test
  void valueObjectsRejectInvalidOrNullState() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> new StreamingMarkdown.Block(StreamingMarkdown.BlockKind.PARAGRAPH, 2, 1, ""))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> new MarkdownTerminalRenderer.Span("x", null))
        .isInstanceOf(NullPointerException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> MarkdownTerminalRenderer.render((String) null, 10))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void liveMarkdownRevealKeepsShapeAndStylesWhileContentGlidesIn() {
    String source = """
        ## Live heading

        A **bold** paragraph that reveals progressively.

        | Key | Value |
        |-----|-------|
        | one | two |
        """;
    var reveal = new TextReveal(90, 0.3, 0.2);
    TextReveal.Frame initial = reveal.begin(source, true, 0);

    var first = MarkdownTerminalRenderer.render(initial, 44);
    TextReveal.Frame advanced = reveal.update(source, true, 100_000_000);
    var later = MarkdownTerminalRenderer.render(advanced, 44);
    TextReveal.Frame settled = reveal.update(source, false, 1_000_000_000);
    var complete = MarkdownTerminalRenderer.render(settled, 44);

    assertThat(first).hasSameSizeAs(later).hasSameSizeAs(complete);
    assertThat(first).extracting(MarkdownTerminalRenderer.Line::text)
        .noneMatch(line -> line.contains("##") || line.contains("|-----|"));
    assertThat(nonBlankContent(first)).isLessThan(nonBlankContent(later));
    assertThat(complete).isEqualTo(MarkdownTerminalRenderer.render(source, 44));
    assertThat(first).allSatisfy(line -> assertThat(
        UnicodeWidth.stringWidth(line.text(), UnicodeWidth.Mode.MODERN))
        .isLessThanOrEqualTo(44));
  }

  @Test
  void statefulWidgetResetsHeightOnReplacementAndWidthChange() {
    var markdown = new StreamingMarkdown();
    markdown.setLive(true);
    markdown.setContent("one two three four five six seven eight nine ten");
    assertThat(markdown.render(10, 0)).hasSizeGreaterThan(1);

    markdown.setContent("x");
    assertThat(markdown.render(10, 16_000_000)).hasSize(1);
    markdown.setContent("x plus enough words to wrap this row again");
    assertThat(markdown.render(10, 32_000_000)).hasSizeGreaterThan(1);
    assertThat(markdown.render(80, 48_000_000)).hasSize(1);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> markdown.render(0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static long nonBlankContent(java.util.List<MarkdownTerminalRenderer.Line> lines) {
    return lines.stream().flatMap(line -> line.text().codePoints().boxed())
        .filter(codePoint -> !Character.isWhitespace(codePoint))
        .filter(codePoint -> codePoint < 0x2500 || codePoint > 0x259f)
        .count();
  }

  private static java.util.List<StreamingMarkdown.BlockKind> kinds(String source) {
    var markdown = new StreamingMarkdown();
    markdown.setContent(source);
    markdown.finish();
    return markdown.blocks().stream().map(StreamingMarkdown.Block::kind).toList();
  }
}
