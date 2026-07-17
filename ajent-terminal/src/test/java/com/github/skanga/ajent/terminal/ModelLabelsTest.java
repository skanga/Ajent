package com.github.skanga.ajent.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ModelLabelsTest {
  @ParameterizedTest(name = "{0} -> {1}")
  @MethodSource("referenceCases")
  void prettyLabelsMatchEveryReferenceCase(String modelId, String expected) {
    assertThat(ModelLabels.pretty(modelId)).isEqualTo(expected);
  }

  private static Stream<Arguments> referenceCases() {
    return Stream.of(
        Arguments.of("codellama:latest", "Codellama"),
        Arguments.of("llama3.2:latest", "Llama3.2"),
        Arguments.of("qwen2.5-coder:7b", "Qwen2.5 Coder 7b"),
        Arguments.of("llama3.1:70b", "Llama3.1 70b"),
        Arguments.of("mixtral:8x7b", "Mixtral 8x7b"),
        Arguments.of("phi3:3.8b", "Phi3 3.8b"),
        Arguments.of("deepseek-coder:6.7b", "Deepseek Coder 6.7b"),
        Arguments.of("gemma2:9b", "Gemma2 9b"),
        Arguments.of("gpt-4o", "GPT 4o"),
        Arguments.of("gpt-4o-mini", "GPT 4o Mini"),
        Arguments.of("gpt-5", "GPT 5"),
        Arguments.of("o4-mini", "o4 Mini"),
        Arguments.of("chatgpt-4o-latest", "Chatgpt 4o Latest"),
        Arguments.of("openai/gpt-4o-mini", "GPT 4o Mini"),
        Arguments.of("anthropic/claude-3-haiku", "Claude 3 Haiku"),
        Arguments.of("meta-llama/Llama-3.1-8B", "Llama 3.1 8B"),
        Arguments.of("google/gemini-2.0-flash", "Gemini 2.0 Flash"),
        Arguments.of("gemini-1.5-pro", "Gemini 1.5 Pro"),
        Arguments.of("grok-2", "Grok 2"),
        Arguments.of("grok-beta", "Grok Beta"),
        Arguments.of("deepseek-r1", "Deepseek R1"),
        Arguments.of("deepseek-chat", "Deepseek Chat"),
        Arguments.of("claude-sonnet-4-5[1m]", "Claude Sonnet 4 5"),
        Arguments.of("gpt-4o[1m]", "GPT 4o"),
        Arguments.of("glm-4-9b", "GLM 4 9b"),
        Arguments.of("Llama-3.1-8B-Instruct", "Llama 3.1 8B Instruct"),
        Arguments.of("", ""),
        Arguments.of(":latest", ""),
        Arguments.of("model", "Model"),
        Arguments.of("a", "A"));
  }
}
