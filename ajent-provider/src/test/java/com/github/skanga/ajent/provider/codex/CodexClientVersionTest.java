package com.github.skanga.ajent.provider.codex;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CodexClientVersionTest {
  @Test
  void extractsSemanticVersionWithoutTrustingOtherOutput() {
    assertThat(CodexClientVersion.normalize("codex-cli 0.121.0")).isEqualTo("0.121.0");
    assertThat(CodexClientVersion.normalize("not a version")).isNull();
  }
}
