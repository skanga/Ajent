package com.github.skanga.ajent.provider;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProviderModelTest {
  @Test void rejectsNegativeContextWindow() {
    assertThatIllegalArgumentException().isThrownBy(
        () -> new ProviderModel("model", "Model", "provider", Optional.empty(), -1))
        .withMessage("contextWindow cannot be negative");
  }

  @Test void rejectsNullRequiredFields() {
    assertThatNullPointerException().isThrownBy(
        () -> new ProviderModel(null, "Model", "provider", Optional.empty(), 0));
    assertThatNullPointerException().isThrownBy(
        () -> new ProviderModel("model", null, "provider", Optional.empty(), 0));
    assertThatNullPointerException().isThrownBy(
        () -> new ProviderModel("model", "Model", null, Optional.empty(), 0));
    assertThatNullPointerException().isThrownBy(
        () -> new ProviderModel("model", "Model", "provider", null, 0));
  }
}
