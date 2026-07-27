package com.github.skanga.ajent.provider.openai;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ProviderRegistry {
  public enum Kind { ANTHROPIC, CODEX, OPENAI }
  public enum AuthStyle { OAUTH_OR_KEY, CODEX_IMPORT, API_KEY, NONE }

  public record Preset(
      String id,
      String label,
      String description,
      Kind kind,
      AuthStyle authStyle,
      boolean local,
      List<String> authEnvironment) {
    public Preset {
      id = Objects.requireNonNull(id, "id");
      label = Objects.requireNonNull(label, "label");
      description = Objects.requireNonNull(description, "description");
      kind = Objects.requireNonNull(kind, "kind");
      authStyle = Objects.requireNonNull(authStyle, "authStyle");
      authEnvironment = List.copyOf(authEnvironment);
    }
  }

  private static final List<Preset> PRESETS = List.of(
      new Preset("anthropic", "Anthropic", "Claude — OAuth (Pro/Max) or API key",
          Kind.ANTHROPIC, AuthStyle.OAUTH_OR_KEY, false, List.of()),
      new Preset("openai", "OpenAI", "GPT — api.openai.com",
          Kind.OPENAI, AuthStyle.API_KEY, false, List.of("OPENAI_API_KEY")),
      new Preset("codex", "Codex", "ChatGPT subscription — import Codex CLI login",
          Kind.CODEX, AuthStyle.CODEX_IMPORT, false, List.of()),
      new Preset("groq", "Groq", "Llama/Mixtral on Groq LPUs — very fast",
          Kind.OPENAI, AuthStyle.API_KEY, false, List.of("GROQ_API_KEY", "OPENAI_API_KEY")),
      new Preset("openrouter", "OpenRouter", "Any model via openrouter.ai",
          Kind.OPENAI, AuthStyle.API_KEY, false,
          List.of("OPENROUTER_API_KEY", "OPENAI_API_KEY")),
      new Preset("together", "Together", "Open models on together.ai",
          Kind.OPENAI, AuthStyle.API_KEY, false,
          List.of("TOGETHER_API_KEY", "OPENAI_API_KEY")),
      new Preset("cerebras", "Cerebras", "Wafer-scale inference — very fast",
          Kind.OPENAI, AuthStyle.API_KEY, false,
          List.of("CEREBRAS_API_KEY", "OPENAI_API_KEY")),
      new Preset("ollama", "Ollama", "Local models at localhost:11434",
          Kind.OPENAI, AuthStyle.NONE, true, List.of()),
      new Preset("llama.cpp", "llama.cpp", "Local llama.cpp server at localhost:8080",
          Kind.OPENAI, AuthStyle.NONE, true, List.of()));

  private ProviderRegistry() {}

  public static List<Preset> presets() {
    return PRESETS;
  }

  public static Optional<Preset> presetFor(String id) {
    return PRESETS.stream().filter(preset -> preset.id().equals(id)).findFirst();
  }

  public static String defaultProviderId() {
    return PRESETS.getFirst().id();
  }
}
