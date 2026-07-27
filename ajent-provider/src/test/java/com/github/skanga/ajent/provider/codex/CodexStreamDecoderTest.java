package com.github.skanga.ajent.provider.codex;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodexStreamDecoderTest {
  @Test
  void decodesFragmentedTextToolUsageAndCompletionEvents() {
    var decoder = new CodexStreamDecoder();
    String stream = """
        event: response.created
        data: {"type":"response.created","response":{"id":"resp_1"}}

        data: {"type":"response.output_text.delta","delta":"hello"}

        data: {"type":"response.output_item.added","item":{"id":"item_1","type":"function_call","call_id":"call_1","name":"read"}}

        data: {"type":"response.function_call_arguments.delta","item_id":"item_1","delta":"{\\"path\\":"}

        data: {"type":"response.function_call_arguments.done","item_id":"item_1","arguments":"{\\"path\\":\\"README.md\\"}"}

        data: {"type":"response.output_item.done","item":{"id":"item_1","type":"function_call","call_id":"call_1","name":"read","arguments":"{\\"path\\":\\"README.md\\"}"}}

        data: {"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":11,"output_tokens":7,"input_tokens_details":{"cached_tokens":3},"output_tokens_details":{"reasoning_tokens":2}}}}

        data: [DONE]

        """;
    byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);

    var first = decoder.feed(java.util.Arrays.copyOf(bytes, 73));
    var events = new java.util.ArrayList<>(first);
    events.addAll(decoder.feed(java.util.Arrays.copyOfRange(bytes, 73, bytes.length)));
    events.addAll(decoder.end());

    assertThat(events).contains(
        new StreamEvent.Started(),
        new StreamEvent.TextDelta("hello"),
        new StreamEvent.ToolUseStart("call_1", "read"),
        new StreamEvent.ToolUseDelta("{\"path\":"),
        new StreamEvent.ToolUseDelta("\"README.md\"}"),
        new StreamEvent.ToolUseEnd(),
        new StreamEvent.Usage(11, 7, 0, 3),
        new StreamEvent.Finished(StopReason.TOOL_USE));
    assertThat(events.stream().filter(StreamEvent.Finished.class::isInstance)).hasSize(1);
  }

  @Test
  void decodesReasoningAndFailuresWithoutLeakingPayloads() {
    var decoder = new CodexStreamDecoder();
    var events = decoder.feed("""
        data: {"type":"response.reasoning_summary_text.delta","delta":"checking"}

        data: {"type":"response.failed","response":{"error":{"message":"request denied"}}}

        """.getBytes(StandardCharsets.UTF_8));

    assertThat(events).contains(
        new StreamEvent.ThinkingDelta("checking", ""),
        new StreamEvent.Error("Codex: request denied"));
  }

  @Test
  void coversTextClosuresEndTurnMaxTokensAndTerminalIdempotence() {
    var decoder = new CodexStreamDecoder();
    var events = decoder.feed("""
        event: response.queued
        data: {"response":{}}

        data: {"type":"response.in_progress"}

        data: {"type":"response.output_text.delta","delta":""}

        data: {"type":"response.output_text.done"}

        data: {"type":"response.content_part.done","part":{"type":"output_text"}}

        data: {"type":"response.content_part.done","part":{"type":"image"}}

        data: {"type":"response.completed","response":{"status":"completed","output":[]}}

        """.getBytes(StandardCharsets.UTF_8));

    assertThat(events).containsExactly(
        new StreamEvent.Started(), new StreamEvent.TextBlockClosed(),
        new StreamEvent.TextBlockClosed(), new StreamEvent.Finished(StopReason.END_TURN));
    assertThat(decoder.feed("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8))).isEmpty();
    assertThat(decoder.end()).isEmpty();

    assertThat(new CodexStreamDecoder().feed("""
        data: {"type":"response.completed","response":{"status":"incomplete"}}

        """.getBytes(StandardCharsets.UTF_8)))
        .containsExactly(new StreamEvent.Finished(StopReason.MAX_TOKENS));
  }

  @Test
  void handlesToolFallbacksDuplicatesAndArgumentReconciliation() {
    var decoder = new CodexStreamDecoder();
    var events = decoder.feed("""
        data: {"type":"response.output_item.added","item":{"type":"message"}}

        data: {"type":"response.output_item.added","item":{"type":"function_call","id":"item","call_id":"","name":"bad"}}

        data: {"type":"response.output_item.added","item":{"type":"function_call","id":"item","call_id":"call","name":"write"}}

        data: {"type":"response.output_item.added","item":{"type":"function_call","id":"item","call_id":"call","name":"duplicate"}}

        data: {"type":"response.function_call_arguments.delta","call_id":"","delta":"ignored"}

        data: {"type":"response.function_call_arguments.delta","call_id":"missing","delta":"ignored"}

        data: {"type":"response.function_call_arguments.delta","item_id":"item","delta":"{\\"a\\":"}

        data: {"type":"response.function_call_arguments.done","item_id":"item","arguments":"{\\"a\\":1}"}

        data: {"type":"response.function_call_arguments.done","call_id":"call","arguments":"replacement"}

        data: {"type":"response.output_item.done","item":{"type":"message"}}

        data: {"type":"response.output_item.done","item":{"type":"function_call","id":"other","call_id":"new","name":"read","arguments":"{}"}}

        data: {"type":"response.output_item.done","item":{"type":"function_call","id":"other","call_id":"new","name":"read","arguments":"{}"}}

        data: {"type":"response.completed","response":{"output":[{"type":"function_call","call_id":"call","arguments":"replacement"}]}}

        """.getBytes(StandardCharsets.UTF_8));

    assertThat(events).contains(
        new StreamEvent.ToolUseStart("call", "write"),
        new StreamEvent.ToolUseDelta("{\"a\":"),
        new StreamEvent.ToolUseDelta("1}"),
        new StreamEvent.ToolUseDelta("replacement"),
        new StreamEvent.ToolUseStart("new", "read"),
        new StreamEvent.ToolUseEnd(),
        new StreamEvent.Finished(StopReason.TOOL_USE));
    assertThat(events.stream().filter(StreamEvent.ToolUseEnd.class::isInstance)).hasSize(2);
  }

  @Test
  void reportsCancelledRootAndMalformedErrorsAndRejectsNullInput() {
    assertThat(new CodexStreamDecoder().feed("""
        data: {"type":"response.cancelled","response":{}}

        """.getBytes(StandardCharsets.UTF_8)))
        .containsExactly(new StreamEvent.Error("Codex: request failed"));
    assertThat(new CodexStreamDecoder().feed("""
        data: {"type":"error","message":"plain failure"}

        """.getBytes(StandardCharsets.UTF_8)))
        .containsExactly(new StreamEvent.Error("Codex: plain failure"));
    assertThat(new CodexStreamDecoder().feed("""
        data: {"type":"error","error":{"message":"nested failure"}}

        """.getBytes(StandardCharsets.UTF_8)))
        .containsExactly(new StreamEvent.Error("Codex: nested failure"));

    var malformed = new CodexStreamDecoder();
    assertThat(malformed.feed("data: {\n\n".getBytes(StandardCharsets.UTF_8)))
        .containsExactly(new StreamEvent.Error("Codex: invalid Responses API event"));
    assertThat(malformed.feed("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8))).isEmpty();
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> malformed.feed(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void ignoresBlankUnknownAndUnresolvableEventsAndEndsOpenStreams() {
    var decoder = new CodexStreamDecoder();
    assertThat(decoder.feed("""
        data:

        data: {"type":"unknown"}

        data: {"type":"response.function_call_arguments.done","call_id":"none","arguments":""}

        """.getBytes(StandardCharsets.UTF_8))).isEmpty();
    assertThat(decoder.end()).isEqualTo(List.of(
        new StreamEvent.Finished(StopReason.UNSPECIFIED)));
  }
}
