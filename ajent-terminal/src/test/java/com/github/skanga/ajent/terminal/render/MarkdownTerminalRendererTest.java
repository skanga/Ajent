package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarkdownTerminalRendererTest {
  @Test
  void paragraphUsesNativeWordAndHyphenBreakBoundaries() {
    assertThat(MarkdownTerminalRenderer.render("aaaa bbbb", 6))
        .extracting(MarkdownTerminalRenderer.Line::text)
        .containsExactly("aaaa", "bbbb");
    assertThat(MarkdownTerminalRenderer.render(
        "A00-committed-scrollback-row A01-committed-scrollback-row A02-committed"
            + "-scrollback-row", 71))
        .extracting(MarkdownTerminalRenderer.Line::text)
        .containsExactly(
            "A00-committed-scrollback-row A01-committed-scrollback-row A02-committed",
            "scrollback-row");
  }
}
