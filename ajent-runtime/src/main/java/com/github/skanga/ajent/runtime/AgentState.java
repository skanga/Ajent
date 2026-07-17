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
    long lastTickNanos,
    String status,
    Optional<ToolDraft> toolDraft,
    List<RuntimeMessage.Submit> queued,
    Compaction compaction,
    Set<String> truncatedToolIds,
    Set<String> sessionGrants) {

  public record ToolDraft(String callId, String partialJson) {
    public ToolDraft {
      callId = Objects.requireNonNull(callId, "callId");
      partialJson = Objects.requireNonNull(partialJson, "partialJson");
    }
  }

  public record ActiveCompaction(int targetIndex, String buffer) {
    public ActiveCompaction {
      if (targetIndex < 0) throw new IllegalArgumentException("targetIndex cannot be negative");
      buffer = Objects.requireNonNull(buffer, "buffer");
    }
  }

  public record Compaction(Optional<ActiveCompaction> active, int recentCompacts,
                           int turnsSinceLastCompact, boolean autoDisabled) {
    public Compaction {
      active = Objects.requireNonNull(active, "active");
      if (recentCompacts < 0 || turnsSinceLastCompact < 0)
        throw new IllegalArgumentException("compaction counters cannot be negative");
    }

    public static Compaction initial() {
      return new Compaction(Optional.empty(), 0, 1_000_000, false);
    }
  }

  public AgentState {
    thread = Objects.requireNonNull(thread, "thread");
    phase = Objects.requireNonNull(phase, "phase");
    status = Objects.requireNonNull(status, "status");
    toolDraft = Objects.requireNonNull(toolDraft, "toolDraft");
    queued = List.copyOf(queued);
    compaction = Objects.requireNonNull(compaction, "compaction");
    truncatedToolIds = Set.copyOf(truncatedToolIds);
    sessionGrants = Set.copyOf(sessionGrants);
  }

  public static AgentState initial(Thread thread) {
    return new AgentState(thread, new SessionPhase.Idle(), 0, 0, 0, 0, 0, "",
        Optional.empty(), List.of(), Compaction.initial(), Set.of(), Set.of());
  }
}
