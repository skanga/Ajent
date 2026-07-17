package com.github.skanga.ajent.provider.ollama;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OllamaStreamParserTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void parsesPlainTextUsageAndFinish() {
    String stream = frame("Hello ") + frame("Ayush")
        + line("{\"message\":{\"role\":\"assistant\",\"content\":\"\"},"
            + "\"done\":true,\"done_reason\":\"stop\","
            + "\"prompt_eval_count\":10,\"eval_count\":3}");
    var events = parse(stream);
    assertThat(text(events)).isEqualTo("Hello Ayush");
    assertThat(events).contains(
        new StreamEvent.Usage(10, 3), new StreamEvent.Finished(StopReason.END_TURN));
    assertThat(starts(events)).isEmpty();
  }

  @Test
  void parsesNativeStructuredCallAndRepairsArgumentAliases() throws Exception {
    String stream = line("{\"message\":{\"role\":\"assistant\",\"content\":\"\","
        + "\"tool_calls\":[{\"id\":\"call_x\",\"function\":{\"name\":\"bash\","
        + "\"arguments\":{\"cmd\":\"ls\"}}}]},\"done_reason\":\"stop\",\"done\":true}");
    var events = parse(stream, Set.of("bash"), false);
    assertThat(starts(events)).containsExactly(new StreamEvent.ToolUseStart("call_x", "bash"));
    assertThat(events).filteredOn(StreamEvent.ToolUseEnd.class::isInstance).hasSize(1);
    var arguments = JSON.readTree(toolArguments(events));
    assertThat(arguments.path("command").textValue()).isEqualTo("ls");
    assertThat(arguments.has("cmd")).isFalse();
    assertThat(events).contains(new StreamEvent.Finished(StopReason.TOOL_USE));
  }

  @Test
  void nativeContentSalvageHonorsKnownToolsAndSwallowsFootguns() {
    var salvaged = parse(frame("{\"name\":\"write\",\"arguments\":{\"file_path\":\"/tmp/f\"}}")
        + done(), Set.of("write", "read"), false);
    assertThat(starts(salvaged)).extracting(StreamEvent.ToolUseStart::name).containsExactly("write");
    assertThat(toolArguments(salvaged)).contains("/tmp/f");
    assertThat(text(salvaged)).doesNotContain("name");

    var noTools = parse(frame("{\"name\":\"read\",\"arguments\":{}}") + done());
    assertThat(starts(noTools)).isEmpty();
    assertThat(text(noTools)).contains("name");

    var footgun = parse(frame("{\"name\":\"remember\",\"arguments\":{\"text\":\"hi\"}}")
        + done(), Set.of("remember", "write"), false);
    assertThat(starts(footgun)).isEmpty();
    assertThat(text(footgun)).doesNotContain("remember");
  }

  @Test
  void rescuesKnownToolFromNarratedFenceButNotUnknownTool() {
    String known = "Sure, I'll rename it.\n\n```sh\n"
        + "{\"name\":\"bash\",\"arguments\":{\"command\":\"mv /a /b\"}}\n```\nDone.";
    var rescued = parse(frame(known) + done(), Set.of("bash", "write"), false);
    assertThat(starts(rescued)).extracting(StreamEvent.ToolUseStart::name).containsExactly("bash");
    assertThat(toolArguments(rescued)).contains("mv /a /b");

    String unknown = "```sh\n{\"name\":\"git\",\"arguments\":{\"command\":\"mv /a /b\"}}\n```";
    assertThat(starts(parse(frame(unknown) + done(), Set.of("bash", "write"), false))).isEmpty();
  }

  @Test
  void errorFrameIsTranslated() {
    assertThat(parse(line("{\"error\":\"model not found\"" + "}")))
        .contains(new StreamEvent.Error("model not found"));
  }

  @Test
  void jsonProtocolAcceptsCanonicalAliasesLeadingProseAndActionSuffix() {
    assertTool(jsonObject("{\"thoughts\":[\"list files\"],\"tool_name\":\"bash\","
        + "\"tool_args\":{\"command\":\"ls -la\"}}"), Set.of("bash"), "bash", "ls -la");
    assertTool(jsonObject("Sure: {\"tool_name\":\"bash\",\"tool_args\":{\"command\":\"pwd\"}} done"),
        Set.of("bash"), "bash", "pwd");
    assertTool(jsonObject("{\"tool\":\"write\",\"args\":{\"file_path\":\"/tmp/x\","
        + "\"content\":\"hi\"}}"), Set.of("write"), "write", "/tmp/x");
    var action = parseJsonProtocol(jsonObject(
        "{\"tool_name\":\"bash:run\",\"tool_args\":{\"command\":\"echo hi\"}}"), Set.of("bash"));
    assertThat(starts(action)).extracting(StreamEvent.ToolUseStart::name).containsExactly("bash");
    assertThat(toolArguments(action)).contains("\"action\":\"run\"");
  }

  @Test
  void jsonProtocolPreservesPlainChatQuotedBracesAndRejectsUnknownTools() {
    var chat = parseJsonProtocol(frame("Hello! How can I help?") + done(), Set.of("bash"));
    assertThat(starts(chat)).isEmpty();
    assertThat(text(chat)).contains("How can I help");

    assertTool(jsonObject("{\"tool_name\":\"bash\",\"tool_args\":{\"command\":\"echo '}'\"}}"),
        Set.of("bash"), "bash", "echo '}'");
    assertThat(starts(parseJsonProtocol(jsonObject(
        "{\"tool_name\":\"frobnicate\",\"tool_args\":{}}"), Set.of("bash")))).isEmpty();
  }

  @Test
  void responsePseudoToolUnwrapsTextAliasesAndThoughtFallback() {
    assertResponse("{\"thoughts\":[\"hi\"],\"tool_name\":\"response\","
        + "\"tool_args\":{\"text\":\"Hello there!\"}}", "Hello there!");
    assertResponse("{\"tool_name\":\"response\",\"tool_args\":{\"response\":\"hey\"}}", "hey");
    assertResponse("{\"thoughts\":[\"You are Ayush\"],\"tool_name\":\"response\","
        + "\"tool_args\":{\"text\":\"\"}}", "You are Ayush");
  }

  @Test
  void progressiveResponseIsEmittedMoreThanOnceWithoutProtocolScaffolding() {
    List<String> chunks = List.of(
        "{\n ", " \"tool", "_name", "\": ", " \"", "response", "\",\n",
        " ", " \"tool", "_args", "\": ", " {\n", "   ", " \"", "text",
        "\": ", " \"", "Hello", " Ay", "ush", "!\"\n", " ", " }\n", "}");
    StringBuilder stream = new StringBuilder();
    chunks.forEach(chunk -> stream.append(frame(chunk)));
    stream.append(done());
    var events = parseJsonProtocol(stream.toString(), Set.of("bash"));
    assertThat(text(events)).isEqualTo("Hello Ayush!")
        .doesNotContain("tool_name", "tool_args", "{");
    assertThat(starts(events)).isEmpty();
    assertThat(events).filteredOn(StreamEvent.TextDelta.class::isInstance).hasSizeGreaterThanOrEqualTo(2);
  }

  @Test
  void responseEscapesDecodeAndStreamedRealToolIsNotHijacked() {
    assertResponse("{\"tool_name\":\"response\",\"tool_args\":{"
        + "\"text\":\"line1\\nsay \\\"hi\\\"\"}}", "line1\nsay \"hi\"");

    String tool = frame("{\"tool_name\":\"") + frame("bash\",\"tool_args\":")
        + frame("{\"command\":\"ls\"}}") + done();
    var events = parseJsonProtocol(tool, Set.of("bash"));
    assertThat(starts(events)).extracting(StreamEvent.ToolUseStart::name).containsExactly("bash");
    assertThat(toolArguments(events)).contains("ls");
    assertThat(text(events)).doesNotContain("tool_name");
  }

  @Test
  void capturedQwenResponseIsExactIncrementalAndCarriesUsage() {
    List<String> chunks = List.of("{", " \"", "tool", "_name", "\":", " \"", "response",
        "\",", " \"", "tool", "_args", "\":", " {", " \"", "text", "\":", " \"",
        "Hi", " Ay", "ush", "!\"", " }", " }");
    StringBuilder stream = new StringBuilder();
    chunks.forEach(chunk -> stream.append(frame(chunk)));
    stream.append(line("{\"message\":{\"role\":\"assistant\",\"content\":\"\"},"
        + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":51,\"eval_count\":24}"));
    var events = parseJsonProtocol(stream.toString(), Set.of("bash"));
    assertThat(text(events)).isEqualTo("Hi Ayush!").doesNotContain("{", "}", "tool_name");
    assertThat(starts(events)).isEmpty();
    assertThat(events).filteredOn(StreamEvent.TextDelta.class::isInstance).hasSizeGreaterThanOrEqualTo(2);
    assertThat(events).contains(new StreamEvent.Usage(51, 24));
  }

  @Test
  void thinkBlocksAreHiddenFromProseAndDoNotDefeatSalvage() {
    var prose = parse(frame("<think>let me reason</think>The answer is 42.") + done());
    assertThat(text(prose)).contains("The answer is 42").doesNotContain("let me reason", "<think>");

    var tool = parse(frame("<think>I should list files</think>"
        + "{\"name\":\"bash\",\"arguments\":{\"command\":\"ls\"}}") + done(),
        Set.of("bash", "write"), false);
    assertThat(starts(tool)).extracting(StreamEvent.ToolUseStart::name).containsExactly("bash");
    assertThat(text(tool)).doesNotContain("should list files");
  }

  @Test
  void salvageRepairsReadFileAliasAndEmptyProtocolTurnGetsPlaceholder() throws Exception {
    var repaired = parseJsonProtocol(jsonObject(
        "{\"tool_name\":\"read\",\"tool_args\":{\"file\":\"x.c\"}}"), Set.of("read"));
    var args = JSON.readTree(toolArguments(repaired));
    assertThat(args.path("path").textValue()).isEqualTo("x.c");
    assertThat(args.has("file")).isFalse();

    var empty = parseJsonProtocol(frame("   ") + done(), Set.of("bash"));
    assertThat(text(empty)).contains("(empty response)");
    assertThat(empty).contains(new StreamEvent.Finished(StopReason.END_TURN));
  }

  @Test
  void ordinaryProseThatStartsWithWordsIsNeverDropped() {
    assertThat(text(parse(frame("Here is the answer: 42.") + done(), Set.of("bash"), false)))
        .contains("42");
  }

  private static void assertTool(
      String stream, Set<String> knownTools, String expectedName, String expectedArgsPart) {
    var events = parseJsonProtocol(stream, knownTools);
    assertThat(starts(events)).extracting(StreamEvent.ToolUseStart::name).containsExactly(expectedName);
    assertThat(toolArguments(events)).contains(expectedArgsPart);
    assertThat(text(events)).doesNotContain("thoughts", "tool_name");
  }

  private static void assertResponse(String object, String expected) {
    var events = parseJsonProtocol(jsonObject(object), Set.of("bash"));
    assertThat(text(events)).isEqualTo(expected).doesNotContain("tool_name");
    assertThat(starts(events)).isEmpty();
  }

  private static List<StreamEvent> parse(String bytes) {
    return parse(bytes, Set.of(), false);
  }

  private static List<StreamEvent> parse(String bytes, Set<String> tools, boolean jsonProtocol) {
    return OllamaStreamParser.parseNdjson(bytes, tools, jsonProtocol);
  }

  private static List<StreamEvent> parseJsonProtocol(String bytes, Set<String> tools) {
    return parse(bytes, tools, true);
  }

  private static String jsonObject(String object) {
    return frame(object) + done();
  }

  private static String frame(String content) {
    try {
      return line("{\"message\":{\"role\":\"assistant\",\"content\":"
          + JSON.writeValueAsString(content) + "}}");
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private static String done() {
    return line("{\"message\":{\"role\":\"assistant\",\"content\":\"\"},"
        + "\"done\":true,\"done_reason\":\"stop\"}");
  }

  private static String line(String json) {
    return json + "\n";
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
}
