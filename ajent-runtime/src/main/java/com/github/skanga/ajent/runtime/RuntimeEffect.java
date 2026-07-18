package com.github.skanga.ajent.runtime;

import com.github.skanga.ajent.domain.CancellationSignal;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.domain.CheckpointId;
import java.util.List;
import java.time.Duration;

public sealed interface RuntimeEffect {
  record Persist(Thread thread) implements RuntimeEffect {}
  record CreateCheckpoint(CheckpointId id) implements RuntimeEffect {}
  record StartStream(long turnId, List<Message> messages, CancellationSignal cancellation)
      implements RuntimeEffect {
    public StartStream { messages = List.copyOf(messages); }
  }
  record ExecuteTool(long turnId, ToolUse call) implements RuntimeEffect {}
  record RequestPermission(long turnId, ToolUse call) implements RuntimeEffect {}
  record RefreshOAuth(long turnId, String refreshToken) implements RuntimeEffect {}
  record Schedule(Duration delay, RuntimeMessage message) implements RuntimeEffect {}
}
