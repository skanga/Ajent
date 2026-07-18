package com.github.skanga.ajent.terminal.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.Effort;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ModelProviderPickerTest {
  private static final List<ModelPicker.Model> MODELS = List.of(
      new ModelPicker.Model("opus", "Claude Opus", false),
      new ModelPicker.Model("sonnet", "Claude Sonnet", true),
      new ModelPicker.Model("gpt", "GPT 5", false),
      new ModelPicker.Model("local", "Llama 🙂", false));

  @Test void opensOnActiveModelAndWrapsThroughVisibleRows() {
    PickerState.OneAxis state = ModelPicker.open(MODELS, "sonnet");
    assertThat(state).isEqualTo(new PickerState.OpenAt(1, ""));
    state = ModelPicker.move(state, MODELS, -2);
    assertThat(state).isEqualTo(new PickerState.OpenAt(3, ""));
    state = ModelPicker.move(new PickerState.OpenAt(0, "Claude"), MODELS, -1);
    assertThat(state).isEqualTo(new PickerState.OpenAt(1, "Claude"));
  }

  @Test void filtersUnicodeBackspacesOneCodepointAndResolvesVisibleIndex() {
    PickerState.OneAxis state = new PickerState.OpenAt(0, "");
    state = ModelPicker.input(state, MODELS, 0x1f642);
    assertThat(state).isEqualTo(new PickerState.OpenAt(0, "🙂"));
    assertThat(ModelPicker.select(state, MODELS).model()).contains(MODELS.get(3));
    assertThat(ModelPicker.backspace(state, MODELS)).isEqualTo(new PickerState.OpenAt(0, ""));
    assertThat(ModelPicker.select(new PickerState.OpenAt(1, "Claude"), MODELS).model())
        .contains(MODELS.get(1));
  }

  @Test void jumpsByNativeFourteenRowPageAndClamps() {
    var many = java.util.stream.IntStream.range(0, 30)
        .mapToObj(index -> new ModelPicker.Model("m" + index, "Model " + index, false)).toList();
    PickerState.OneAxis state = new PickerState.OpenAt(20, "");
    assertThat(ModelPicker.jump(state, many, ModelPicker.Jump.PAGE_UP))
        .isEqualTo(new PickerState.OpenAt(6, ""));
    assertThat(ModelPicker.jump(state, many, ModelPicker.Jump.PAGE_DOWN))
        .isEqualTo(new PickerState.OpenAt(29, ""));
    assertThat(ModelPicker.jump(state, many, ModelPicker.Jump.HOME))
        .isEqualTo(new PickerState.OpenAt(0, ""));
  }

  @Test void togglesTheRealModelBehindAFilteredCursor() {
    var result = ModelPicker.toggleFavorite(new PickerState.OpenAt(1, "Claude"), MODELS);
    assertThat(result.models().get(1).favorite()).isFalse();
    assertThat(result.models().get(0).favorite()).isFalse();
  }

  @Test void cyclesEffortForTheHighlightedModelsCapabilities() {
    var models = List.of(
        new ModelPicker.Model("claude-opus-4-7", "Opus", false),
        new ModelPicker.Model("claude-opus-4-5", "Old Opus", false),
        new ModelPicker.Model("gpt-5", "GPT", false));
    assertThat(ModelPicker.cycleEffort(new PickerState.OpenAt(0, ""), models,
        Effort.MAX, 1)).isEqualTo(Effort.NONE);
    assertThat(ModelPicker.cycleEffort(new PickerState.OpenAt(1, ""), models,
        Effort.HIGH, 1)).isEqualTo(Effort.NONE);
    assertThat(ModelPicker.cycleEffort(new PickerState.OpenAt(2, ""), models,
        Effort.HIGH, 1)).isEqualTo(Effort.NONE);
    assertThat(ModelPicker.cycleEffort(new PickerState.Closed(), models,
        Effort.HIGH, 1)).isEqualTo(Effort.HIGH);
  }

  @Test void providerPickerAddsCustomHostRowAndReturnsTypedActions() {
    var providers = List.of(new ProviderPicker.Provider("anthropic", "Anthropic"),
        new ProviderPicker.Provider("ollama", "Ollama"));
    PickerState.OneAxis state = ProviderPicker.open(providers, "ollama");
    assertThat(state).isEqualTo(new PickerState.OpenAt(1, ""));
    state = ProviderPicker.move(state, providers, 1);
    assertThat(ProviderPicker.select(state, providers).action())
        .contains(new ProviderPicker.EnterCustomHost());
    state = ProviderPicker.move(new PickerState.OpenAt(0, ""), providers, -1);
    assertThat(state).isEqualTo(new PickerState.OpenAt(2, ""));
    assertThat(ProviderPicker.select(new PickerState.OpenAt(0, ""), providers).action())
        .contains(new ProviderPicker.SelectProvider(providers.getFirst()));
  }
}
