package com.github.skanga.ajent.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record Message(
    MessageId id, Role role, String text, List<ImageContent> images,
    List<Attachment> attachments, String thinking, String thinkingSignature,
    List<ToolUse> toolCalls, Instant timestamp, Optional<CheckpointId> checkpointId,
    Optional<String> error, boolean isCompactSummary) {
  public Message(Role role, String text, List<ImageContent> images, List<ToolUse> toolCalls) {
    this(MessageId.random(), role, text, images, List.of(), "", "", toolCalls,
        Instant.now(), Optional.empty(), Optional.empty(), false);
  }

  public Message {
    id = Objects.requireNonNull(id, "id");
    role = Objects.requireNonNull(role, "role");
    text = Objects.requireNonNull(text, "text");
    images = List.copyOf(images);
    attachments = List.copyOf(attachments);
    thinking = Objects.requireNonNull(thinking, "thinking");
    thinkingSignature = Objects.requireNonNull(thinkingSignature, "thinkingSignature");
    toolCalls = List.copyOf(toolCalls);
    timestamp = Objects.requireNonNull(timestamp, "timestamp");
    checkpointId = Objects.requireNonNull(checkpointId, "checkpointId");
    error = Objects.requireNonNull(error, "error");
  }

  public Message withToolCalls(List<ToolUse> revisedToolCalls) {
    return new Message(id, role, text, images, attachments, thinking, thinkingSignature,
        revisedToolCalls, timestamp, checkpointId, error, isCompactSummary);
  }
}
