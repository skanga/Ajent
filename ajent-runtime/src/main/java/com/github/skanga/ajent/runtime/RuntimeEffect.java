package com.github.skanga.ajent.runtime;

import com.github.skanga.ajent.domain.CancellationSignal;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ToolUse;
import java.util.List;

public sealed interface RuntimeEffect {
  record Persist(Thread thread) implements RuntimeEffect {}
  record StartStream(long turnId, List<Message> messages, CancellationSignal cancellation)
      implements RuntimeEffect {
    public StartStream { messages = List.copyOf(messages); }
  }
  record ExecuteTool(long turnId, ToolUse call) implements RuntimeEffect {}
  record RequestPermission(long turnId, ToolUse call) implements RuntimeEffect {}
}
