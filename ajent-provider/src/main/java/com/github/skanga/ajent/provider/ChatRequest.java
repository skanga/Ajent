package com.github.skanga.ajent.provider;

import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import com.github.skanga.ajent.provider.openai.Endpoint;
import java.util.List;
import java.util.Objects;

/** Provider-neutral request shape shared by the OpenAI-compatible and Ollama transports. */
public record ChatRequest(
    String model,
    String systemPrompt,
    List<Message> messages,
    List<ToolSpecification> tools,
    int maxTokens,
    ProviderAuth auth,
    Endpoint endpoint,
    int contextWindow,
    boolean jsonProtocol) {
  public ChatRequest {
    model = Objects.requireNonNull(model, "model");
    systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
    messages = List.copyOf(messages);
    tools = List.copyOf(tools);
    auth = Objects.requireNonNull(auth, "auth");
    endpoint = Objects.requireNonNull(endpoint, "endpoint");
    if (maxTokens < 0) throw new IllegalArgumentException("maxTokens cannot be negative");
    if (contextWindow < 0) throw new IllegalArgumentException("contextWindow cannot be negative");
  }
}
