package com.github.skanga.ajent.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenAiStreamDecoderTest {
  @Test
  void emitsTextBeforeTheSseStreamFinishes() {
    var decoder = OpenAiStreamDecoder.sse(Set.of());

    var first = decoder.feed(bytes(data(content("Hello "))));
    var second = decoder.feed(bytes(data(content("world"))));
    var terminal = decoder.feed(bytes(data(finish("stop")) + data("[DONE]")));

    assertThat(text(first)).isEqualTo("Hello ");
    assertThat(text(second)).isEqualTo("world");
    assertThat(terminal).containsExactly(new StreamEvent.Finished(StopReason.END_TURN));
  }

  @Test
  void arbitraryByteSplitsPreserveUtf8AndEventOrdering() {
    String wire = data(content("A🙂"))
        + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
            + "\"id\":\"c1\",\"function\":{\"name\":\"read\","
            + "\"arguments\":\"{\\\"path\\\":\"}}]}}]}")
        + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
            + "\"function\":{\"arguments\":\"\\\"x\\\"}\"}}]}}]}")
        + data(finish("tool_calls")) + data("[DONE]");
    byte[] bytes = bytes(wire);
    var decoder = OpenAiStreamDecoder.sse(Set.of("read"));
    var events = new ArrayList<StreamEvent>();

    for (byte value : bytes) events.addAll(decoder.feed(new byte[] {value}));
    events.addAll(decoder.end());

    assertThat(text(events)).isEqualTo("A🙂");
    assertThat(events).containsSubsequence(
        new StreamEvent.ToolUseStart("c1", "read"),
        new StreamEvent.ToolUseDelta("{\"path\":"),
        new StreamEvent.ToolUseDelta("\"x\"}"),
        new StreamEvent.ToolUseEnd(),
        new StreamEvent.Finished(StopReason.TOOL_USE));
  }

  @Test
  void nativeNdjsonStreamsCompleteLinesAndSuccessfulCloseTerminates() {
    var decoder = OpenAiStreamDecoder.ndjson(Set.of());

    var first = decoder.feed(bytes("{\"message\":{\"content\":\"Hi\"}}\r\n"));
    var terminal = decoder.end();

    assertThat(first).containsExactly(new StreamEvent.TextDelta("Hi"));
    assertThat(terminal).containsExactly(new StreamEvent.Finished(StopReason.UNSPECIFIED));
    assertThat(decoder.end()).isEmpty();
  }

  @Test
  void nativeDoneAndMalformedFramesTerminateExactlyOnce() {
    var nativeDecoder = OpenAiStreamDecoder.ndjson(Set.of());
    var events = nativeDecoder.feed(bytes(
        "{\"message\":{\"content\":\"ok\"},\"done\":true,"
            + "\"prompt_eval_count\":2,\"eval_count\":1}\n"));
    assertThat(events).containsExactly(
        new StreamEvent.TextDelta("ok"),
        new StreamEvent.Usage(2, 1),
        new StreamEvent.Finished(StopReason.END_TURN));
    assertThat(nativeDecoder.feed(bytes("ignored\n"))).isEmpty();
    assertThat(nativeDecoder.end()).isEmpty();

    var malformed = OpenAiStreamDecoder.sse(Set.of());
    assertThat(malformed.feed(bytes(data("{bad}"))))
        .singleElement().isInstanceOf(StreamEvent.Error.class);
    assertThat(malformed.end()).doesNotHaveAnyElementsOfTypes(StreamEvent.Finished.class);
  }

  private static String content(String value) {
    return "{\"choices\":[{\"delta\":{\"content\":" + json(value) + "}}]}";
  }

  private static String finish(String reason) {
    return "{\"choices\":[{\"delta\":{},\"finish_reason\":\"" + reason + "\"}]}";
  }

  private static String data(String payload) {
    return "data: " + payload + "\n\n";
  }

  private static String json(String value) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      throw new AssertionError(exception);
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String text(List<StreamEvent> events) {
    return events.stream().filter(StreamEvent.TextDelta.class::isInstance)
        .map(StreamEvent.TextDelta.class::cast).map(StreamEvent.TextDelta::text)
        .reduce("", String::concat);
  }
}
