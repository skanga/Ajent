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
import org.junit.jupiter.api.Test;

class SalvagedCallDeduplicatorTest {
  @Test
  void releakedSalvagedCallInSameMessageIsFailed() {
    var args = Map.<String, Object>of("path", "/tmp/x");
    var result = dedup(List.of(user(), assistant(
        call("call_salvaged_0", "read", args, true),
        call("call_salvaged_1", "read", args, false))));
    assertThat(result.deduplicated()).isOne();
    assertThat(lastCalls(result).get(0).status()).isInstanceOf(ToolStatus.Done.class);
    assertThat(lastCalls(result).get(1).status()).isInstanceOf(ToolStatus.Failed.class);
  }

  @Test
  void structuredDuplicateIsDeliberateAndRemainsPending() {
    var args = Map.<String, Object>of("path", "/tmp/x");
    var result = dedup(List.of(user(), assistant(
        call("call_abc", "read", args, true), call("call_def", "read", args, false))));
    assertThat(result.deduplicated()).isZero();
    assertThat(lastCalls(result).get(1).status()).isInstanceOf(ToolStatus.Pending.class);
  }

  @Test
  void differentArgumentsAndFirstSalvagedCallRemainPending() {
    var different = dedup(List.of(user(), assistant(
        call("call_salvaged_0", "read", Map.of("path", "/a"), true),
        call("call_salvaged_1", "read", Map.of("path", "/b"), false))));
    assertThat(different.deduplicated()).isZero();
    assertThat(lastCalls(different).get(1).status()).isInstanceOf(ToolStatus.Pending.class);

    var first = dedup(List.of(user(), assistant(
        call("call_salvaged_0", "read", Map.of("path", "/x"), false))));
    assertThat(first.deduplicated()).isZero();
    assertThat(lastCalls(first).get(0).status()).isInstanceOf(ToolStatus.Pending.class);
  }

  @Test
  void userBoundaryResetsDedupScope() {
    var args = Map.<String, Object>of("path", "/x");
    var result = dedup(List.of(
        user(), assistant(call("call_salvaged_0", "read", args, true)),
        user(), assistant(call("call_salvaged_1", "read", args, false))));
    assertThat(result.deduplicated()).isZero();
    assertThat(lastCalls(result).get(0).status()).isInstanceOf(ToolStatus.Pending.class);
  }

  @Test
  void duplicateAcrossAssistantSubturnsIsFailed() {
    var args = Map.<String, Object>of("path", "/tmp/x");
    var result = dedup(List.of(
        user(), assistant(call("call_salvaged_0", "read", args, true)),
        assistant(call("call_salvaged_1", "read", args, false))));
    assertThat(result.deduplicated()).isOne();
    assertThat(lastCalls(result).get(0).status()).isInstanceOf(ToolStatus.Failed.class);
  }

  @Test
  void salvageBudgetBoundsDriftingArgumentsButNeverStructuredCalls() {
    var messages = new ArrayList<>(List.of(user()));
    var prior = new ArrayList<ToolUse>();
    for (int index = 0; index < 8; index++) {
      prior.add(call("call_salvaged_" + index, "read", Map.of("path", "/f" + index), true));
    }
    messages.add(assistant(prior.toArray(ToolUse[]::new)));
    messages.add(assistant(call(
        "call_salvaged_8", "read", Map.of("path", "/f8-drifted"), false)));
    var salvaged = dedup(messages);
    assertThat(salvaged.deduplicated()).isOne();
    assertThat(lastCalls(salvaged).get(0).status()).isInstanceOf(ToolStatus.Failed.class);

    messages.set(messages.size() - 1, assistant(
        call("call_real_1", "read", Map.of("path", "/tmp/x"), false)));
    var structured = dedup(messages);
    assertThat(structured.deduplicated()).isZero();
    assertThat(lastCalls(structured).get(0).status()).isInstanceOf(ToolStatus.Pending.class);
  }

  @Test
  void salvagedMemoryToolsAreBlockedButStructuredMemoryCallsAreAllowed() {
    for (var name : List.of("remember", "forget", "wipe_memory")) {
      var blocked = dedup(List.of(user(), assistant(
          call("call_salvaged_0", name, Map.of("text", "Hi there!"), false))));
      assertThat(blocked.deduplicated()).isOne();
      assertThat(lastCalls(blocked).get(0).status()).isInstanceOf(ToolStatus.Failed.class);
    }

    var allowed = dedup(List.of(user(), assistant(
        call("call_real_1", "remember", Map.of("text", "durable fact"), false))));
    assertThat(allowed.deduplicated()).isZero();
    assertThat(lastCalls(allowed).get(0).status()).isInstanceOf(ToolStatus.Pending.class);
  }

  @Test
  void emptyOrNonAssistantTailIsUnchanged() {
    assertThat(dedup(List.of()).deduplicated()).isZero();
    var messages = List.of(user());
    var result = dedup(messages);
    assertThat(result.deduplicated()).isZero();
    assertThat(result.messages()).isEqualTo(messages).isUnmodifiable();
  }

  @Test
  void approvedSalvagedCallsAreAlsoProtectedAndFailureExplainsWhy() {
    var args = Map.<String, Object>of("path", "x");
    var result = dedup(List.of(user(), assistant(
        call("call_salvaged_0", "read", args, true),
        call("call_salvaged_1", "read", args, new ToolStatus.Approved()))));
    assertThat(result.deduplicated()).isOne();
    assertThat(((ToolStatus.Failed) lastCalls(result).get(1).status()).output())
        .contains("duplicate");
  }

  private static SalvagedCallDeduplicator.Result dedup(List<Message> messages) {
    return SalvagedCallDeduplicator.deduplicate(messages);
  }

  private static List<ToolUse> lastCalls(SalvagedCallDeduplicator.Result result) {
    return result.messages().get(result.messages().size() - 1).toolCalls();
  }

  private static Message user() {
    return new Message(Role.USER, "go", List.<ImageContent>of(), List.of());
  }

  private static Message assistant(ToolUse... calls) {
    return new Message(Role.ASSISTANT, "", List.<ImageContent>of(), List.of(calls));
  }

  private static ToolUse call(
      String id, String name, Map<String, Object> arguments, boolean terminal) {
    return call(id, name, arguments,
        terminal ? new ToolStatus.Done("ok") : new ToolStatus.Pending());
  }

  private static ToolUse call(
      String id, String name, Map<String, Object> arguments, ToolStatus status) {
    return new ToolUse(new ToolCallId(id), new ToolName(name), arguments, status);
  }
}
