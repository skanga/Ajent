package com.github.skanga.ajent.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenAiStreamParserTest {
  @Test
  void parsesTextUsageFinishAndErrorFrames() {
    String stream = data("{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}")
        + data("{\"choices\":[{\"delta\":{\"content\":\"lo!\"}}]}")
        + data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")
        + data("{\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3,"
            + "\"prompt_tokens_details\":{\"cached_tokens\":7}}}")
        + data("[DONE]");
    var events = parseSse(stream);
    assertThat(text(events)).isEqualTo("Hello!");
    assertThat(events).filteredOn(StreamEvent.Finished.class::isInstance)
        .containsExactly(new StreamEvent.Finished(StopReason.END_TURN));
    assertThat(events).filteredOn(StreamEvent.Usage.class::isInstance)
        .containsExactly(new StreamEvent.Usage(12, 3, 0, 7));

    var error = parseSse(data(
        "{\"error\":{\"message\":\"rate limit exceeded\",\"type\":\"rate_limit\"}}"));
    assertThat(error).contains(new StreamEvent.Error("rate limit exceeded"));
  }

  @Test
  void assemblesStructuredToolArgumentsAndOrdersBoundaries() {
    String stream = data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
        + "\"id\":\"call_42\",\"type\":\"function\","
        + "\"function\":{\"name\":\"read\",\"arguments\":\"\"}}]}}]}")
        + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
            + "\"function\":{\"arguments\":\"{\\\"path\\\":\"}}]}}]}")
        + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
            + "\"function\":{\"arguments\":\"\\\"a.txt\\\"}\"}}]}}]}")
        + data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")
        + data("[DONE]");
    var events = parseSse(stream);
    assertThat(events).contains(new StreamEvent.ToolUseStart("call_42", "read"));
    assertThat(toolArguments(events)).isEqualTo("{\"path\":\"a.txt\"}");
    assertThat(events).contains(new StreamEvent.Finished(StopReason.TOOL_USE));
    int start = indexOf(events, StreamEvent.ToolUseStart.class);
    int delta = lastIndexOf(events, StreamEvent.ToolUseDelta.class);
    int end = indexOf(events, StreamEvent.ToolUseEnd.class);
    assertThat(start).isGreaterThanOrEqualTo(0);
    assertThat(delta).isGreaterThan(start);
    assertThat(end).isGreaterThan(delta);
  }

  @Test
  void closesOneStructuredToolBeforeOpeningTheNext() {
    String stream = data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
        + "\"id\":\"c0\",\"function\":{\"name\":\"glob\",\"arguments\":\"{}\"}}]}}]}")
        + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,"
            + "\"id\":\"c1\",\"function\":{\"name\":\"grep\",\"arguments\":\"{}\"}}]}}]}")
        + data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")
        + data("[DONE]");
    var events = parseSse(stream);
    assertThat(starts(events)).containsExactly(
        new StreamEvent.ToolUseStart("c0", "glob"),
        new StreamEvent.ToolUseStart("c1", "grep"));
    assertThat(events).filteredOn(StreamEvent.ToolUseEnd.class::isInstance).hasSize(2);
  }

  @Test
  void salvagesBareTaggedAndFencedToolCalls() {
    assertSalvaged("{\"name\": \"echo\", \"arguments\": {\"text\": \"hi\"}}",
        "echo", "{\"text\":\"hi\"}");
    assertSalvaged("<tool_call>\n{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"}}\n</tool_call>",
        "echo", "{\"text\":\"hi\"}");
    assertSalvaged("<tool_call>```json\n{\"name\":\"echo\",\"arguments\":{}}\n```</tool_call>",
        "echo", "{}");
  }

  @Test
  void dropsUnknownTruncatedFenceOnlyAndMetaToolLeaksWithoutShowingRawJson() {
    for (String content : List.of(
        "{\"name\":\"nonexistent\",\"arguments\":{}}",
        "{\"name\":\"remember\",\"argum",
        "```json",
        "{\"name\":\"remember\",\"arguments\":{\"text\":\"fact\"}}",
        "{\"name\":\"skill\",\"arguments\":{\"name\":\"greeting\"}}")) {
      var events = parseSse(contentFrame(content)
          + data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")
          + data("[DONE]"), Set.of("echo", "remember", "skill"));
      assertThat(starts(events)).isEmpty();
      assertThat(text(events)).doesNotContain("nonexistent", "remember", "skill", "```", "{");
      assertThat(text(events)).isNotEmpty();
    }
  }

  @Test
  void preservesPlainObjectsProseAndMarkdownCodeFences() {
    assertThat(text(parseSse(contentFrame("{\"answer\":42,\"unit\":\"none\"}")
        + finish(), Set.of("echo")))).contains("answer");
    assertThat(text(parseSse(contentFrame("Sure, here you go.") + finish(), Set.of("echo"))))
        .isEqualTo("Sure, here you go.");
    String markdown = contentFrame("```cpp\n") + contentFrame("int main() {")
        + contentFrame("\n  return 0;") + contentFrame("\n}\n```") + finish();
    assertThat(text(parseSse(markdown, Set.of("read"))))
        .contains("```cpp", "int main()", "return 0");
  }

  @Test
  void structuredToolCallsRemainStructuredWhenSalvageIsEnabled() {
    String stream = data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
        + "\"id\":\"call_1\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]}}]}")
        + data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")
        + data("[DONE]");
    assertThat(starts(parseSse(stream, Set.of("read"))))
        .containsExactly(new StreamEvent.ToolUseStart("call_1", "read"));
  }

  @Test
  void salvagesStreamedArraysSequentialObjectsAndFunctionAliasWithUniqueIds() {
    String streamed = contentFrame("{") + contentFrame("\"name") + contentFrame("\":")
        + contentFrame(" \"read") + contentFrame("\",") + contentFrame(" \"arguments")
        + contentFrame("\":") + contentFrame(" {}") + contentFrame("}") + finish();
    assertThat(starts(parseSse(streamed, Set.of("read", "write"))))
        .extracting(StreamEvent.ToolUseStart::name).containsExactly("read");

    var array = parseSse(contentFrame(
        "[{\"name\":\"read\",\"arguments\":{}},{\"name\":\"write\",\"arguments\":{}}]")
        + finish(), Set.of("read", "write"));
    assertThat(starts(array)).extracting(StreamEvent.ToolUseStart::name)
        .containsExactly("read", "write");
    assertThat(starts(array)).extracting(StreamEvent.ToolUseStart::id).doesNotHaveDuplicates();

    var sequential = parseSse(contentFrame("{\"name\":\"read\",\"arguments\":{}}")
        + contentFrame("\n")
        + contentFrame("{\"name\":\"write\",\"arguments\":{}}") + finish(),
        Set.of("read", "write"));
    assertThat(starts(sequential)).extracting(StreamEvent.ToolUseStart::name)
        .containsExactly("read", "write");

    var alias = parseSse(contentFrame(
        "{\"function\":\"echo\",\"arguments\":{\"text\":\"Hi there!\"}}")
        + finish(), Set.of("echo"));
    assertThat(starts(alias)).extracting(StreamEvent.ToolUseStart::name).containsExactly("echo");
    assertThat(toolArguments(alias)).isEqualTo("{\"text\":\"Hi there!\"}");
  }

  @Test
  void proseBeforeJsonDisablesSalvage() {
    var events = parseSse(contentFrame("Let me check.\n")
        + contentFrame("{\"name\":\"read\",\"arguments\":{}}") + finish(), Set.of("read"));
    assertThat(starts(events)).isEmpty();
    assertThat(text(events)).contains("Let me check", "read");
  }

  @Test
  void parsesNativeNdjsonTextUsageStructuredAndSalvagedCalls() {
    String greeting = line("{\"message\":{\"role\":\"assistant\",\"content\":\"Hello! \"}}")
        + line("{\"message\":{\"role\":\"assistant\",\"content\":\"How can I help?\"}}")
        + line("{\"message\":{\"role\":\"assistant\",\"content\":\"\"},"
            + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":10,\"eval_count\":5}");
    var greetingEvents = OpenAiStreamParser.parseNdjson(greeting, Set.of("read"));
    assertThat(text(greetingEvents)).isEqualTo("Hello! How can I help?");
    assertThat(greetingEvents).contains(
        new StreamEvent.Usage(10, 5), new StreamEvent.Finished(StopReason.END_TURN));

    String structured = line("{\"message\":{\"role\":\"assistant\",\"content\":\"\","
        + "\"tool_calls\":[{\"function\":{\"name\":\"read\","
        + "\"arguments\":{\"path\":\"/etc/hostname\"}}}]}}")
        + line("{\"message\":{\"role\":\"assistant\",\"content\":\"\"},"
            + "\"done\":true,\"done_reason\":\"stop\"}");
    var structuredEvents = OpenAiStreamParser.parseNdjson(structured, Set.of("read"));
    assertThat(starts(structuredEvents).getFirst().id()).startsWith("call_native_");
    assertThat(toolArguments(structuredEvents)).isEqualTo("{\"path\":\"/etc/hostname\"}");
    assertThat(structuredEvents).contains(new StreamEvent.Finished(StopReason.TOOL_USE));

    String leaked = line("{\"message\":{\"role\":\"assistant\",\"content\":"
        + "\"{\\\"name\\\":\\\"read\\\",\\\"arguments\\\":{\\\"path\\\":\\\"/etc/hostname\\\"}}\"}}")
        + line("{\"done\":true,\"done_reason\":\"stop\"}");
    var leakedEvents = OpenAiStreamParser.parseNdjson(leaked, Set.of("read"));
    assertThat(starts(leakedEvents).getFirst().id()).startsWith("call_salvaged_");
    assertThat(text(leakedEvents)).isEmpty();
  }

  @Test
  void nativeNdjsonPreservesProseAndEmptyObjectAndSalvagesJsonFence() {
    String prose = line("{\"message\":{\"role\":\"assistant\",\"content\":\"Sure.\"}}")
        + line("{\"done\":true}");
    assertThat(text(OpenAiStreamParser.parseNdjson(prose, Set.of("read")))).isEqualTo("Sure.");

    String fenced = line("{\"message\":{\"role\":\"assistant\",\"content\":"
        + "\"```json\\n{\\\"name\\\":\\\"read\\\",\\\"arguments\\\":{}}\\n```\"},\"done\":true}");
    assertThat(starts(OpenAiStreamParser.parseNdjson(fenced, Set.of("read"))))
        .extracting(StreamEvent.ToolUseStart::name).containsExactly("read");

    String emptyObject = line("{\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"done\":false}")
        + line("{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true}");
    assertThat(text(OpenAiStreamParser.parseNdjson(emptyObject, Set.of("read"))))
        .isEqualTo("{}").doesNotContain("unparseable");
  }

  @Test
  void malformedJsonFrameBecomesAnErrorInsteadOfCrashing() {
    assertThat(parseSse("data: {not-json}\n\n"))
        .singleElement().isInstanceOf(StreamEvent.Error.class);
    assertThat(OpenAiStreamParser.parseNdjson("{not-json}\n", Set.of()))
        .singleElement().isInstanceOf(StreamEvent.Error.class);
  }

  private static void assertSalvaged(String content, String name, String arguments) {
    var events = parseSse(contentFrame(content) + finish(), Set.of("echo", "read"));
    assertThat(starts(events)).extracting(StreamEvent.ToolUseStart::name).containsExactly(name);
    assertThat(toolArguments(events)).isEqualTo(arguments);
    assertThat(text(events)).isEmpty();
    assertThat(events).contains(new StreamEvent.Finished(StopReason.TOOL_USE));
  }

  private static List<StreamEvent> parseSse(String stream) {
    return parseSse(stream, Set.of());
  }

  private static List<StreamEvent> parseSse(String stream, Set<String> tools) {
    return OpenAiStreamParser.parseSse(stream, tools);
  }

  private static String data(String payload) {
    return "data: " + payload + "\n\n";
  }

  private static String line(String payload) {
    return payload + "\n";
  }

  private static String contentFrame(String content) {
    try {
      return data("{\"choices\":[{\"delta\":{\"content\":"
          + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(content) + "}}]}");
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      throw new AssertionError(exception);
    }
  }

  private static String finish() {
    return data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}") + data("[DONE]");
  }

  private static String text(List<StreamEvent> events) {
    return events.stream().filter(StreamEvent.TextDelta.class::isInstance)
        .map(StreamEvent.TextDelta.class::cast).map(StreamEvent.TextDelta::text)
        .reduce("", String::concat);
  }

  private static String toolArguments(List<StreamEvent> events) {
    return events.stream().filter(StreamEvent.ToolUseDelta.class::isInstance)
        .map(StreamEvent.ToolUseDelta.class::cast).map(StreamEvent.ToolUseDelta::partialJson)
        .reduce("", String::concat);
  }

  private static List<StreamEvent.ToolUseStart> starts(List<StreamEvent> events) {
    return events.stream().filter(StreamEvent.ToolUseStart.class::isInstance)
        .map(StreamEvent.ToolUseStart.class::cast).toList();
  }

  private static int indexOf(List<StreamEvent> events, Class<?> type) {
    for (int index = 0; index < events.size(); index++) if (type.isInstance(events.get(index))) return index;
    return -1;
  }

  private static int lastIndexOf(List<StreamEvent> events, Class<?> type) {
    for (int index = events.size() - 1; index >= 0; index--) if (type.isInstance(events.get(index))) return index;
    return -1;
  }
}
