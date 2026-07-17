package com.github.skanga.ajent.core.loop;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.ImageContent;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DoomLoopBreakerTest {
  private final AtomicInteger ids = new AtomicInteger();

  @Test
  void identicalFailingCallBreaksOnThirdAttemptAndNamesTool() {
    var messages = new ArrayList<>(List.of(user("go")));
    for (int attempt = 0; attempt < 3; attempt++) {
      messages.add(callMessage("read", Map.of("path", "https://x.com/jokes"), false));
    }
    assertThat(DoomLoopBreaker.shouldBreak(messages, true))
        .get().extracting(DoomLoopBreaker.LoopBreak::reason)
        .asString().contains("read");
  }

  @Test
  void twoFailuresAreAllowed() {
    var messages = List.of(
        user("go"),
        callMessage("read", Map.of("path", "x"), false),
        callMessage("read", Map.of("path", "x"), false));
    assertThat(DoomLoopBreaker.shouldBreak(messages, true)).isEmpty();
  }

  @Test
  void repeatedSuccessAndDistinctFailuresDoNotBreak() {
    var successes = new ArrayList<>(List.of(user("go")));
    for (int attempt = 0; attempt < 5; attempt++) {
      successes.add(callMessage("read", Map.of("path", "log.txt"), true));
    }
    assertThat(DoomLoopBreaker.shouldBreak(successes, true)).isEmpty();

    var distinct = List.of(
        user("go"),
        callMessage("read", Map.of("path", "a"), false),
        callMessage("read", Map.of("path", "b"), false),
        callMessage("read", Map.of("path", "c"), false));
    assertThat(DoomLoopBreaker.shouldBreak(distinct, true)).isEmpty();
  }

  @Test
  void weakModelBreaksAtTwentyFiveToolTurns() {
    var messages = healthyTurns(25);
    assertThat(DoomLoopBreaker.shouldBreak(messages, true))
        .get().extracting(DoomLoopBreaker.LoopBreak::reason)
        .asString().contains("steps");
  }

  @Test
  void capableModelSkipsStepCapButNotRepeatedFailureCap() {
    assertThat(DoomLoopBreaker.shouldBreak(healthyTurns(40), false)).isEmpty();
    assertThat(DoomLoopBreaker.shouldBreak(healthyTurns(40), true)).isPresent();

    var repeated = List.of(
        user("go"),
        callMessage("read", Map.of("path", "/nope"), false),
        callMessage("read", Map.of("path", "/nope"), false),
        callMessage("read", Map.of("path", "/nope"), false));
    assertThat(DoomLoopBreaker.shouldBreak(repeated, false)).isPresent();
  }

  @Test
  void healthyProgressDoesNotBreak() {
    var messages = List.of(
        user("go"),
        callMessage("bash", Map.of("command", "ls"), true),
        callMessage("read", Map.of("path", "a.cpp"), true),
        callMessage("edit", Map.of("path", "a.cpp"), true),
        callMessage("bash", Map.of("command", "make"), true));
    assertThat(DoomLoopBreaker.shouldBreak(messages, true)).isEmpty();
  }

  @Test
  void latestUserBoundaryStartsAFreshRun() {
    var args = Map.<String, Object>of("path", "x");
    var messages = List.of(
        user("first"), callMessage("read", args, false), callMessage("read", args, false),
        callMessage("read", args, false), user("second"),
        callMessage("bash", Map.of("command", "pwd"), true));
    assertThat(DoomLoopBreaker.shouldBreak(messages, true)).isEmpty();
  }

  @Test
  void emptyAndTextOnlyHistoriesAreSafe() {
    assertThat(DoomLoopBreaker.shouldBreak(List.of(), true)).isEmpty();
    assertThat(DoomLoopBreaker.shouldBreak(
        List.of(user("go"), message(Role.ASSISTANT, "Here's your answer.", List.of())), true))
        .isEmpty();
  }

  @Test
  void ignoresNonAssistantAndInFlightCallsAndCountsOneTurnPerMessage() {
    var pending = tool("read", Map.of("path", "x"), new ToolStatus.Pending());
    var failed = tool("read", Map.of("path", "x"), new ToolStatus.Failed("no such file"));
    var messages = new ArrayList<Message>();
    messages.add(user("go"));
    messages.add(message(Role.USER, "carrier", List.of(failed, failed, failed)));
    messages.add(message(Role.ASSISTANT, "", List.of(pending)));
    messages.add(message(Role.ASSISTANT, "", List.of(failed, failed)));
    assertThat(DoomLoopBreaker.shouldBreak(messages, true)).isEmpty();
  }

  @Test
  void successfulOccurrencePreventsSameSignatureBeingClassifiedAsAllFailed() {
    var args = Map.<String, Object>of("path", "x");
    var messages = List.of(
        user("go"), callMessage("read", args, false), callMessage("read", args, true),
        callMessage("read", args, false), callMessage("read", args, false));
    assertThat(DoomLoopBreaker.shouldBreak(messages, true)).isEmpty();
  }

  private List<Message> healthyTurns(int count) {
    var messages = new ArrayList<>(List.of(user("go")));
    for (int index = 0; index < count; index++) {
      messages.add(callMessage("bash", Map.of("command", "echo " + index), true));
    }
    return messages;
  }

  private Message callMessage(String name, Map<String, Object> arguments, boolean success) {
    var status = success ? new ToolStatus.Done("ok") : new ToolStatus.Failed("no such file");
    return message(Role.ASSISTANT, "", List.of(tool(name, arguments, status)));
  }

  private ToolUse tool(String name, Map<String, Object> arguments, ToolStatus status) {
    return new ToolUse(
        new ToolCallId("call_" + ids.incrementAndGet()), new ToolName(name), arguments, status);
  }

  private static Message user(String text) {
    return message(Role.USER, text, List.of());
  }

  private static Message message(Role role, String text, List<ToolUse> calls) {
    return new Message(role, text, List.<ImageContent>of(), calls);
  }
}
