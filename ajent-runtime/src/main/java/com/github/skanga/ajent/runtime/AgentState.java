package com.github.skanga.ajent.runtime;

import com.github.skanga.ajent.domain.SessionPhase;
import com.github.skanga.ajent.domain.Thread;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record AgentState(
    Thread thread,
    SessionPhase phase,
    long activeTurnId,
    long turnCounter,
    int tokensIn,
    int tokensOut,
    String status,
    Optional<ToolDraft> toolDraft,
    List<RuntimeMessage.Submit> queued,
    Set<String> sessionGrants) {

  public record ToolDraft(String callId, String partialJson) {
    public ToolDraft {
      callId = Objects.requireNonNull(callId, "callId");
      partialJson = Objects.requireNonNull(partialJson, "partialJson");
    }
  }

  public AgentState {
    thread = Objects.requireNonNull(thread, "thread");
    phase = Objects.requireNonNull(phase, "phase");
    status = Objects.requireNonNull(status, "status");
    toolDraft = Objects.requireNonNull(toolDraft, "toolDraft");
    queued = List.copyOf(queued);
    sessionGrants = Set.copyOf(sessionGrants);
  }

  public static AgentState initial(Thread thread) {
    return new AgentState(thread, new SessionPhase.Idle(), 0, 0, 0, 0, "",
        Optional.empty(), List.of(), Set.of());
  }
}
