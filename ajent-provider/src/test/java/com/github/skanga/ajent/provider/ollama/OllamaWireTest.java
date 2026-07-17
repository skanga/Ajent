package com.github.skanga.ajent.provider.ollama;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.domain.ImageContent;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OllamaWireTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void buildsNativeTextMessages() {
    var messages = OllamaWire.buildMessages(List.of(
        message(Role.USER, "hello"), message(Role.ASSISTANT, "hi there")), false);
    assertThat(messages).hasSize(2);
    assertThat(messages.at("/0/role").textValue()).isEqualTo("user");
    assertThat(messages.at("/0/content").textValue()).isEqualTo("hello");
    assertThat(messages.at("/1/role").textValue()).isEqualTo("assistant");
    assertThat(messages.at("/1/content").textValue()).isEqualTo("hi there");
  }

  @Test
  void buildsNativeToolCallWithObjectArgumentsAndToolResult() {
    var call = doneCall("call_1", "read", Map.of("path", "/etc/hostname"), "myhost");
    var messages = OllamaWire.buildMessages(List.of(
        new Message(Role.ASSISTANT, "", List.of(), List.of(call))), false);
    assertThat(messages).hasSize(2);
    assertThat(messages.at("/0/role").textValue()).isEqualTo("assistant");
    assertThat(messages.at("/0/tool_calls/0/id").textValue()).isEqualTo("call_1");
    assertThat(messages.at("/0/tool_calls/0/function/name").textValue()).isEqualTo("read");
    assertThat(messages.at("/0/tool_calls/0/function/arguments").isObject()).isTrue();
    assertThat(messages.at("/0/tool_calls/0/function/arguments/path").textValue())
        .isEqualTo("/etc/hostname");
    assertThat(messages.at("/1/role").textValue()).isEqualTo("tool");
    assertThat(messages.at("/1/tool_name").textValue()).isEqualTo("read");
    assertThat(messages.at("/1/content").textValue()).isEqualTo("myhost");
  }

  @Test
  void buildsNativeImageArrayFromNonemptyPayloads() {
    var user = new Message(Role.USER, "what is this?", List.of(
        new ImageContent("image/png", "abc".getBytes()), new ImageContent("image/png", new byte[0])),
        List.of());
    var messages = OllamaWire.buildMessages(List.of(user), false);
    assertThat(messages).hasSize(1);
    assertThat(messages.at("/0/images")).hasSize(1);
    assertThat(messages.at("/0/images/0").textValue()).isEqualTo("YWJj");
  }

  @Test
  void jsonProtocolEchoesCallAsAssistantJsonAndResultAsUserText() throws Exception {
    var call = doneCall("call_1", "bash", Map.of("command", "ls -la"), "file1\nfile2");
    var messages = OllamaWire.buildMessages(List.of(
        new Message(Role.ASSISTANT, "", List.of(), List.of(call))), true);
    assertThat(messages).hasSize(2);
    assertThat(messages.at("/0/role").textValue()).isEqualTo("assistant");
    assertThat(messages.at("/0/tool_calls").isMissingNode()).isTrue();
    var object = JSON.readTree(messages.at("/0/content").textValue());
    assertThat(object.path("tool_name").textValue()).isEqualTo("bash");
    assertThat(object.at("/tool_args/command").textValue()).isEqualTo("ls -la");
    assertThat(messages.at("/1/role").textValue()).isEqualTo("user");
    assertThat(messages.at("/1/content").textValue())
        .contains("TOOL RESULT (bash)", "file1");
  }

  @Test
  void optionsClampContextPredictionAndJsonProtocolSampling() {
    assertThat(OllamaWire.buildOptions(new OllamaRequestOptions(16_384, 0, false)))
        .containsEntry("num_ctx", 8_192).containsEntry("num_predict", 4_096)
        .doesNotContainKey("temperature");
    assertThat(OllamaWire.buildOptions(new OllamaRequestOptions(16_384, 32_768, false)))
        .containsEntry("num_ctx", 32_768).containsEntry("num_predict", 16_384);
    assertThat(OllamaWire.buildOptions(new OllamaRequestOptions(16_384, 131_072, false)))
        .containsEntry("num_ctx", 32_768);
    assertThat(OllamaWire.buildOptions(new OllamaRequestOptions(16_384, 2_048, false)))
        .containsEntry("num_ctx", 8_192);
    assertThat(OllamaWire.buildOptions(new OllamaRequestOptions(16_384, 8_192, true)))
        .containsEntry("temperature", 0.2).containsEntry("top_p", 0.9);
  }

  @Test
  void slimSystemPromptHasMemoryEnvironmentAndNoHostedClaudeSections() {
    String prompt = OllamaWire.systemPrompt();
    assertThat(prompt).containsIgnoringCase("ajent").containsIgnoringCase("agentty")
        .contains("CONVERSATION MEMORY", "ENVIRONMENT")
        .doesNotContain("<file-editing>", "<learned-memory");
  }

  private static Message message(Role role, String text) {
    return new Message(role, text, List.of(), List.of());
  }

  private static ToolUse doneCall(
      String id, String name, Map<String, Object> arguments, String output) {
    return new ToolUse(new ToolCallId(id), new ToolName(name), arguments,
        new ToolStatus.Done(output));
  }
}
