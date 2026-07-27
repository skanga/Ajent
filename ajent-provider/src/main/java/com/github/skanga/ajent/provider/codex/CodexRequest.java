package com.github.skanga.ajent.provider.codex;

import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.provider.ToolSpecification;
import java.util.List;
import java.util.Objects;

/** Stateless ChatGPT Codex Responses API request. */
public record CodexRequest(
    String model,
    String instructions,
    List<Message> messages,
    List<ToolSpecification> tools,
    int maxTokens,
    String reasoningEffort) {
  public CodexRequest(
      String model, String instructions, List<Message> messages,
      List<ToolSpecification> tools, int maxTokens) {
    this(model, instructions, messages, tools, maxTokens, "");
  }

  public CodexRequest {
    model = Objects.requireNonNull(model, "model");
    instructions = Objects.requireNonNull(instructions, "instructions");
    messages = List.copyOf(messages);
    tools = List.copyOf(tools);
    reasoningEffort = Objects.requireNonNull(reasoningEffort, "reasoningEffort");
    if (maxTokens < 1) throw new IllegalArgumentException("maxTokens must be positive");
  }
}
