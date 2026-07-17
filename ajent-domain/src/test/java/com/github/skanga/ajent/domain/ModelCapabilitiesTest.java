package com.github.skanga.ajent.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ModelCapabilitiesTest {
  @Test
  void hostedClaudeFamiliesAreNeverWeak() {
    assertStrong("claude-opus-4-5", "claude-sonnet-4-5-20250101", "claude-haiku-4-5",
        "claude-3-5-haiku-20241022", "claude-opus-4-5[1m]");
  }

  @Test
  void knownLocalCoderFamiliesAreWeakAtEverySize() {
    assertWeak("qwen2.5-coder:7b", "qwen2.5-coder:3b", "qwen2.5:7b", "codellama:7b",
        "deepseek-coder:6.7b", "phi3:3.8b", "gemma2:9b", "starcoder2:7b",
        "tinyllama:1.1b", "smollm2:1.7b", "qwen2.5-coder:14b", "qwen2.5-coder:32b",
        "codellama:34b", "deepseek-coder:33b");
  }

  @Test
  void toolTrainedAndLargeFamiliesAreStrongUnlessExplicitlyTiny() {
    assertStrong("llama3.1:70b", "mixtral:8x22b", "qwen3:8b", "llama3.1:8b",
        "llama3.3:70b", "mistral:7b", "mistral-small:24b", "ministral:8b",
        "command-r:35b", "hermes3:8b", "firefunction-v2", "functionary-small:7b",
        "devstral:24b", "codestral:22b", "granite3.1-dense:8b", "deepseek-v3",
        "deepseek-r1:32b");
    assertWeak("llama3.1:1b", "qwen3:1.7b", "granite3.1-moe:3b");
  }

  @Test
  void unknownModelsDefaultStrongAndBareSizeSignalsAreRespected() {
    assertStrong("gpt-4o", "gpt-4o-mini", "o1-preview", "grok-2", "",
        "some-random-hosted-model", "mystery:70b", "turbo-model", "bigbird");
    assertWeak("mystery:7b");
  }

  @Test
  void outputCapsMatchTheReferenceCatalog() {
    assertCap(64_000, "claude-sonnet-4-5", "claude-opus-4-5",
        "claude-sonnet-4-5-20250101", "claude-opus-4-5[1m]", "claude-fable-5",
        "claude-mythos-5", "claude-fable-5[1m]");
    assertCap(8_192, "claude-haiku-4-5", "claude-3-5-haiku-20241022",
        "claude-3-5-sonnet-20241022", "claude-3-opus-20240229");
    assertCap(16_384, "qwen2.5-coder:7b", "gpt-4o", "");
  }

  @Test
  void flagshipLaneHasKnownFullCapabilities() {
    for (String id : new String[] {"claude-fable-5", "claude-mythos-5"}) {
      var capabilities = ModelCapabilities.fromId(id);
      assertThat(capabilities.isKnownFamily()).isTrue();
      assertThat(capabilities.isFlagship()).isTrue();
      assertThat(capabilities.generation()).isEqualTo(5);
      assertThat(capabilities.generation4OrLater()).isTrue();
      assertThat(capabilities.isWeakToolUser()).isFalse();
      assertThat(capabilities.supportsEffort()).isTrue();
      assertThat(capabilities.supportsEffortMax()).isTrue();
      assertThat(capabilities.supportsEffortXhigh()).isTrue();
    }
    var extended = ModelCapabilities.fromId("claude-fable-5[1m]");
    assertThat(extended.isFable()).isTrue();
    assertThat(extended.extendedContext1m()).isTrue();
  }

  @Test
  void familyPredicatesAndEffortRevisionThresholdsAreExact() {
    assertThat(ModelCapabilities.fromId("claude-haiku-4-5").isHaiku()).isTrue();
    assertThat(ModelCapabilities.fromId("claude-sonnet-4-5").isSonnet()).isTrue();
    assertThat(ModelCapabilities.fromId("claude-opus-4-5").isOpus()).isTrue();
    assertThat(ModelCapabilities.fromId("claude-mythos-5").isMythos()).isTrue();
    var opus45 = ModelCapabilities.fromId("claude-opus-4-5");
    assertThat(opus45.supportsEffort()).isTrue();
    assertThat(opus45.supportsEffortMax()).isFalse();
    assertThat(opus45.supportsEffortXhigh()).isFalse();
    assertThat(ModelCapabilities.fromId("claude-opus-4-6").supportsEffortMax()).isTrue();
    assertThat(ModelCapabilities.fromId("claude-opus-4-6").supportsEffortXhigh()).isFalse();
    assertThat(ModelCapabilities.fromId("claude-opus-4-7").supportsEffortXhigh()).isTrue();
    assertThat(ModelCapabilities.fromId("claude-opus-5").supportsEffortXhigh()).isTrue();
    assertThat(ModelCapabilities.fromId("claude-sonnet-4-5").supportsEffort()).isFalse();
    assertThat(ModelCapabilities.fromId("claude-sonnet-4-6").supportsEffortMax()).isTrue();
    assertThat(ModelCapabilities.fromId("claude-sonnet-4-6").supportsEffortXhigh()).isFalse();
    assertThat(ModelCapabilities.fromId("claude-fable-4").supportsEffort()).isFalse();
    assertThat(ModelCapabilities.fromId("unknown").supportsEffortMax()).isFalse();
    assertThat(ModelCapabilities.fromId("unknown").supportsEffortXhigh()).isFalse();
  }

  @Test
  void malformedGenerationAndSizeTokensAreIgnoredLikeTheReference() {
    assertThat(ModelCapabilities.fromId(null).family()).isEqualTo(ModelCapabilities.Family.UNKNOWN);
    assertThat(ModelCapabilities.fromId("claude--opus-x-20250101").generation()).isZero();
    assertThat(ModelCapabilities.fromId("claude-opus-123").generation()).isZero();
    assertThat(ModelCapabilities.fromId("claude-opus-4-xx").revision()).isZero();
    assertStrong("model:7bf16", "word7b", "model:b", "model:.b");
    assertWeak("MODEL:7B", "model:8b-q4:3b");
    assertStrong("model:14b", "model:8x22b");
  }

  @Test
  void outputOverrideAcceptsOnlyAPositiveLeadingInteger() {
    assertThat(ModelCapabilities.maxOutputTokensFor(null, null)).isEqualTo(16_384);
    assertThat(ModelCapabilities.maxOutputTokensFor("gpt-4o", null)).isEqualTo(16_384);
    assertThat(ModelCapabilities.maxOutputTokensFor("gpt-4o", "")).isEqualTo(16_384);
    assertThat(ModelCapabilities.maxOutputTokensFor("gpt-4o", "0")).isEqualTo(16_384);
    assertThat(ModelCapabilities.maxOutputTokensFor("gpt-4o", "64000suffix")).isEqualTo(64_000);
    assertThat(ModelCapabilities.maxOutputTokensFor("gpt-4o", "not-a-number")).isEqualTo(16_384);
    assertThat(ModelCapabilities.maxOutputTokensFor("claude-opus-x", null)).isEqualTo(16_384);
    assertThat(ModelCapabilities.maxOutputTokensFor("claude-opus-3", null)).isEqualTo(8_192);
  }

  private static void assertWeak(String... ids) {
    for (String id : ids) assertThat(ModelCapabilities.isWeakModel(id)).as(id).isTrue();
  }

  private static void assertStrong(String... ids) {
    for (String id : ids) assertThat(ModelCapabilities.isWeakModel(id)).as(id).isFalse();
  }

  private static void assertCap(int expected, String... ids) {
    for (String id : ids) assertThat(ModelCapabilities.maxOutputTokensFor(id)).as(id).isEqualTo(expected);
  }
}
