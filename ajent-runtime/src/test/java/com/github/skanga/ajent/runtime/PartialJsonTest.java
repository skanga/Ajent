package com.github.skanga.ajent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class PartialJsonTest {
  @Test void closesContainersValuesStringsAndDanglingCommasLikeAgentty() {
    assertThat(PartialJson.close("")).isEqualTo("null");
    assertThat(PartialJson.close("{\"path\":")).isEqualTo("{\"path\":null}");
    assertThat(PartialJson.close("{\"items\":[1,2,")).isEqualTo("{\"items\":[1,2]}");
    assertThat(PartialJson.close("{\"content\":\"abc")).isEqualTo("{\"content\":\"abc\"}");
    assertThat(PartialJson.close("{\"content\":\"abc\\"))
        .isEqualTo("{\"content\":\"abc\"}");
  }

  @Test void detectsOnlyAnActuallyOpenStringIncludingAHalfEscape() {
    assertThat(PartialJson.endedInsideString("{\"path\":\"half")).isTrue();
    assertThat(PartialJson.endedInsideString("{\"path\":\"half\\")).isTrue();
    assertThat(PartialJson.endedInsideString("{\"path\":\"done\"")).isFalse();
    assertThat(PartialJson.endedInsideString("{\"path\":\"a\\\"b\"")).isFalse();
    assertThat(PartialJson.endedInsideString("")).isFalse();
  }

  @Test void preservesClosedAndEscapedStructuresAndHandlesDefensiveTopLevelTails() {
    assertThat(PartialJson.close("{\"nested\":[{\"x\":1}]}"))
        .isEqualTo("{\"nested\":[{\"x\":1}]}");
    assertThat(PartialJson.close("{\"text\":\"a\\nb\\\"c\"}"))
        .isEqualTo("{\"text\":\"a\\nb\\\"c\"}");
    assertThat(PartialJson.close("{\"x\":1,   ")).isEqualTo("{\"x\":1   }");
    assertThat(PartialJson.close("value:")).isEqualTo("value:null");
    assertThat(PartialJson.close("}")).isEqualTo("}");
    assertThat(PartialJson.close("]")).isEqualTo("]");
  }
}
