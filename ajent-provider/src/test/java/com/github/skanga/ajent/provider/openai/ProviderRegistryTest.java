package com.github.skanga.ajent.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.provider.auth.ProviderAuth;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderRegistryTest {
  @Test
  void keepsCodexSubscriptionSeparateFromOpenAiApiKeyProvider() {
    var codex = ProviderRegistry.presetFor("codex").orElseThrow();
    var openAi = ProviderRegistry.presetFor("openai").orElseThrow();

    assertThat(codex.kind()).isEqualTo(ProviderRegistry.Kind.CODEX);
    assertThat(codex.authStyle()).isEqualTo(ProviderRegistry.AuthStyle.CODEX_IMPORT);
    assertThat(openAi.kind()).isEqualTo(ProviderRegistry.Kind.OPENAI);
    assertThat(openAi.authStyle()).isEqualTo(ProviderRegistry.AuthStyle.API_KEY);
  }
  @Test
  void endpointPresetsMatchEveryReferencePathAndTlsChoice() {
    assertEndpoint("groq", "api.groq.com", 443, true, "/openai/v1/chat/completions");
    var openrouter = Endpoint.fromSpec("openrouter");
    assertThat(openrouter.host()).isEqualTo("openrouter.ai");
    assertThat(openrouter.path()).isEqualTo("/api/v1/chat/completions");
    assertThat(openrouter.modelsPath()).isEqualTo("/api/v1/models");
    assertThat(openrouter.nativeApi()).isFalse();
    assertEndpoint("together", "api.together.xyz", 443, true, "/v1/chat/completions");
    assertEndpoint("cerebras", "api.cerebras.ai", 443, true, "/v1/chat/completions");
    assertEndpoint("ollama", "localhost", 11434, false, "/api/chat");
    assertThat(Endpoint.fromSpec("ollama").nativeApi()).isTrue();
    assertEndpoint("llama.cpp", "localhost", 8080, false, "/v1/chat/completions");
    assertThat(Endpoint.fromSpec("llama.cpp").nativeApi()).isFalse();

    var custom = Endpoint.fromSpec("my.host:8080");
    assertThat(custom.host()).isEqualTo("my.host");
    assertThat(custom.port()).isEqualTo(8080);
    assertThat(custom.useTls()).isFalse();
    assertThat(custom.label()).isEqualTo("my.host:8080");

    assertThat(Endpoint.fromSpec("").host()).isEqualTo("api.openai.com");
    assertThat(Endpoint.fromSpec("").useTls()).isTrue();
  }

  @Test
  void everyOpenAiRegistryPresetResolvesConsistently() {
    assertThat(ProviderRegistry.presets()).hasSize(9);
    assertThat(ProviderRegistry.defaultProviderId()).isEqualTo("anthropic");
    for (var preset : ProviderRegistry.presets()) {
      assertThat(ProviderRegistry.presetFor(preset.id())).contains(preset);
      if (preset.kind() != ProviderRegistry.Kind.OPENAI) continue;
      var endpoint = Endpoint.fromSpec(preset.id());
      assertThat(endpoint.host()).isNotEmpty();
      assertThat(endpoint.path()).isNotEmpty();
      assertThat(endpoint.modelsPath()).isNotEmpty();
      assertThat(endpoint.label()).isEqualTo(preset.id());
      assertThat(endpoint.useTls()).isEqualTo(!preset.local());
      if (!preset.local()) assertThat(endpoint.port()).isEqualTo(443);
    }
    assertThat(ProviderRegistry.presetFor("unknown")).isEmpty();
  }

  @Test
  void authResolutionMatchesPresetAndPrecedenceRules() {
    ProviderAuth anthropic = new ProviderAuth.Bearer("anthropic-oauth-token");
    Map<String, String> noEnvironment = Map.of();
    for (var preset : ProviderRegistry.presets()) {
      var auth = ProviderAuthResolver.resolve(
          preset.id(), anthropic, "", "", noEnvironment);
      if (preset.kind() == ProviderRegistry.Kind.ANTHROPIC) {
        assertThat(auth).isEqualTo(anthropic);
      } else {
        assertThat(auth.isEmpty()).isTrue();
      }
    }

    assertThat(ProviderAuthResolver.resolve(
        "openrouter", anthropic, "", "sk-saved", noEnvironment))
        .isEqualTo(new ProviderAuth.ApiKey("sk-saved"));
    assertThat(ProviderAuthResolver.resolve(
        "groq", anthropic, "sk-cli", "sk-saved", noEnvironment))
        .isEqualTo(new ProviderAuth.ApiKey("sk-cli"));
    assertThat(ProviderAuthResolver.resolve(
        "ollama", anthropic, "", "sk-ignored", noEnvironment).isEmpty()).isTrue();
  }

  @Test
  void providerSpecificEnvironmentKeyPrecedesGenericFallback() {
    ProviderAuth anthropic = new ProviderAuth.Empty();
    var environment = Map.of("GROQ_API_KEY", "groq", "OPENAI_API_KEY", "generic");
    assertThat(ProviderAuthResolver.resolve("groq", anthropic, "", "", environment))
        .isEqualTo(new ProviderAuth.ApiKey("groq"));
    assertThat(ProviderAuthResolver.resolve(
        "openrouter", anthropic, "", "", environment))
        .isEqualTo(new ProviderAuth.ApiKey("generic"));
  }

  private static void assertEndpoint(
      String spec, String host, int port, boolean tls, String path) {
    var endpoint = Endpoint.fromSpec(spec);
    assertThat(endpoint.host()).isEqualTo(host);
    assertThat(endpoint.port()).isEqualTo(port);
    assertThat(endpoint.useTls()).isEqualTo(tls);
    assertThat(endpoint.path()).isEqualTo(path);
  }
}
