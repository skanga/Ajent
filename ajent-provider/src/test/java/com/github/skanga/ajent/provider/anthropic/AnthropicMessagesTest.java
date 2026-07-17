package com.github.skanga.ajent.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.domain.ImageContent;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnthropicMessagesTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void smallToolResultsShipByteIdenticallyWithoutAnElisionMarker() {
    String small = "matched 3 lines\nfoo\nbar\nbaz\n";
    String content = toolResultContent(AnthropicMessages.toJson(threadWithOutput(small)), "toolu_test_1");
    assertThat(content).isEqualTo(small).doesNotContain("bytes elided");
  }

  @Test
  void oversizedToolResultsAreBoundedAndKeepBothEnds() {
    String big = "HEAD_SENTINEL_AAAA\n" + "x".repeat(500 * 1024) + "\nTAIL_SENTINEL_ZZZZ";
    String content = toolResultContent(AnthropicMessages.toJson(threadWithOutput(big)), "toolu_test_1");
    assertThat(content.getBytes(StandardCharsets.UTF_8).length).isLessThan(80 * 1024).isLessThan(big.length());
    assertThat(content).contains("bytes elided", "HEAD_SENTINEL_AAAA", "TAIL_SENTINEL_ZZZZ");
  }

  @Test
  void multibyteCutsRemainValidUtf8AndJson() {
    String content = toolResultContent(
        AnthropicMessages.toJson(threadWithOutput("中".repeat(70 * 1024))), "toolu_test_1");
    assertThat(content).isNotEmpty().contains("bytes elided");
    assertThat(new String(content.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
        .isEqualTo(content);
  }

  @Test
  void emptyImagesAreDroppedWhileTextAndRealImagesStillShip() {
    var emptyOnly = new Thread(new ThreadId("t"), "", List.of(
        new Message(Role.USER, "look at this", List.of(new ImageContent("image/png", new byte[0])), List.of())));
    JsonNode firstWire = parse(AnthropicMessages.toJson(emptyOnly));
    assertThat(blocksOfType(firstWire, "image")).isZero();
    assertThat(blocksOfType(firstWire, "text")).isOne();

    byte[] realBytes = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 'x'};
    var mixed = new Thread(new ThreadId("t"), "", List.of(
        new Message(Role.USER, "two images", List.of(
            new ImageContent("image/png", new byte[0]),
            new ImageContent("image/png", realBytes)), List.of())));
    JsonNode secondWire = parse(AnthropicMessages.toJson(mixed));
    assertThat(blocksOfType(secondWire, "image")).isOne();
    JsonNode image = findBlock(secondWire, "image");
    assertThat(image.path("source").path("data").textValue()).isNotEmpty();
  }

  @Test
  void allToolStatusesProducePairedTruthfulResults() {
    for (ToolStatus status : List.of(
        new ToolStatus.Pending(), new ToolStatus.Approved(), new ToolStatus.Running("live"))) {
      JsonNode result = onlyToolResult(status, Map.of());
      assertThat(result.path("content").asText()).contains("did not complete");
      assertThat(result.path("is_error").asBoolean()).isTrue();
    }
    JsonNode done = onlyToolResult(new ToolStatus.Done(""), Map.of());
    assertThat(done.path("content").asText()).isEqualTo("(no output)");
    assertThat(done.path("is_error").asBoolean()).isFalse();
    JsonNode failed = onlyToolResult(new ToolStatus.Failed("boom"), Map.of());
    assertThat(failed.path("content").asText()).isEqualTo("boom");
    assertThat(failed.path("is_error").asBoolean()).isTrue();
    JsonNode rejected = onlyToolResult(new ToolStatus.Rejected(), Map.of());
    assertThat(rejected.path("content").asText()).isEqualTo("(no output)");
    assertThat(rejected.path("is_error").asBoolean()).isTrue();
  }

  @Test
  void toolArgumentsDefaultImageMediaAndCachePinsAreSerialized() {
    var tool = new ToolUse(new ToolCallId("c"), new ToolName("grep"), Map.of("query", "needle"),
        new ToolStatus.Done("ok"));
    var user = new Message(Role.USER, "", List.of(new ImageContent("", new byte[] {1})), List.of());
    var assistant = new Message(Role.ASSISTANT, "", List.of(), List.of(tool));
    JsonNode wire = parse(AnthropicMessages.toJson(
        new Thread(new ThreadId("t"), "", List.of(user, assistant))));
    assertThat(findBlock(wire, "image").path("source").path("media_type").asText())
        .isEqualTo("image/png");
    assertThat(findBlock(wire, "tool_use").path("input").path("query").asText())
        .isEqualTo("needle");
    assertThat(findBlock(wire, "tool_use").path("cache_control").path("type").asText())
        .isEqualTo("ephemeral");
    assertThat(findBlock(wire, "tool_result").path("cache_control").path("type").asText())
        .isEqualTo("ephemeral");
  }

  @Test
  void emptyMessagesAreSkippedAndSystemMessagesUseAssistantWireRole() {
    var empty = new Message(Role.USER, "", List.of(), List.of());
    var system = new Message(Role.SYSTEM, "system text", List.of(), List.of());
    JsonNode wire = parse(AnthropicMessages.toJson(
        new Thread(new ThreadId("t"), "", List.of(empty, system))));
    assertThat(wire).hasSize(1);
    assertThat(wire.path(0).path("role").asText()).isEqualTo("assistant");
  }

  @Test
  void pathologicalTinyBudgetsCutOnUtf8BoundariesWithoutAMarker() {
    assertThat(AnthropicMessages.capToolResult("中中中", 4)).isEqualTo("中");
    assertThat(AnthropicMessages.capToolResult("small", 64)).isEqualTo("small");
  }

  private static Thread threadWithOutput(String output) {
    var user = new Message(Role.USER, "do a thing", List.of(), List.of());
    var tool = new ToolUse(new ToolCallId("toolu_test_1"), new ToolName("grep"), Map.of(),
        new ToolStatus.Done(output));
    var assistant = new Message(Role.ASSISTANT, "running grep", List.of(), List.of(tool));
    return new Thread(new ThreadId("t"), "", List.of(user, assistant));
  }

  private static JsonNode onlyToolResult(ToolStatus status, Map<String, Object> arguments) {
    var tool = new ToolUse(new ToolCallId("c"), new ToolName("grep"), arguments, status);
    var message = new Message(Role.ASSISTANT, "", List.of(), List.of(tool));
    return findBlock(parse(AnthropicMessages.toJson(
        new Thread(new ThreadId("t"), "", List.of(message)))), "tool_result");
  }

  private static String toolResultContent(String wire, String toolUseId) {
    for (JsonNode message : parse(wire)) {
      if (!message.path("role").asText().equals("user")) continue;
      for (JsonNode block : message.path("content")) {
        if (block.path("type").asText().equals("tool_result")
            && block.path("tool_use_id").asText().equals(toolUseId)) {
          return block.path("content").asText();
        }
      }
    }
    return "";
  }

  private static int blocksOfType(JsonNode wire, String type) {
    int count = 0;
    for (JsonNode message : wire) for (JsonNode block : message.path("content"))
      if (block.path("type").asText().equals(type)) count++;
    return count;
  }

  private static JsonNode findBlock(JsonNode wire, String type) {
    for (JsonNode message : wire) for (JsonNode block : message.path("content"))
      if (block.path("type").asText().equals(type)) return block;
    throw new AssertionError("Missing block " + type);
  }

  private static JsonNode parse(String wire) {
    try { return JSON.readTree(wire); }
    catch (Exception exception) { throw new AssertionError(exception); }
  }
}
