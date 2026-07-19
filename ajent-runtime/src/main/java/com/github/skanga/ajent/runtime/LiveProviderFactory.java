package com.github.skanga.ajent.runtime;

import com.github.skanga.ajent.domain.Effort;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.ModelCapabilities;
import com.github.skanga.ajent.provider.ChatRequest;
import com.github.skanga.ajent.provider.ProviderHttpTransport;
import com.github.skanga.ajent.provider.ToolSpecification;
import com.github.skanga.ajent.provider.anthropic.AnthropicRequest;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import com.github.skanga.ajent.provider.openai.Endpoint;
import com.github.skanga.ajent.tools.catalog.NativeToolWireCatalog;
import com.github.skanga.ajent.tools.prompt.AgentSystemPrompt;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds call-time provider requests from one resolved application selection. */
public final class LiveProviderFactory {
  private static final String DEFAULT_MODEL = "claude-opus-4-5";

  public record Configuration(
      String provider,
      String model,
      ProviderAuth auth,
      String effort,
      AgentSystemPrompt systemPrompt,
      int contextWindow,
      Map<String, String> environment) {
    public Configuration {
      provider = provider == null || provider.isBlank() ? "anthropic" : provider;
      model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
      auth = Objects.requireNonNull(auth, "auth");
      effort = effort == null ? "" : effort;
      systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
      if (contextWindow < 0) {
        throw new IllegalArgumentException("contextWindow cannot be negative");
      }
      environment = Map.copyOf(environment);
    }
  }

  private LiveProviderFactory() {}

  public static ProviderPort create(Configuration configuration, HttpClient client) {
    Objects.requireNonNull(configuration, "configuration");
    return new HttpProviderPort(new ProviderHttpTransport(client, configuration.environment()),
        messages -> request(configuration, messages));
  }

  public static HttpProviderPort.Request request(
      Configuration configuration, List<Message> messages) {
    Objects.requireNonNull(configuration, "configuration");
    List<Message> history = List.copyOf(messages);
    List<ToolSpecification> tools = toolsFor(configuration.model());
    int maximum = ModelCapabilities.maxOutputTokensFor(
        configuration.model(), configuration.environment().get("AGENTTY_MAX_OUTPUT_TOKENS"));
    if (configuration.provider().equals("anthropic")) {
      String effort = Effort.fromWire(configuration.effort())
          .clamp(ModelCapabilities.fromId(configuration.model())).wire();
      return new HttpProviderPort.Request.Anthropic(new AnthropicRequest(
          configuration.model(), configuration.systemPrompt().anthropic(), history, tools,
          maximum, configuration.auth(), 0, effort));
    }
    Endpoint endpoint = Endpoint.fromSpec(configuration.provider());
    boolean weak = ModelCapabilities.isWeakModel(configuration.model());
    var chat = new ChatRequest(
        configuration.model(), configuration.systemPrompt().local(), history, tools, maximum,
        configuration.auth(), endpoint, configuration.contextWindow(), weak && endpoint.nativeApi());
    return endpoint.nativeApi()
        ? new HttpProviderPort.Request.Ollama(chat) : new HttpProviderPort.Request.OpenAi(chat);
  }

  static HttpProviderPort.Request request(
      Configuration configuration, List<Message> messages, String systemPrompt,
      List<ToolSpecification> tools, int maximum) {
    Objects.requireNonNull(configuration, "configuration");
    Objects.requireNonNull(systemPrompt, "systemPrompt");
    List<Message> history = List.copyOf(messages);
    List<ToolSpecification> selectedTools = List.copyOf(tools);
    if (maximum <= 0) throw new IllegalArgumentException("maximum must be positive");
    if (configuration.provider().equals("anthropic")) {
      String effort = Effort.fromWire(configuration.effort())
          .clamp(ModelCapabilities.fromId(configuration.model())).wire();
      return new HttpProviderPort.Request.Anthropic(new AnthropicRequest(
          configuration.model(), systemPrompt, history, selectedTools,
          maximum, configuration.auth(), 0, effort));
    }
    Endpoint endpoint = Endpoint.fromSpec(configuration.provider());
    boolean weak = ModelCapabilities.isWeakModel(configuration.model());
    var chat = new ChatRequest(configuration.model(), systemPrompt, history, selectedTools,
        maximum, configuration.auth(), endpoint, configuration.contextWindow(),
        weak && endpoint.nativeApi());
    return endpoint.nativeApi()
        ? new HttpProviderPort.Request.Ollama(chat) : new HttpProviderPort.Request.OpenAi(chat);
  }

  private static List<ToolSpecification> toolsFor(String model) {
    if (!ModelCapabilities.isWeakModel(model)) {
      return NativeToolWireCatalog.all();
    }
    return NativeToolWireCatalog.all().stream().filter(tool -> switch (tool.name()) {
      case "skill", "remember", "forget", "wipe_memory" -> false;
      default -> true;
    }).toList();
  }
}
