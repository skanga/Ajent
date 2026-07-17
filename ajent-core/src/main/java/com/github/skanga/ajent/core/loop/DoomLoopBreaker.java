package com.github.skanga.ajent.core.loop;

import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.ToolStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DoomLoopBreaker {
  private static final int REPEAT_LIMIT = 3;
  private static final int MAX_TOOL_TURNS = 25;

  public record LoopBreak(String reason) {}

  private record Signature(String toolName, Map<String, Object> arguments) {}

  private static final class Occurrences {
    private int count;
    private boolean allFailed = true;

    void note(boolean failed) {
      count++;
      if (!failed) {
        allFailed = false;
      }
    }
  }

  private DoomLoopBreaker() {}

  public static Optional<LoopBreak> shouldBreak(List<Message> messages) {
    return shouldBreak(messages, true);
  }

  public static Optional<LoopBreak> shouldBreak(
      List<Message> messages, boolean enforceStepCap) {
    int runStart = 0;
    for (int index = messages.size() - 1; index >= 0; index--) {
      if (messages.get(index).role() == Role.USER) {
        runStart = index + 1;
        break;
      }
    }

    int toolTurns = 0;
    var seen = new HashMap<Signature, Occurrences>();
    for (int index = runStart; index < messages.size(); index++) {
      var message = messages.get(index);
      if (message.role() != Role.ASSISTANT || message.toolCalls().isEmpty()) {
        continue;
      }
      toolTurns++;
      for (var call : message.toolCalls()) {
        if (!call.status().isTerminal()) {
          continue;
        }
        var signature = new Signature(call.name().value(), call.arguments());
        seen.computeIfAbsent(signature, ignored -> new Occurrences())
            .note(call.status() instanceof ToolStatus.Failed);
      }
    }

    for (var entry : seen.entrySet()) {
      var occurrences = entry.getValue();
      if (occurrences.count >= REPEAT_LIMIT && occurrences.allFailed) {
        return Optional.of(new LoopBreak(
            "Stopped: the `" + entry.getKey().toolName + "` tool was called "
                + occurrences.count + " times with the same arguments and failed every time. "
                + "Re-trying the identical call won't help — change the arguments "
                + "(check the path/target exists, or pick a different tool), or answer the user "
                + "directly with what you already know."));
      }
    }
    if (enforceStepCap && toolTurns >= MAX_TOOL_TURNS) {
      return Optional.of(new LoopBreak(
          "Stopped after " + toolTurns + " tool steps without finishing. Summarise what you "
              + "found and answer the user directly, or ask them how to proceed."));
    }
    return Optional.empty();
  }
}
