package com.github.skanga.ajent.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReasoningPolicyTest {
  @Test void codexSupportsNormalReasoningEffortsAndBackendDefault() {
    ReasoningPolicy policy = ReasoningPolicy.forModel("codex", "gpt-5.6-codex");
    assertThat(policy.available()).containsExactly(
        Effort.NONE, Effort.LOW, Effort.MEDIUM, Effort.HIGH);
    assertThat(policy.clamp(Effort.MAX)).isEqualTo(Effort.HIGH);
    assertThat(policy.clamp(Effort.NONE)).isEqualTo(Effort.NONE);
  }

  @Test void codexMiniClampsLowAndExtendedEfforts() {
    ReasoningPolicy policy = ReasoningPolicy.forModel("codex", "codex-mini-latest");
    assertThat(policy.available()).containsExactly(
        Effort.NONE, Effort.MEDIUM, Effort.HIGH);
    assertThat(policy.clamp(Effort.LOW)).isEqualTo(Effort.MEDIUM);
    assertThat(policy.clamp(Effort.XHIGH)).isEqualTo(Effort.HIGH);
  }

  @Test void onlyKnownCodexModelsExposeXhigh() {
    assertThat(ReasoningPolicy.forModel("codex", "gpt-5.3-codex-spark").available())
        .contains(Effort.XHIGH);
    assertThat(ReasoningPolicy.forModel("codex", "gpt-5.6-sol").available())
        .doesNotContain(Effort.XHIGH);
  }

  @Test void anthropicDelegatesExistingCapabilitiesAndOtherProvidersDisableEffort() {
    assertThat(ReasoningPolicy.forModel("anthropic", "claude-opus-4-7").available())
        .containsExactly(Effort.NONE, Effort.LOW, Effort.MEDIUM, Effort.HIGH,
            Effort.XHIGH, Effort.MAX);
    assertThat(ReasoningPolicy.forModel("openai", "gpt-5").available()).isEmpty();
    assertThat(ReasoningPolicy.forModel("ollama", "qwen").cycle(Effort.HIGH, 1))
        .isEqualTo(Effort.NONE);
    assertThat(new ReasoningPolicy(List.of(), Effort.NONE).clamp(Effort.HIGH))
        .isEqualTo(Effort.NONE);
  }

  @Test void cyclingUsesTheProviderSpecificList() {
    ReasoningPolicy policy = ReasoningPolicy.forModel("codex", "gpt-5.6-codex");
    assertThat(policy.cycle(Effort.HIGH, 1)).isEqualTo(Effort.NONE);
    assertThat(policy.cycle(Effort.NONE, -1)).isEqualTo(Effort.HIGH);
  }
}
