package com.github.skanga.ajent.tools.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OutputBudgetTest {

  @Test
  void zeroAndFittingBudgetsLeaveOutputUntouched() {
    assertThat(OutputBudget.apply("unchanged", 0, TruncationStrategy.HEAD))
        .isEqualTo("unchanged");
    assertThat(OutputBudget.apply("unchanged", 9, TruncationStrategy.TAIL))
        .isEqualTo("unchanged");
  }

  @Test
  void headTailAndHeadTailStrategiesUseExactAjentMarkers() {
    assertThat(OutputBudget.apply("0123456789", 4, TruncationStrategy.HEAD))
        .isEqualTo("0123\n\n[... 6 chars elided — output exceeded tool's budget; "
            + "refine your request to see more ...]");
    assertThat(OutputBudget.apply("0123456789", 4, TruncationStrategy.TAIL))
        .isEqualTo("[... 6 chars elided from start — showing tail of output ...]\n\n6789");
    assertThat(OutputBudget.apply("0123456789", 5, TruncationStrategy.HEAD_TAIL))
        .isEqualTo("012\n\n[... 5 chars elided from middle ...]\n\n89");
  }

  @Test
  void everyStrategyKeepsUtf8BoundariesAndCountsBytesLikeAjent() {
    String value = "ab😀cd";
    assertThat(value.getBytes(StandardCharsets.UTF_8)).hasSize(8);
    assertThat(OutputBudget.apply(value, 4, TruncationStrategy.HEAD))
        .startsWith("ab\n\n").doesNotContain("�").contains("6 chars elided");
    assertThat(OutputBudget.apply(value, 4, TruncationStrategy.TAIL))
        .endsWith("\n\ncd").doesNotContain("�").contains("6 chars elided");
    assertThat(OutputBudget.apply("😀-middle-😀", 9, TruncationStrategy.HEAD_TAIL))
        .doesNotContain("�").contains("chars elided from middle");
  }
}
