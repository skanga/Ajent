package com.github.skanga.ajent.runtime;

import com.github.skanga.ajent.domain.CancellationSignal;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.provider.ChatRequest;
import com.github.skanga.ajent.provider.ProviderHttpTransport;
import com.github.skanga.ajent.provider.anthropic.AnthropicRequest;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Runtime provider seam that resolves the active live HTTP backend for every stream call. */
public final class HttpProviderPort implements ProviderPort {
  private final ProviderHttpTransport transport;
  private final Function<List<Message>, Request> requestFactory;

  public HttpProviderPort(
      ProviderHttpTransport transport, Function<List<Message>, Request> requestFactory) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.requestFactory = Objects.requireNonNull(requestFactory, "requestFactory");
  }

  @Override
  public void stream(
      long turnId,
      List<Message> messages,
      CancellationSignal cancellation,
      Consumer<StreamEvent> events) {
    Objects.requireNonNull(cancellation, "cancellation");
    Objects.requireNonNull(events, "events");
    Request request = Objects.requireNonNull(
        requestFactory.apply(List.copyOf(messages)), "requestFactory result");
    switch (request) {
      case Request.Anthropic anthropic -> transport.streamAnthropic(
          anthropic.value(), events, cancellation::isCancelled);
      case Request.OpenAi openAi -> transport.streamOpenAi(
          openAi.value(), events, cancellation::isCancelled);
      case Request.Ollama ollama -> transport.streamOllama(
          ollama.value(), events, cancellation::isCancelled);
    }
  }

  public sealed interface Request {
    record Anthropic(AnthropicRequest value) implements Request {
      public Anthropic { value = Objects.requireNonNull(value, "value"); }
    }

    record OpenAi(ChatRequest value) implements Request {
      public OpenAi { value = Objects.requireNonNull(value, "value"); }
    }

    record Ollama(ChatRequest value) implements Request {
      public Ollama { value = Objects.requireNonNull(value, "value"); }
    }
  }
}
