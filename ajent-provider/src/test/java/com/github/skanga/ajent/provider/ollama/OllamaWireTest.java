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
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    assertThat(messages.at("/0/content").textValue())
        .isEqualTo("{\"tool_args\":{\"command\":\"ls -la\"},\"tool_name\":\"bash\"}");
    var object = JSON.readTree(messages.at("/0/content").textValue());
    assertThat(object.path("tool_name").textValue()).isEqualTo("bash");
    assertThat(object.at("/tool_args/command").textValue()).isEqualTo("ls -la");
    assertThat(messages.at("/1/role").textValue()).isEqualTo("user");
    assertThat(messages.at("/1/content").textValue())
        .contains("TOOL RESULT (bash)", "file1");
  }

  @Test
  void jsonProtocolCanonicalizesNestedToolArgumentKeysLikeNativeJson() {
    var call = doneCall("call_1", "write",
        Map.of("file_path", "x.txt", "content", "body"), "ok");

    var messages = OllamaWire.buildMessages(List.of(
        new Message(Role.ASSISTANT, "", List.of(), List.of(call))), true);

    assertThat(messages.at("/0/content").textValue()).isEqualTo(
        "{\"tool_args\":{\"content\":\"body\",\"file_path\":\"x.txt\"},"
            + "\"tool_name\":\"write\"}");
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
  void optionEnvironmentOverridesMatchAjentParsingAndPrecedence() {
    var request = new OllamaRequestOptions(16_384, 32_768, true);
    assertThat(OllamaWire.buildOptions(request, Map.of(
        "AJENT_OLLAMA_NUM_CTX", "12288trailing",
        "AJENT_OLLAMA_NUM_PREDICT", "3000x",
        "AJENT_OLLAMA_TEMPERATURE", "0.15ignored")))
        .containsEntry("num_ctx", 12_288)
        .containsEntry("num_predict", 3_000)
        .containsEntry("temperature", 0.15);
    assertThat(OllamaWire.buildOptions(request, Map.of(
        "AJENT_OLLAMA_NUM_CTX", "invalid",
        "AJENT_OLLAMA_TEMPERATURE", "nope")))
        .containsEntry("num_ctx", 32_768)
        .containsEntry("temperature", 0.2);
  }

  @Test
  void slimSystemPromptHasMemoryEnvironmentAndNoHostedClaudeSections() {
    String prompt = OllamaWire.systemPrompt();
    assertThat(prompt).containsIgnoringCase("ajent")
        .contains("CONVERSATION MEMORY", "ENVIRONMENT")
        .doesNotContain("<file-editing>", "<learned-memory");
  }

  @Test
  void systemPromptMatchesReferenceContentAndLoadsBoundedMemoryTiers(
      @TempDir Path directory) throws Exception {
    Path home = Files.createDirectories(directory.resolve("home"));
    Path project = Files.createDirectories(directory.resolve("project"));
    Files.writeString(home.resolve("CLAUDE.md"), "user guidance");
    Files.writeString(project.resolve("CLAUDE.md"), "project guidance");
    Files.writeString(project.resolve("CLAUDE.local.md"), "local guidance");

    String prompt = OllamaWire.systemPrompt(project, home, "Windows 11");

    assertThat(prompt)
        .startsWith("You are ajent, a terminal coding assistant.")
        .contains("ALWAYS use earlier messages", "There is NO `git` or `mv` tool",
            "call `search_docs` FIRST", "GitHub-flavoured markdown",
            "- os: Windows", "- shell: cmd.exe", "- cwd: " + project,
            "<user-memory>\nuser guidance", "<project-memory>\nproject guidance",
            "<local-memory>\nlocal guidance")
        .doesNotContain("ajent-compatible", "<learned-memory");
    assertThat(OllamaWire.systemPrompt(project, home, "Darwin"))
        .contains("- os: macOS", "- shell: sh");
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
