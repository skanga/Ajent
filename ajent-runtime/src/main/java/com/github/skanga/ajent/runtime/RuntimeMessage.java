package com.github.skanga.ajent.runtime;

import com.github.skanga.ajent.domain.ImageContent;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import java.util.List;
import java.util.Objects;

public sealed interface RuntimeMessage {
  record Submit(String text, List<ImageContent> images) implements RuntimeMessage {
    public Submit {
      text = Objects.requireNonNull(text, "text");
      images = List.copyOf(images);
    }
  }
  record ProviderEvent(long turnId, StreamEvent event) implements RuntimeMessage {
    public ProviderEvent { event = Objects.requireNonNull(event, "event"); }
  }
  record ToolCompleted(long turnId, String callId, ToolCompletion result)
      implements RuntimeMessage {
    public ToolCompleted {
      callId = Objects.requireNonNull(callId, "callId");
      result = Objects.requireNonNull(result, "result");
    }
  }
  record PermissionResolved(String callId, boolean approved, boolean always)
      implements RuntimeMessage {
    public PermissionResolved { callId = Objects.requireNonNull(callId, "callId"); }
  }
  record RetryStream(long turnId) implements RuntimeMessage {}
  record CompactContext() implements RuntimeMessage {}
  record Tick() implements RuntimeMessage {}
  record Cancel() implements RuntimeMessage {}
}
