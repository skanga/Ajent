package com.github.skanga.ajent.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

final class AnthropicStreamDecoderTest {
  @Test void arbitraryByteSplitsPreserveTheCompleteAnthropicEventLifecycle() {
    String wire = event("message_start", """
        {"message":{"usage":{"input_tokens":5,"output_tokens":0,
        "cache_creation_input_tokens":2,"cache_read_input_tokens":3}}}
        """)
        + event("ping", "{}")
        + event("content_block_start", """
            {"content_block":{"type":"text","text":""}}
            """)
        + event("content_block_delta", """
            {"delta":{"type":"text_delta","text":"AðŸ™‚"}}
            """)
        + event("content_block_stop", "{}")
        + event("content_block_start", """
            {"content_block":{"type":"thinking","thinking":""}}
            """)
        + event("content_block_delta", """
            {"delta":{"type":"thinking_delta","thinking":"plan"}}
            """)
        + event("content_block_delta", """
            {"delta":{"type":"signature_delta","signature":"signed"}}
            """)
        + event("content_block_stop", "{}")
        + event("content_block_start", """
            {"content_block":{"type":"tool_use","id":"tool-1","name":"read","input":{}}}
            """)
        + event("content_block_delta", """
            {"delta":{"type":"input_json_delta","partial_json":"{\\\"path\\\":"}}
            """)
        + event("content_block_delta", """
            {"delta":{"type":"input_json_delta","partial_json":"\\\"x\\\"}"}}
            """)
        + event("content_block_stop", "{}")
        + event("message_delta", """
            {"delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":7}}
            """)
        + event("message_stop", "{}");
    var decoder = new AnthropicStreamDecoder();
    var events = new ArrayList<StreamEvent>();

    for (byte value : wire.getBytes(StandardCharsets.UTF_8)) {
      events.addAll(decoder.feed(new byte[] {value}));
    }
    events.addAll(decoder.end());

    assertThat(events).containsExactly(
        new StreamEvent.Started(),
        new StreamEvent.Usage(5, 0, 2, 3),
        new StreamEvent.Heartbeat(),
        new StreamEvent.TextDelta("AðŸ™‚"),
        new StreamEvent.TextBlockClosed(),
        new StreamEvent.ThinkingDelta("plan", ""),
        new StreamEvent.ThinkingDelta("", "signed"),
        new StreamEvent.ToolUseStart("tool-1", "read"),
        new StreamEvent.ToolUseDelta("{\"path\":"),
        new StreamEvent.ToolUseDelta("\"x\"}"),
        new StreamEvent.ToolUseEnd(),
        new StreamEvent.Usage(0, 7, 0, 0),
        new StreamEvent.Finished(StopReason.TOOL_USE));
    assertThat(decoder.end()).isEmpty();
  }

  @Test void closeMidToolSynthesizesEndAndTerminalExactlyOnce() {
    var decoder = new AnthropicStreamDecoder();
    var events = decoder.feed(bytes(event("content_block_start", """
        {"content_block":{"type":"tool_use","id":"t","name":"write"}}
        """)));

    assertThat(events).containsExactly(new StreamEvent.ToolUseStart("t", "write"));
    assertThat(decoder.end()).containsExactly(
        new StreamEvent.ToolUseEnd(),
        new StreamEvent.Finished(StopReason.UNSPECIFIED));
    assertThat(decoder.end()).isEmpty();
  }

  @Test void errorAndUnknownOrMalformedFramesDoNotCreateDuplicateTerminals() {
    var decoder = new AnthropicStreamDecoder();
    String wire = event("future_event", "{\"new\":true}")
        + event("content_block_delta", "{bad}")
        + event("error", """
            {"error":{"type":"overloaded_error","message":"busy"}}
            """)
        + event("message_stop", "{}");

    var events = decoder.feed(bytes(wire));

    assertThat(events).containsExactly(new StreamEvent.Error("busy"));
    assertThat(decoder.end()).isEmpty();
  }

  @Test void mapsEveryNativeStopReason() {
    assertThat(finish("end_turn")).containsExactly(new StreamEvent.Finished(StopReason.END_TURN));
    assertThat(finish("max_tokens")).containsExactly(
        new StreamEvent.Finished(StopReason.MAX_TOKENS));
    assertThat(finish("tool_use")).containsExactly(new StreamEvent.Finished(StopReason.TOOL_USE));
    assertThat(finish("stop_sequence")).containsExactly(
        new StreamEvent.Finished(StopReason.STOP_SEQUENCE));
    assertThat(finish("unknown")).containsExactly(
        new StreamEvent.Finished(StopReason.UNSPECIFIED));
  }

  private static java.util.List<StreamEvent> finish(String reason) {
    var decoder = new AnthropicStreamDecoder();
    return decoder.feed(bytes(event("message_delta",
        "{\"delta\":{\"stop_reason\":\"" + reason + "\"}}")
        + event("message_stop", "{}")));
  }

  private static String event(String name, String data) {
    String payload = data.strip().lines()
        .map(line -> "data: " + line)
        .collect(java.util.stream.Collectors.joining("\n"));
    return "event: " + name + "\n" + payload + "\n\n";
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
