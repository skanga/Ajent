package com.github.skanga.ajent.core.loop;

import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SalvagedCallDeduplicator {
  private static final String SALVAGED_PREFIX = "call_salvaged_";
  private static final int MAX_SALVAGED_PER_TURN = 3;
  private static final Set<String> MEMORY_TOOLS = Set.of("remember", "forget", "wipe_memory");

  public record Result(List<Message> messages, int deduplicated) {
    public Result {
      messages = List.copyOf(messages);
    }
  }

  private record Signature(String name, Map<String, Object> arguments) {}

  private SalvagedCallDeduplicator() {}

  public static Result deduplicate(List<Message> messages) {
    if (messages.isEmpty() || messages.getLast().role() != Role.ASSISTANT) {
      return new Result(messages, 0);
    }

    int turnStart = messages.size();
    while (turnStart > 0 && messages.get(turnStart - 1).role() == Role.ASSISTANT) {
      turnStart--;
    }

    var terminalSignatures = new HashSet<Signature>();
    int terminalSalvaged = 0;
    for (int index = turnStart; index < messages.size(); index++) {
      for (var call : messages.get(index).toolCalls()) {
        if (!call.status().isTerminal()) {
          continue;
        }
        terminalSignatures.add(signature(call));
        if (isSalvaged(call)) {
          terminalSalvaged++;
        }
      }
    }

    var last = messages.getLast();
    var revisedCalls = new ArrayList<ToolUse>(last.toolCalls().size());
    int blockedMemoryCalls = 0;
    for (var call : last.toolCalls()) {
      if (isReady(call) && isSalvaged(call) && MEMORY_TOOLS.contains(call.name().value())) {
        revisedCalls.add(withFailure(call,
            "not run (" + call.name().value()
                + ": memory tools run only on an explicit user request)"));
        blockedMemoryCalls++;
      } else {
        revisedCalls.add(call);
      }
    }

    if (terminalSignatures.isEmpty() && terminalSalvaged < MAX_SALVAGED_PER_TURN) {
      return replaceLast(messages, last, revisedCalls, blockedMemoryCalls);
    }

    int deduplicated = 0;
    for (int index = 0; index < revisedCalls.size(); index++) {
      var call = revisedCalls.get(index);
      if (!isReady(call) || !isSalvaged(call)) {
        continue;
      }
      boolean exactReleak = terminalSignatures.contains(signature(call));
      boolean overBudget = terminalSalvaged >= MAX_SALVAGED_PER_TURN;
      if (!exactReleak && !overBudget) {
        continue;
      }
      String reason = overBudget && !exactReleak
          ? "not run (too many repeated tool calls this turn)"
          : "not run (duplicate — this exact call already ran this turn; its result is above)";
      revisedCalls.set(index, withFailure(call, reason));
      deduplicated++;
    }
    return replaceLast(messages, last, revisedCalls, deduplicated + blockedMemoryCalls);
  }

  private static Result replaceLast(
      List<Message> messages, Message last, List<ToolUse> revisedCalls, int count) {
    var revisedMessages = new ArrayList<>(messages);
    revisedMessages.set(revisedMessages.size() - 1, last.withToolCalls(revisedCalls));
    return new Result(revisedMessages, count);
  }

  private static ToolUse withFailure(ToolUse call, String reason) {
    return new ToolUse(call.id(), call.name(), call.arguments(), new ToolStatus.Failed(reason));
  }

  private static Signature signature(ToolUse call) {
    return new Signature(call.name().value(), call.arguments());
  }

  private static boolean isSalvaged(ToolUse call) {
    return call.id().value().startsWith(SALVAGED_PREFIX);
  }

  private static boolean isReady(ToolUse call) {
    return call.status() instanceof ToolStatus.Pending
        || call.status() instanceof ToolStatus.Approved;
  }
}
