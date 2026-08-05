package com.github.skanga.ajent.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EffortTest {
  @Test void wireValuesAndLabelsMatchAjent() {
    assertThat(Effort.values()).extracting(Effort::wire)
        .containsExactly("", "low", "medium", "high", "xhigh", "max");
    assertThat(Effort.values()).extracting(Effort::label)
        .containsExactly("off", "low", "medium", "high", "xhigh", "max");
    assertThat(java.util.Arrays.asList(null, "", "unknown")).allSatisfy(
        value -> assertThat(Effort.fromWire(value)).isEqualTo(Effort.NONE));
    for (Effort effort : Effort.values()) {
      if (effort != Effort.NONE) assertThat(Effort.fromWire(effort.wire())).isEqualTo(effort);
    }
  }

  @Test void availableLaddersMatchModelCapabilityCeilings() {
    assertThat(Effort.available(ModelCapabilities.fromId("gpt-5"))).isEmpty();
    assertThat(Effort.available(ModelCapabilities.fromId("claude-opus-4-5")))
        .containsExactly(Effort.NONE, Effort.LOW, Effort.MEDIUM, Effort.HIGH);
    assertThat(Effort.available(ModelCapabilities.fromId("claude-sonnet-4-6")))
        .containsExactly(Effort.NONE, Effort.LOW, Effort.MEDIUM, Effort.HIGH, Effort.MAX);
    assertThat(Effort.available(ModelCapabilities.fromId("claude-opus-4-7")))
        .containsExactly(Effort.NONE, Effort.LOW, Effort.MEDIUM, Effort.HIGH,
            Effort.XHIGH, Effort.MAX);
  }

  @Test void cycleWrapsInBothDirectionsAndUnsupportedModelsReturnOff() {
    var opus45 = ModelCapabilities.fromId("claude-opus-4-5");
    assertThat(Effort.NONE.cycle(1, opus45)).isEqualTo(Effort.LOW);
    assertThat(Effort.NONE.cycle(-1, opus45)).isEqualTo(Effort.HIGH);
    assertThat(Effort.LOW.cycle(7, opus45)).isEqualTo(Effort.NONE);
    assertThat(Effort.MAX.cycle(1, opus45)).isEqualTo(Effort.LOW);
    assertThat(Effort.HIGH.cycle(1, ModelCapabilities.fromId("gpt-5")))
        .isEqualTo(Effort.NONE);
  }

  @Test void clampDegradesUnsupportedStoredTiersExactly() {
    assertThat(Effort.MAX.clamp(ModelCapabilities.fromId("claude-opus-4-5")))
        .isEqualTo(Effort.HIGH);
    assertThat(Effort.XHIGH.clamp(ModelCapabilities.fromId("claude-sonnet-4-6")))
        .isEqualTo(Effort.HIGH);
    assertThat(Effort.HIGH.clamp(ModelCapabilities.fromId("gpt-5")))
        .isEqualTo(Effort.NONE);
    assertThat(Effort.NONE.clamp(ModelCapabilities.fromId("claude-opus-4-7")))
        .isEqualTo(Effort.NONE);
    assertThat(Effort.MAX.clamp(ModelCapabilities.fromId("claude-opus-4-7")))
        .isEqualTo(Effort.MAX);
  }
}
