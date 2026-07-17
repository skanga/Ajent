package com.github.skanga.ajent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConversationTest {
  @Test
  void identifiersAreStrongNonNullValues() {
    assertThat(new ThreadId("t").value()).isEqualTo("t");
    assertThat(new ToolCallId("call").value()).isEqualTo("call");
    assertThat(new ToolName("grep").value()).isEqualTo("grep");
    assertThatNullPointerException().isThrownBy(() -> new ThreadId(null));
    assertThatNullPointerException().isThrownBy(() -> new ToolCallId(null));
    assertThatNullPointerException().isThrownBy(() -> new ToolName(null));
  }

  @Test
  void imagesDefensivelyOwnTheirBytes() {
    byte[] original = {1, 2, 3};
    var image = new ImageContent("image/png", original);
    original[0] = 9;
    assertThat(image.bytes()).containsExactly(1, 2, 3);
    byte[] exposed = image.bytes();
    exposed[1] = 9;
    assertThat(image.bytes()).containsExactly(1, 2, 3);
    assertThat(image.isEmpty()).isFalse();
    assertThat(new ImageContent("", new byte[0]).isEmpty()).isTrue();
    assertThatNullPointerException().isThrownBy(() -> new ImageContent(null, new byte[0]));
    assertThatNullPointerException().isThrownBy(() -> new ImageContent("image/png", null));
  }

  @Test
  void conversationCollectionsAreImmutableSnapshots() {
    Map<String, Object> arguments = new HashMap<>();
    arguments.put("query", "one");
    var tool = new ToolUse(new ToolCallId("c"), new ToolName("grep"), arguments,
        new ToolStatus.Done("ok"));
    arguments.put("query", "two");
    assertThat(tool.arguments()).containsEntry("query", "one");
    assertThat(tool.arguments()).isUnmodifiable();

    List<ToolUse> tools = new ArrayList<>(List.of(tool));
    var message = new Message(Role.ASSISTANT, "text", List.of(), tools);
    tools.clear();
    assertThat(message.toolCalls()).containsExactly(tool).isUnmodifiable();
    List<Message> messages = new ArrayList<>(List.of(message));
    var thread = new Thread(new ThreadId("t"), "title", messages);
    messages.clear();
    assertThat(thread.messages()).containsExactly(message).isUnmodifiable();
  }

  @Test
  void everyToolStatusHasExplicitTerminalErrorAndOutputSemantics() {
    List<ToolStatus> inFlight = List.of(
        new ToolStatus.Pending(), new ToolStatus.Approved(), new ToolStatus.Running("progress"));
    for (ToolStatus status : inFlight) {
      assertThat(status.isTerminal()).isFalse();
      assertThat(status.isError()).isTrue();
      assertThat(status.output()).isEmpty();
    }
    var done = new ToolStatus.Done("done");
    assertThat(done.isTerminal()).isTrue();
    assertThat(done.isError()).isFalse();
    assertThat(done.output()).isEqualTo("done");
    var failed = new ToolStatus.Failed("failed");
    assertThat(failed.isTerminal()).isTrue();
    assertThat(failed.isError()).isTrue();
    assertThat(failed.output()).isEqualTo("failed");
    var rejected = new ToolStatus.Rejected();
    assertThat(rejected.isTerminal()).isTrue();
    assertThat(rejected.isError()).isTrue();
    assertThat(rejected.output()).isEmpty();
    assertThatNullPointerException().isThrownBy(() -> new ToolStatus.Running(null));
    assertThatNullPointerException().isThrownBy(() -> new ToolStatus.Done(null));
    assertThatNullPointerException().isThrownBy(() -> new ToolStatus.Failed(null));
  }

  @Test
  void aggregateRecordsRejectNullRequiredComponents() {
    assertThatNullPointerException().isThrownBy(() -> new Message(null, "", List.of(), List.of()));
    assertThatNullPointerException().isThrownBy(() -> new Message(Role.USER, null, List.of(), List.of()));
    assertThatNullPointerException().isThrownBy(() -> new ToolUse(null, new ToolName("x"), Map.of(), new ToolStatus.Pending()));
    assertThatNullPointerException().isThrownBy(() -> new ToolUse(new ToolCallId("x"), null, Map.of(), new ToolStatus.Pending()));
    assertThatNullPointerException().isThrownBy(() -> new ToolUse(new ToolCallId("x"), new ToolName("x"), Map.of(), null));
    assertThatNullPointerException().isThrownBy(() -> new Thread(null, "", List.of()));
    assertThatNullPointerException().isThrownBy(() -> new Thread(new ThreadId("x"), null, List.of()));
  }
}
