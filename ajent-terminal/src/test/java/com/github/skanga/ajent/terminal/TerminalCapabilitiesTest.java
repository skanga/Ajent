package com.github.skanga.ajent.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class TerminalCapabilitiesTest {
  @Test void portsMayaSynchronizedOutputEnvironmentHeuristics() {
    assertThat(TerminalCapabilities.synchronizedOutput(Map.of())).isFalse();
    assertThat(TerminalCapabilities.synchronizedOutput(Map.of("MAYA_FORCE_SYNC", "1"))).isTrue();
    assertThat(TerminalCapabilities.synchronizedOutput(Map.of(
        "MAYA_FORCE_SYNC", "0", "WT_SESSION", "x"))).isTrue();
    assertThat(TerminalCapabilities.synchronizedOutput(Map.of(
        "MAYA_NO_SYNC", "yes", "WT_SESSION", "x"))).isFalse();
    assertThat(TerminalCapabilities.synchronizedOutput(Map.of(
        "TERM_PROGRAM", "Apple_Terminal", "TERM", "xterm-kitty"))).isFalse();
    assertThat(TerminalCapabilities.synchronizedOutput(Map.of("VTE_VERSION", "6200suffix")))
        .isTrue();
    assertThat(TerminalCapabilities.synchronizedOutput(Map.of(
        "TERM_PROGRAM", "iTerm.app", "TERM_PROGRAM_VERSION", "3.5.6"))).isTrue();
    assertThat(TerminalCapabilities.synchronizedOutput(Map.of(
        "TERM_PROGRAM", "iTerm.app", "TERM_PROGRAM_VERSION", "3.4.23"))).isFalse();
    assertThat(TerminalCapabilities.synchronizedOutput(Map.of("TERM", "tmux-256color")))
        .isFalse();
    assertThat(TerminalCapabilities.synchronizedOutput(Map.of("TERM", "xterm-foot"))).isTrue();
  }

  @Test void portsSshCadenceAndEscapeHatch() {
    assertThat(TerminalCapabilities.streamingTickPeriod(Map.of()))
        .isEqualTo(Duration.ofMillis(100));
    assertThat(TerminalCapabilities.streamingTickPeriod(Map.of("MAYA_FORCE_SYNC", "1")))
        .isEqualTo(Duration.ofMillis(33));
    assertThat(TerminalCapabilities.streamingTickPeriod(Map.of(
        "MAYA_FORCE_SYNC", "1", "SSH_CONNECTION", "")))
        .isEqualTo(Duration.ofMillis(80));
    assertThat(TerminalCapabilities.streamingTickPeriod(Map.of(
        "MAYA_FORCE_SYNC", "1", "SSH_TTY", "", "AGENTTY_NO_SSH_THROTTLE", "yes")))
        .isEqualTo(Duration.ofMillis(33));
    assertThat(TerminalCapabilities.runningOverSsh(Map.of(
        "SSH_CLIENT", "", "AGENTTY_NO_SSH_THROTTLE", "0"))).isTrue();
  }
}
