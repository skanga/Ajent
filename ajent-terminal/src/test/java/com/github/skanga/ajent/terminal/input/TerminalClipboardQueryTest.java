package com.github.skanga.ajent.terminal.input;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TerminalClipboardQueryTest {
  @Test void emitsKittyMultiMimeQueryForLocalAndSshFingerprints() {
    assertThat(TerminalClipboardQuery.forEnvironment(Map.of("KITTY_WINDOW_ID", "1")))
        .isEqualTo(TerminalClipboardQuery.OSC_5522_KITTY)
        .contains("type=read;")
        .endsWith("\u001b\\");
    assertThat(TerminalClipboardQuery.forEnvironment(Map.of("TERM", "xterm-kitty")))
        .isEqualTo(TerminalClipboardQuery.OSC_5522_KITTY);
    assertThat(TerminalClipboardQuery.forEnvironment(
        Map.of("KITTY_WINDOW_ID", "", "TERM", "tmux-kitty-wrapper")))
        .isEqualTo(TerminalClipboardQuery.OSC_5522_KITTY);
  }

  @Test void emitsWidelySupportedTextQueryForOtherTerminals() {
    assertThat(TerminalClipboardQuery.forEnvironment(Map.of()))
        .isEqualTo("\u001b]52;c;?\u001b\\");
    assertThat(TerminalClipboardQuery.forEnvironment(Map.of(
        "KITTY_WINDOW_ID", "", "TERM", "xterm-256color")))
        .isEqualTo(TerminalClipboardQuery.OSC_52_TEXT);
  }
}
