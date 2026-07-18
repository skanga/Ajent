package com.github.skanga.ajent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import com.github.skanga.ajent.tools.runtime.ToolRuntimeFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveProviderFactoryTest {
  @Test
  void buildsHostedAnthropicRequestsWithFullPromptCatalogAndModelCeiling(@TempDir Path root)
      throws Exception {
    var components = components(root);
    var configuration = new LiveProviderFactory.Configuration(
        "anthropic", "claude-opus-4-5", new ProviderAuth.Bearer("oauth"), "high",
        components.systemPrompt(), 200_000, Map.of());

    var request = LiveProviderFactory.request(configuration,
        List.of(new Message(Role.USER, "hello", List.of(), List.of())));

    assertThat(request).isInstanceOfSatisfying(HttpProviderPort.Request.Anthropic.class, value -> {
      assertThat(value.value().model()).isEqualTo("claude-opus-4-5");
      assertThat(value.value().maxTokens()).isEqualTo(64_000);
      assertThat(value.value().effort()).isEqualTo("high");
      assertThat(value.value().systemPrompt()).startsWith("You are ajent");
      assertThat(value.value().tools()).hasSize(23);
      assertThat(value.value().auth()).isEqualTo(new ProviderAuth.Bearer("oauth"));
    });
  }

  @Test
  void routesOpenAiCompatAndNativeOllamaAndHidesFootgunsForWeakModels(@TempDir Path root)
      throws Exception {
    var components = components(root);
    var openAi = new LiveProviderFactory.Configuration(
        "groq", "llama-3.1-70b", new ProviderAuth.ApiKey("key"), "",
        components.systemPrompt(), 0, Map.of());
    var ollama = new LiveProviderFactory.Configuration(
        "ollama", "qwen2.5-coder:7b", new ProviderAuth.Empty(), "",
        components.systemPrompt(), 32_768, Map.of());

    assertThat(LiveProviderFactory.request(openAi, List.of()))
        .isInstanceOfSatisfying(HttpProviderPort.Request.OpenAi.class, value -> {
          assertThat(value.value().endpoint().host()).isEqualTo("api.groq.com");
          assertThat(value.value().jsonProtocol()).isFalse();
          assertThat(value.value().tools()).hasSize(23);
        });
    assertThat(LiveProviderFactory.request(ollama, List.of()))
        .isInstanceOfSatisfying(HttpProviderPort.Request.Ollama.class, value -> {
          assertThat(value.value().endpoint().nativeApi()).isTrue();
          assertThat(value.value().jsonProtocol()).isTrue();
          assertThat(value.value().contextWindow()).isEqualTo(32_768);
          assertThat(value.value().tools()).extracting(tool -> tool.name())
              .doesNotContain("skill", "remember", "forget", "wipe_memory")
              .hasSize(19);
        });
  }

  @Test
  void defaultsBlankProviderAndModelAndHonorsOutputOverride(@TempDir Path root) throws Exception {
    var components = components(root);
    var configuration = new LiveProviderFactory.Configuration(
        "", "", new ProviderAuth.Empty(), "", components.systemPrompt(), 0,
        Map.of("AGENTTY_MAX_OUTPUT_TOKENS", "12345suffix"));

    assertThat(LiveProviderFactory.request(configuration, List.of()))
        .isInstanceOfSatisfying(HttpProviderPort.Request.Anthropic.class, value -> {
          assertThat(value.value().model()).isEqualTo("claude-opus-4-5");
          assertThat(value.value().maxTokens()).isEqualTo(12_345);
        });
  }

  @Test
  void clampsPersistedEffortAtRequestTimeForTheSelectedModel(@TempDir Path root) throws Exception {
    var components = components(root);
    var oldOpus = new LiveProviderFactory.Configuration(
        "anthropic", "claude-opus-4-5", new ProviderAuth.Empty(), "max",
        components.systemPrompt(), 0, Map.of());
    var unsupported = new LiveProviderFactory.Configuration(
        "anthropic", "claude-haiku-4-5", new ProviderAuth.Empty(), "high",
        components.systemPrompt(), 0, Map.of());

    assertThat(LiveProviderFactory.request(oldOpus, List.of()))
        .isInstanceOfSatisfying(HttpProviderPort.Request.Anthropic.class,
            request -> assertThat(request.value().effort()).isEqualTo("high"));
    assertThat(LiveProviderFactory.request(unsupported, List.of()))
        .isInstanceOfSatisfying(HttpProviderPort.Request.Anthropic.class,
            request -> assertThat(request.value().effort()).isEmpty());
  }

  private static ToolRuntimeFactory.Components components(Path root) throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Path home = Files.createDirectories(root.resolve("home"));
    return ToolRuntimeFactory.compose(ToolRuntimeFactory.Configuration.standalone(workspace, home));
  }
}
