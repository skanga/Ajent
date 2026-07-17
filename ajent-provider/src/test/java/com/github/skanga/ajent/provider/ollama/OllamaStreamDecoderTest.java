package com.github.skanga.ajent.provider.ollama;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OllamaStreamDecoderTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void emitsNativeTextAsSoonAsItsLineArrives() {
    var decoder = new OllamaStreamDecoder(Set.of(), false);

    var first = decoder.feed(bytes(frame("Hello ")));
    var second = decoder.feed(bytes(frame("world")));
    var terminal = decoder.feed(bytes(done()));

    assertThat(first).containsExactly(new StreamEvent.TextDelta("Hello "));
    assertThat(second).containsExactly(new StreamEvent.TextDelta("world"));
    assertThat(terminal).containsExactly(new StreamEvent.Finished(StopReason.END_TURN));
  }

  @Test
  void arbitraryByteSplitsPreserveUtf8AndNativeUsage() {
    byte[] wire = bytes(frame("Hi 🙂") + line("{\"message\":{\"content\":\"\"},"
        + "\"done\":true,\"done_reason\":\"length\","
        + "\"prompt_eval_count\":7,\"eval_count\":2}"));
    var decoder = new OllamaStreamDecoder(Set.of(), false);
    var events = new ArrayList<StreamEvent>();

    for (byte value : wire) events.addAll(decoder.feed(new byte[] {value}));
    events.addAll(decoder.end());

    assertThat(text(events)).isEqualTo("Hi 🙂");
    assertThat(events).containsExactly(
        new StreamEvent.TextDelta("Hi 🙂"),
        new StreamEvent.Usage(7, 2),
        new StreamEvent.Finished(StopReason.MAX_TOKENS));
  }

  @Test
  void jsonProtocolResponseTextStreamsBeforeDoneWithoutScaffolding() {
    var decoder = new OllamaStreamDecoder(Set.of("bash"), true);
    var events = new ArrayList<StreamEvent>();
    for (String chunk : List.of(
        "{\"tool_name\":\"response\",\"tool_args\":{\"text\":\"Hel",
        "lo ", "Ayush!\"}}")) {
      events.addAll(decoder.feed(bytes(frame(chunk))));
    }

    assertThat(text(events)).isEqualTo("Hello Ayush!");
    assertThat(events).filteredOn(StreamEvent.TextDelta.class::isInstance).hasSize(3);
    assertThat(text(events)).doesNotContain("tool_name", "tool_args", "{");
    assertThat(decoder.feed(bytes(done())))
        .containsExactly(new StreamEvent.Finished(StopReason.END_TURN));
  }

  @Test
  void successfulCloseWithoutDoneEmitsOneTerminalEvent() {
    var decoder = new OllamaStreamDecoder(Set.of(), false);
    decoder.feed(bytes(frame("partial")));

    assertThat(decoder.end()).containsExactly(
        new StreamEvent.Finished(StopReason.UNSPECIFIED));
    assertThat(decoder.end()).isEmpty();
  }

  @Test
  void progressiveResponseDecodesEveryJsonEscapeAcrossFrames() {
    var decoder = new OllamaStreamDecoder(Set.of("bash"), true);
    var events = new ArrayList<StreamEvent>();
    for (String chunk : List.of(
        " { \n \"tool\" : \"response\", \"tool_args\":{\"content\":\"a\\n",
        "b\\t\\r\\b\\f\\/\\\\\\\"\\u2",
        "63A\"}}")) {
      events.addAll(decoder.feed(bytes(frame(chunk))));
    }

    assertThat(text(events)).isEqualTo("a\nb\t\r\b\f/\\\"☺");
    assertThat(events).filteredOn(StreamEvent.TextDelta.class::isInstance).hasSize(3);
    assertThat(decoder.feed(bytes(done())))
        .containsExactly(new StreamEvent.Finished(StopReason.END_TURN));
  }

  @Test
  void jsonProtocolWaitsForRealToolAndPlainChatUntilTerminalFrame() {
    var toolDecoder = new OllamaStreamDecoder(Set.of("bash"), true);
    assertThat(toolDecoder.feed(bytes(frame(
        "{\"name\":\"bash\",\"arguments\":{\"command\":\"pwd\"}}"))))
        .containsExactly(
            new StreamEvent.ToolUseStart("call_salvaged_0", "bash"),
            new StreamEvent.ToolUseDelta("{\"command\":\"pwd\"}"),
            new StreamEvent.ToolUseEnd());
    assertThat(toolDecoder.feed(bytes(done())))
        .containsExactly(new StreamEvent.Finished(StopReason.TOOL_USE));

    var chatDecoder = new OllamaStreamDecoder(Set.of("bash"), true);
    assertThat(chatDecoder.feed(bytes(frame("ordinary chat")))).isEmpty();
    assertThat(chatDecoder.feed(bytes(done()))).containsExactly(
        new StreamEvent.TextDelta("ordinary chat"),
        new StreamEvent.Finished(StopReason.END_TURN));
  }

  @Test
  void malformedFramesAndErrorOnlyCloseDoNotSynthesizeSuccess() {
    var malformed = new OllamaStreamDecoder(Set.of(), false);
    assertThat(malformed.feed(bytes("{bad}\n")))
        .singleElement().isInstanceOf(StreamEvent.Error.class);
    assertThat(malformed.end()).isEmpty();

    var jsonError = new OllamaStreamDecoder(Set.of(), true);
    assertThat(jsonError.feed(bytes("{\"error\":\"model missing\"}\n")))
        .containsExactly(new StreamEvent.Error("model missing"));
    assertThat(jsonError.end()).doesNotHaveAnyElementsOfTypes(StreamEvent.Finished.class);
  }

  @Test
  void incompleteAndInvalidUnicodeEscapesResumeWithoutCrashing() {
    var decoder = new OllamaStreamDecoder(Set.of("bash"), true);
    var events = new ArrayList<StreamEvent>();
    events.addAll(decoder.feed(bytes(frame(
        "{\"tool_name\":\"response\",\"tool_args\":{\"answer\":\"x\\"))));
    events.addAll(decoder.feed(bytes(frame("uZZZZy\"}}"))));

    assertThat(text(events)).isEqualTo("xuZZZZy");
  }

  private static String frame(String content) {
    try {
      return line("{\"message\":{\"content\":" + JSON.writeValueAsString(content) + "}}");
    } catch (JsonProcessingException exception) {
      throw new AssertionError(exception);
    }
  }

  private static String done() {
    return line("{\"message\":{\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\"}");
  }

  private static String line(String json) {
    return json + "\n";
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
