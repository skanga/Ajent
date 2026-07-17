package com.github.skanga.ajent.provider.openai;

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
import com.github.skanga.ajent.provider.ToolSpecification;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAiWireBuildersTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void translatesToolSpecificationToOpenAiFunctionSchema() throws Exception {
    JsonNode parameters = JSON.readTree(
        "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}");
    var tools = OpenAiWire.buildTools(List.of(
        new ToolSpecification("read", "Read a file", parameters, false)));

    assertThat(tools.isArray()).isTrue();
    assertThat(tools).hasSize(1);
    assertThat(tools.at("/0/type").textValue()).isEqualTo("function");
    assertThat(tools.at("/0/function/name").textValue()).isEqualTo("read");
    assertThat(tools.at("/0/function/description").textValue()).isEqualTo("Read a file");
    assertThat(tools.at("/0/function/parameters/type").textValue()).isEqualTo("object");
  }

  @Test
  void translatesBasicUserMessage() {
    var messages = OpenAiWire.buildMessages(thread(
        new Message(Role.USER, "hello", List.of(), List.of())));

    assertThat(messages).hasSize(1);
    assertThat(messages.at("/0/role").textValue()).isEqualTo("user");
    assertThat(messages.at("/0/content").textValue()).isEqualTo("hello");
  }

  @Test
  void translatesAssistantToolCallAndSeparateToolResult() {
    var call = new ToolUse(
        new ToolCallId("call_abc"), new ToolName("read"), Map.of("path", "foo.txt"),
        new ToolStatus.Done("file contents here"));
    var messages = OpenAiWire.buildMessages(thread(
        new Message(Role.ASSISTANT, "let me check", List.of(), List.of(call))));

    assertThat(messages).hasSize(2);
    assertThat(messages.at("/0/role").textValue()).isEqualTo("assistant");
    assertThat(messages.at("/0/content").textValue()).isEqualTo("let me check");
    assertThat(messages.at("/0/tool_calls/0/id").textValue()).isEqualTo("call_abc");
    assertThat(messages.at("/0/tool_calls/0/type").textValue()).isEqualTo("function");
    assertThat(messages.at("/0/tool_calls/0/function/name").textValue()).isEqualTo("read");
    assertThat(messages.at("/0/tool_calls/0/function/arguments").isTextual()).isTrue();
    assertThat(messages.at("/0/tool_calls/0/function/arguments").textValue())
        .isEqualTo("{\"path\":\"foo.txt\"}");
    assertThat(messages.at("/1/role").textValue()).isEqualTo("tool");
    assertThat(messages.at("/1/tool_call_id").textValue()).isEqualTo("call_abc");
    assertThat(messages.at("/1/content").textValue()).isEqualTo("file contents here");
  }

  @Test
  void translatesImagesAsOpenAiMultimodalContentAndDropsEmptyImages() {
    var message = new Message(
        Role.USER,
        "look",
        List.of(new ImageContent("image/png", new byte[] {1, 2, 3}),
            new ImageContent("", new byte[0])),
        List.of());
    var messages = OpenAiWire.buildMessages(thread(message));

    assertThat(messages.at("/0/content")).hasSize(2);
    assertThat(messages.at("/0/content/0/type").textValue()).isEqualTo("text");
    assertThat(messages.at("/0/content/0/text").textValue()).isEqualTo("look");
    assertThat(messages.at("/0/content/1/type").textValue()).isEqualTo("image_url");
    assertThat(messages.at("/0/content/1/image_url/url").textValue())
        .isEqualTo("data:image/png;base64,AQID");
  }

  @Test
  void omitsEmptyMessagesAndUsesTruthfulToolResultFallbacks() {
    var incomplete = new ToolUse(
        new ToolCallId("pending"), new ToolName("read"), Map.of(), new ToolStatus.Pending());
    var failed = new ToolUse(
        new ToolCallId("failed"), new ToolName("read"), Map.of(), new ToolStatus.Failed("no"));
    var messages = OpenAiWire.buildMessages(thread(
        new Message(Role.ASSISTANT, "", List.of(), List.of()),
        new Message(Role.ASSISTANT, "", List.of(), List.of(incomplete, failed))));

    assertThat(messages).hasSize(3);
    assertThat(messages.at("/1/content").textValue()).isEqualTo("(no output)");
    assertThat(messages.at("/2/content").textValue()).isEqualTo("no");
  }

  private static Thread thread(Message... messages) {
    return new Thread(new ThreadId("t"), "", List.of(messages));
  }
}
