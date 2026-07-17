package com.github.skanga.ajent.provider.stream;

import com.github.skanga.ajent.provider.ErrorClass;
import com.github.skanga.ajent.provider.ProviderErrorPolicy;
import java.util.Objects;
import java.time.Duration;
import java.util.Optional;

public sealed interface StreamEvent {
  record Started() implements StreamEvent {}

  record TextDelta(String text) implements StreamEvent {
    public TextDelta { text = Objects.requireNonNull(text, "text"); }
  }

  record TextBlockClosed() implements StreamEvent {}

  record ThinkingDelta(String text, String signature) implements StreamEvent {
    public ThinkingDelta {
      text = Objects.requireNonNull(text, "text");
      signature = Objects.requireNonNull(signature, "signature");
    }
  }

  record ToolUseStart(String id, String name) implements StreamEvent {
    public ToolUseStart {
      id = Objects.requireNonNull(id, "id");
      name = Objects.requireNonNull(name, "name");
    }
  }

  record ToolUseDelta(String partialJson) implements StreamEvent {
    public ToolUseDelta { partialJson = Objects.requireNonNull(partialJson, "partialJson"); }
  }

  record ToolUseEnd() implements StreamEvent {}
  record Heartbeat() implements StreamEvent {}
  record Usage(int inputTokens, int outputTokens, int cacheCreationInputTokens,
               int cacheReadInputTokens) implements StreamEvent {
    public Usage(int inputTokens, int outputTokens) {
      this(inputTokens, outputTokens, 0, 0);
    }
  }

  record Finished(StopReason stopReason) implements StreamEvent {
    public Finished { stopReason = Objects.requireNonNull(stopReason, "stopReason"); }
  }

  record Error(String message, Optional<Duration> retryAfter, ErrorClass errorClass,
               boolean fromStall) implements StreamEvent {
    public Error(String message) {
      this(message, Optional.empty(), ProviderErrorPolicy.classify(message), false);
    }

    public Error(String message, Optional<Duration> retryAfter) {
      this(message, retryAfter, ProviderErrorPolicy.classify(message), false);
    }

    public Error {
      message = Objects.requireNonNull(message, "message");
      retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
      errorClass = Objects.requireNonNull(errorClass, "errorClass");
    }
  }
}
