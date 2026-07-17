package com.github.skanga.ajent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConversationTest {
  @Test
  void identifiersAreStrongNonNullValues() {
    assertThat(new ThreadId("t").value()).isEqualTo("t");
    assertThat(new ToolCallId("call").value()).isEqualTo("call");
    assertThat(new ToolName("grep").value()).isEqualTo("grep");
    assertThat(new ModelId("model").value()).isEqualTo("model");
    assertThatNullPointerException().isThrownBy(() -> new ThreadId(null));
    assertThatNullPointerException().isThrownBy(() -> new ToolCallId(null));
    assertThatNullPointerException().isThrownBy(() -> new ToolName(null));
    assertThatNullPointerException().isThrownBy(() -> new ModelId(null));
  }

  @Test
  void profilesPreserveAgenTTYOrdinalsAndSafelyDefaultUnknownValues() {
    assertThat(Profile.fromPersistedOrdinal(0)).isEqualTo(Profile.WRITE);
    assertThat(Profile.fromPersistedOrdinal(1)).isEqualTo(Profile.ASK);
    assertThat(Profile.fromPersistedOrdinal(2)).isEqualTo(Profile.MINIMAL);
    assertThat(Profile.fromPersistedOrdinal(-1)).isEqualTo(Profile.WRITE);
    assertThat(Profile.fromPersistedOrdinal(3)).isEqualTo(Profile.WRITE);
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
  void toolStatusVariantsCarryOnlyTheMonotonicTimesValidForTheirState() {
    List<ToolStatus> statuses = List.of(new ToolStatus.Pending(11),
        new ToolStatus.Approved(12), new ToolStatus.Running(13, "progress"),
        new ToolStatus.Done(14, 15, "done"), new ToolStatus.Failed(16, 17, "failed"),
        new ToolStatus.Rejected(18));
    assertThat(statuses).extracting(ToolStatus::startedNanos)
        .containsExactly(11L, 12L, 13L, 14L, 16L, 0L);
    assertThat(statuses).extracting(ToolStatus::finishedNanos)
        .containsExactly(0L, 0L, 0L, 15L, 17L, 18L);
    assertThat(new ToolStatus.Pending().startedNanos()).isZero();
    assertThat(new ToolStatus.Done("done").finishedNanos()).isZero();
    assertThatIllegalArgumentException().isThrownBy(() -> new ToolStatus.Pending(-1));
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

  @Test
  void persistedConversationMetadataIsImmutableAndCopySafe() {
    var attachment = new Attachment(
        Attachment.Kind.PASTE, new byte[] {0, 1}, "a.txt", "text/plain", "paste",
        3, 4, 2);
    var message = new Message(
        new MessageId("m1"), Role.USER, "text", List.of(), List.of(attachment),
        "thought", "signature", List.of(), Instant.ofEpochSecond(12),
        Optional.of(new CheckpointId("cp")), Optional.of("error"), true);
    var compaction = new CompactionRecord(1, "summary", Instant.ofEpochSecond(20));
    var thread = new Thread(
        new ThreadId("t"), "title", List.of(message), Instant.ofEpochSecond(1),
        Instant.ofEpochSecond(2), List.of(compaction));

    assertThat(message.id()).isEqualTo(new MessageId("m1"));
    assertThat(message.attachments()).containsExactly(attachment).isUnmodifiable();
    assertThat(message.checkpointId()).contains(new CheckpointId("cp"));
    assertThat(message.error()).contains("error");
    assertThat(message.isCompactSummary()).isTrue();
    byte[] exposed = attachment.body();
    exposed[0] = 9;
    assertThat(attachment.body()).containsExactly(0, 1);
    assertThat(thread.createdAt()).isEqualTo(Instant.ofEpochSecond(1));
    assertThat(thread.updatedAt()).isEqualTo(Instant.ofEpochSecond(2));
    assertThat(thread.compactions()).containsExactly(compaction).isUnmodifiable();
    assertThat(message.withToolCalls(List.of()).id()).isEqualTo(message.id());
  }

  @Test
  void persistedMetadataRejectsNegativeCountsAndNullComponents() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Attachment(
        Attachment.Kind.PASTE, new byte[0], "", "", "", -1, 0, 0));
    assertThatIllegalArgumentException().isThrownBy(() -> new Attachment(
        Attachment.Kind.PASTE, new byte[0], "", "", "", 0, -1, 0));
    assertThatIllegalArgumentException().isThrownBy(() -> new Attachment(
        Attachment.Kind.PASTE, new byte[0], "", "", "", 0, 0, -1));
    assertThatNullPointerException().isThrownBy(() -> new Attachment(
        null, new byte[0], "", "", "", 0, 0, 0));
    assertThatNullPointerException().isThrownBy(() -> new Attachment(
        Attachment.Kind.PASTE, null, "", "", "", 0, 0, 0));
    assertThatIllegalArgumentException().isThrownBy(() ->
        new CompactionRecord(-1, "", Instant.EPOCH));
    assertThatNullPointerException().isThrownBy(() ->
        new CompactionRecord(0, null, Instant.EPOCH));
    assertThatNullPointerException().isThrownBy(() ->
        new CompactionRecord(0, "", null));
  }
}
