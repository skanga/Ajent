package com.github.skanga.ajent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.CompactionRecord;
import com.github.skanga.ajent.domain.ImageContent;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.MessageId;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ConversationWireTest {
  @Test void latestValidCompactionReplacesOnlyItsCoveredPrefix() {
    List<Message> raw = List.of(message("u0", Role.USER, "root"),
        message("a0", Role.ASSISTANT, "answer"), message("u1", Role.USER, "recent"));
    Thread thread = thread(raw, List.of(
        new CompactionRecord(1, "old", Instant.EPOCH),
        new CompactionRecord(2, "state summary", Instant.EPOCH)));

    List<Message> wire = ConversationWire.messages(thread);

    assertThat(wire).hasSize(2);
    assertThat(wire.getFirst().role()).isEqualTo(Role.USER);
    assertThat(wire.getFirst().isCompactSummary()).isTrue();
    assertThat(wire.getFirst().text()).contains("state summary",
        "Continue the work from where it left off", "without re-acknowledging this summary");
    assertThat(wire.getLast()).isEqualTo(raw.getLast());
    assertThat(thread.messages()).containsExactlyElementsOf(raw);
  }

  @Test void absentZeroOrOutOfRangeCompactionSendsTheRawTranscript() {
    List<Message> raw = List.of(message("u", Role.USER, "one"));
    assertThat(ConversationWire.messages(thread(raw, List.of()))).containsExactlyElementsOf(raw);
    assertThat(ConversationWire.messages(thread(raw,
        List.of(new CompactionRecord(0, "bad", Instant.EPOCH)))))
        .containsExactlyElementsOf(raw);
    assertThat(ConversationWire.messages(thread(raw,
        List.of(new CompactionRecord(2, "bad", Instant.EPOCH)))))
        .containsExactlyElementsOf(raw);
  }

  @Test void compactionPayloadAppliesPriorSummaryTrimsToSixtyFivePercentAndAddsPrompt() {
    String large = "x".repeat(350);
    List<Message> raw = List.of(message("u0", Role.USER, "root"),
        message("a0", Role.ASSISTANT, large), message("u1", Role.USER, large),
        message("a1", Role.ASSISTANT, large), message("u2", Role.USER, "latest"));
    Thread thread = thread(raw, List.of(new CompactionRecord(1, "prior", Instant.EPOCH)));

    List<Message> wire = ConversationWire.forCompaction(thread, 400);

    assertThat(wire.getLast().role()).isEqualTo(Role.USER);
    assertThat(wire.getLast().text()).isEqualTo(ConversationWire.COMPACTION_SUMMARY_PROMPT);
    assertThat(wire.getFirst().role()).isEqualTo(Role.USER);
    assertThat(wire).noneMatch(message -> message.id().value().equals("a0"));
    assertThat(ConversationWire.estimateTokens(wire.subList(0, wire.size() - 1)))
        .isLessThanOrEqualTo((int) (400 * 0.65));
  }

  @Test void normalPayloadSoftTrimsAtNinetyFivePercentButPreservesHeadAndUserStart() {
    List<Message> raw = List.of(message("u0", Role.USER, "root"),
        message("a0", Role.ASSISTANT, "a".repeat(400)),
        message("u1", Role.USER, "b".repeat(400)),
        message("a1", Role.ASSISTANT, "c".repeat(400)),
        message("u2", Role.USER, "tail"));

    List<Message> wire = ConversationWire.forNormalTurn(thread(raw, List.of()), 300);

    assertThat(wire.getFirst()).isEqualTo(raw.getFirst());
    assertThat(wire.getFirst().role()).isEqualTo(Role.USER);
    assertThat(wire).hasSizeLessThan(raw.size());
    assertThat(ConversationWire.estimateTokens(wire)).isLessThanOrEqualTo((int) (300 * 0.95));
    assertThat(thread(raw, List.of()).messages()).containsExactlyElementsOf(raw);
  }

  @Test void estimatorMatchesNativeBytesImagesAndToolOutputApproximation() {
    ToolUse tool = new ToolUse(new ToolCallId("call"), new ToolName("bash"), Map.of("ignored", 1),
        new ToolStatus.Running("progress"));
    Message withImage = new Message(new MessageId("m"), Role.ASSISTANT, "1234567",
        List.of(new ImageContent("image/png", new byte[] {1})), List.of(), "ignored thinking",
        "", List.of(tool), Instant.EPOCH, Optional.empty(), Optional.empty(), false);
    assertThat(ConversationWire.estimateTokens(List.of(withImage)))
        .isEqualTo((int) ((7 + 4 + 8) / 3.5) + 1500);
    ToolUse done = new ToolUse(new ToolCallId("done"), new ToolName("read"), Map.of(),
        new ToolStatus.Done("output"));
    assertThat(ConversationWire.estimateTokens(List.of(withImage.withToolCalls(List.of(done)))))
        .isEqualTo((int) ((7 + 4 + 6) / 3.5) + 1500);
  }

  @Test void zeroContextDoesNotTrimAndEmptyThreadsStillGetACompactionPrompt() {
    Thread raw = thread(List.of(message("u", Role.USER, "root")), List.of());
    assertThat(ConversationWire.forNormalTurn(raw, 0)).containsExactlyElementsOf(raw.messages());
    Thread two = thread(List.of(message("u", Role.USER, "root"),
        message("a", Role.ASSISTANT, "answer")), List.of());
    assertThat(ConversationWire.forNormalTurn(two, 1)).containsExactlyElementsOf(two.messages());
    assertThat(ConversationWire.forCompaction(thread(List.of(), List.of()), 0))
        .singleElement().extracting(Message::text)
        .isEqualTo(ConversationWire.COMPACTION_SUMMARY_PROMPT);
  }

  private static Message message(String id, Role role, String text) {
    return new Message(new MessageId(id), role, text, List.of(), List.of(), "", "", List.of(),
        Instant.EPOCH, Optional.empty(), Optional.empty(), false);
  }

  private static Thread thread(List<Message> messages, List<CompactionRecord> compactions) {
    return new Thread(new ThreadId("wire"), "Wire", messages, Instant.EPOCH, Instant.EPOCH,
        compactions);
  }
}
