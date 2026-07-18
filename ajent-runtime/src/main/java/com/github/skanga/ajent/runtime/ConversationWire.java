package com.github.skanga.ajent.runtime;

import com.github.skanga.ajent.domain.AttachmentText;
import com.github.skanga.ajent.domain.Attachment;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.MessageId;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.ToolStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Immutable transcript-to-provider wire projection, including compaction substitution. */
public final class ConversationWire {
  static final String COMPACTION_SUMMARY_PROMPT =
      "You have been working on the task described above but have not yet completed it. Write a "
          + "continuation summary that will allow you (or another instance of yourself) to resume "
          + "work efficiently in a future context window where the conversation history will be "
          + "replaced with this summary. Your summary should be structured, concise, and "
          + "actionable. Include:\n"
          + "1. Task Overview\n"
          + "  The user's core request and success criteria\n"
          + "  Any clarifications or constraints they specified\n"
          + "2. Current State\n"
          + "  What has been completed so far\n"
          + "  Files created, modified, or analyzed (with paths if relevant)\n"
          + "  Key outputs or artifacts produced\n"
          + "3. Important Discoveries\n"
          + "  Technical constraints or requirements uncovered\n"
          + "  Decisions made and their rationale\n"
          + "  Errors encountered and how they were resolved\n"
          + "  What approaches were tried that didn't work (and why)\n"
          + "4. Next Steps\n"
          + "  Specific actions needed to complete the task\n"
          + "  Any blockers or open questions to resolve\n"
          + "  Priority order if multiple steps remain\n"
          + "5. Context to Preserve\n"
          + "  User preferences or style requirements\n"
          + "  Domain-specific details that aren't obvious\n"
          + "  Any promises made to the user\n"
          + "Be concise but complete — err on the side of including information that would "
          + "prevent duplicate work or repeated mistakes. Write in a way that enables immediate "
          + "resumption of the task. Do not call any tools; just write the summary text. Wrap the "
          + "summary in <summary></summary> tags.";

  private static final double BYTES_PER_TOKEN = 3.5;
  private static final int TOKENS_PER_IMAGE = 1_500;

  private ConversationWire() {}

  public static List<Message> messages(com.github.skanga.ajent.domain.Thread thread) {
    if (thread.compactions().isEmpty()) return thread.messages();
    var record = thread.compactions().getLast();
    if (record.upToIndex() == 0 || record.upToIndex() > thread.messages().size())
      return thread.messages();
    var result = new ArrayList<Message>(1 + thread.messages().size() - record.upToIndex());
    result.add(summaryMessage(record.summary()));
    result.addAll(thread.messages().subList(record.upToIndex(), thread.messages().size()));
    return List.copyOf(result);
  }

  public static List<Message> forNormalTurn(
      com.github.skanga.ajent.domain.Thread thread, int contextMax) {
    return forNormalTurn(thread, contextMax, Attachment::body);
  }

  static List<Message> forNormalTurn(com.github.skanga.ajent.domain.Thread thread, int contextMax,
      AttachmentText.BodyResolver resolver) {
    var result = new ArrayList<>(messages(thread));
    if (contextMax <= 0 || result.size() <= 1) return expandAttachments(result, resolver);
    int ceiling = (int) (contextMax * 0.95);
    if (ceiling <= 0) return expandAttachments(result, resolver);
    int drop = frontDropCount(result, ceiling, 1, resolver);
    if (drop > 0) result.subList(1, 1 + drop).clear();
    removeLeadingAssistants(result);
    return expandAttachments(result, resolver);
  }

  public static List<Message> forCompaction(
      com.github.skanga.ajent.domain.Thread thread, int contextMax) {
    return forCompaction(thread, contextMax, Attachment::body);
  }

  static List<Message> forCompaction(com.github.skanga.ajent.domain.Thread thread, int contextMax,
      AttachmentText.BodyResolver resolver) {
    var result = new ArrayList<>(messages(thread));
    if (contextMax > 0) {
      int ceiling = (int) (contextMax * 0.65);
      int drop = frontDropCount(result, ceiling, 0, resolver);
      if (drop > 0) result.subList(0, drop).clear();
      removeLeadingAssistants(result);
    }
    result.add(syntheticMessage(COMPACTION_SUMMARY_PROMPT, false));
    return expandAttachments(result, resolver);
  }

  public static int estimateTokens(List<Message> messages) {
    return estimateTokens(messages, Attachment::body);
  }

  private static int estimateTokens(
      List<Message> messages, AttachmentText.BodyResolver resolver) {
    long bytes = 0;
    int images = 0;
    for (Message message : messages) {
      bytes += utf8Length(AttachmentText.expand(message.text(), message.attachments(), resolver));
      images += message.images().size();
      for (var call : message.toolCalls()) {
        bytes += utf8Length(call.name().value());
        bytes += utf8Length(call.status().output());
        if (call.status() instanceof ToolStatus.Running running)
          bytes += utf8Length(running.progressText());
      }
    }
    return (int) (bytes / BYTES_PER_TOKEN) + images * TOKENS_PER_IMAGE;
  }

  private static int frontDropCount(List<Message> messages, int ceiling, int keepHead,
      AttachmentText.BodyResolver resolver) {
    if (messages.size() <= keepHead) return 0;
    long bytes = 0;
    int images = 0;
    for (Message message : messages) {
      Weight weight = weight(message, resolver);
      bytes += weight.bytes();
      images += weight.images();
    }
    int drop = 0;
    while (tokensFrom(bytes, images) > ceiling && messages.size() - drop > 1
        && keepHead + drop < messages.size()) {
      Weight weight = weight(messages.get(keepHead + drop), resolver);
      bytes -= weight.bytes();
      images -= weight.images();
      drop++;
    }
    return drop;
  }

  private static Weight weight(Message message, AttachmentText.BodyResolver resolver) {
    long bytes = utf8Length(
        AttachmentText.expand(message.text(), message.attachments(), resolver));
    for (var call : message.toolCalls()) {
      bytes += utf8Length(call.name().value());
      bytes += utf8Length(call.status().output());
      if (call.status() instanceof ToolStatus.Running running)
        bytes += utf8Length(running.progressText());
    }
    return new Weight(bytes, message.images().size());
  }

  private static int tokensFrom(long bytes, int images) {
    return (int) (bytes / BYTES_PER_TOKEN) + images * TOKENS_PER_IMAGE;
  }

  private static void removeLeadingAssistants(List<Message> messages) {
    int count = 0;
    while (count < messages.size() && messages.get(count).role() == Role.ASSISTANT) count++;
    if (count > 0) messages.subList(0, count).clear();
  }

  private static Message summaryMessage(String summary) {
    String text = "This session is being continued from a previous conversation that ran out of "
        + "context. The summary below covers the earlier portion of the conversation; recent "
        + "messages are preserved verbatim after this summary.\n\nSummary:\n" + summary
        + "\n\nContinue the work from where it left off without re-acknowledging this summary "
        + "or recapping what was happening. Pick up the last task as if the break never happened.";
    return syntheticMessage(text, true);
  }

  private static Message syntheticMessage(String text, boolean compactSummary) {
    return new Message(new MessageId(compactSummary ? "compaction-summary" : "compaction-prompt"),
        Role.USER, text, List.of(), List.of(), "", "", List.of(), Instant.EPOCH,
        Optional.empty(), Optional.empty(), compactSummary);
  }

  private static List<Message> expandAttachments(
      List<Message> messages, AttachmentText.BodyResolver resolver) {
    return messages.stream().map(message -> message.attachments().isEmpty() ? message
        : new Message(message.id(), message.role(),
            AttachmentText.expand(message.text(), message.attachments(), resolver), message.images(),
            message.attachments(), message.thinking(), message.thinkingSignature(),
            message.toolCalls(), message.timestamp(), message.checkpointId(), message.error(),
            message.textBlockClosed(), message.isCompactSummary())).toList();
  }

  private static int utf8Length(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  private record Weight(long bytes, int images) {}
}
