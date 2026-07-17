package com.github.skanga.ajent.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MachineSeedTest {

  @Test
  void windowsSeedMatchesAgenTTYMachineGuidAndUsernameComposition() {
    assertThat(MachineSeed.windows(
        Optional.of("machine-guid"), Optional.of("fallback-host"),
        Map.of("USERNAME", "alice")))
        .isEqualTo("machine-guid\u001falice\u001fagentty-credentials-v1");
    assertThat(MachineSeed.windows(
        Optional.empty(), Optional.of("computer"), Map.of("USERNAME", "bob")))
        .isEqualTo("computer\u001fbob\u001fagentty-credentials-v1");
  }

  @Test
  void windowsFallbackAndUnixUidBindingMatchAgenTTY() {
    assertThat(MachineSeed.windows(Optional.empty(), Optional.empty(), Map.of()))
        .isEqualTo("agentty-fallback-seed\u001fagentty-credentials-v1");
    assertThat(MachineSeed.unix(Optional.of("machine-id"), Optional.of("host"), 1001))
        .isEqualTo("machine-id\u001f1001\u001fagentty-credentials-v1");
    assertThat(MachineSeed.unix(Optional.empty(), Optional.of("host"), 42))
        .isEqualTo("host\u001f42\u001fagentty-credentials-v1");
    assertThat(MachineSeed.unix(Optional.empty(), Optional.empty(), 0))
        .isEqualTo("\u001f0\u001fagentty-credentials-v1");
    assertThat(MachineSeed.windows(
        Optional.empty(), Optional.empty(), Map.of("USERNAME", "")))
        .isEqualTo("\u001f\u001fagentty-credentials-v1");
  }

  @Test
  void currentSeedUsesTheNativeMachineDiscoveryPath() {
    assertThat(MachineSeed.current())
        .isNotBlank()
        .endsWith("\u001fagentty-credentials-v1");
  }
}
