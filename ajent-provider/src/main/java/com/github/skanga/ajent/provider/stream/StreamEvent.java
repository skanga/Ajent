package com.github.skanga.ajent.provider.stream;

import java.util.Objects;

public sealed interface StreamEvent {
  record TextDelta(String text) implements StreamEvent {
    public TextDelta { text = Objects.requireNonNull(text, "text"); }
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
  record Usage(int inputTokens, int outputTokens) implements StreamEvent {}

  record Finished(StopReason stopReason) implements StreamEvent {
    public Finished { stopReason = Objects.requireNonNull(stopReason, "stopReason"); }
  }

  record Error(String message) implements StreamEvent {
    public Error { message = Objects.requireNonNull(message, "message"); }
  }
}
