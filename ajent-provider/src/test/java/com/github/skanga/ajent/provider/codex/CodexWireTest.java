package com.github.skanga.ajent.provider.codex;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.ImageContent;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.provider.ToolSpecification;
import java.util.List;
import java.util.Map;
import java.net.URI;
import org.junit.jupiter.api.Test;

class CodexWireTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void serializesInitialResponsesRequestAndCodexTools() {
    var body = CodexWire.buildRequestBody(new CodexRequest(
        "gpt-5.2-codex", "You are Ajent.",
        List.of(new Message(Role.USER, "fix it", List.of(), List.of())),
        List.of(new ToolSpecification("read", "Read a file",
            JSON.createObjectNode().put("type", "object"), false)), 8192));

    assertThat(body.path("model").asText()).isEqualTo("gpt-5.2-codex");
    assertThat(body.path("instructions").asText()).isEqualTo("You are Ajent.");
    assertThat(body.path("stream").asBoolean()).isTrue();
    assertThat(body.path("store").asBoolean()).isFalse();
    assertThat(body.path("max_output_tokens").isMissingNode()).isTrue();
    assertThat(body.at("/input/0/type").asText()).isEqualTo("message");
    assertThat(body.at("/input/0/role").asText()).isEqualTo("user");
    assertThat(body.at("/input/0/content/0/type").asText()).isEqualTo("input_text");
    assertThat(body.at("/tools/0/type").asText()).isEqualTo("function");
    assertThat(body.at("/tools/0/name").asText()).isEqualTo("read");
    assertThat(body.at("/tools/0/parameters/type").asText()).isEqualTo("object");
  }

  @Test
  void serializesAssistantToolCallAndResultForStatelessContinuation() {
    ToolUse call = new ToolUse(new ToolCallId("call_7"), new ToolName("read"),
        Map.of("path", "README.md"), new ToolStatus.Done("contents"));
    var body = CodexWire.buildRequestBody(new CodexRequest(
        "gpt-5.2-codex", "", List.of(
            new Message(Role.USER, "read it", List.of(), List.of()),
            new Message(Role.ASSISTANT, "I'll inspect it.", List.of(), List.of(call))),
        List.of(), 8192));

    assertThat(body.at("/input/1/role").asText()).isEqualTo("assistant");
    assertThat(body.at("/input/1/content/0/type").asText()).isEqualTo("output_text");
    assertThat(body.at("/input/2/type").asText()).isEqualTo("function_call");
    assertThat(body.at("/input/2/call_id").asText()).isEqualTo("call_7");
    assertThat(body.at("/input/2/arguments").asText()).isEqualTo("{\"path\":\"README.md\"}");
    assertThat(body.at("/input/3/type").asText()).isEqualTo("function_call_output");
    assertThat(body.at("/input/3/output").asText()).isEqualTo("contents");
  }

  @Test
  void buildsSubscriptionResponsesRequestWithDistinctChatGptHeaders() {
    var request = new CodexRequest("gpt-5.2-codex", "", List.of(), List.of(), 8192);
    var http = CodexWire.buildHttpRequest(request,
        new CodexAuthManager.Headers("Bearer oauth-access", "acct_7"),
        URI.create("https://example.test/backend-api/codex/responses"));

    assertThat(http.uri().toString())
        .isEqualTo("https://example.test/backend-api/codex/responses");
    assertThat(http.headers().firstValue("authorization")).hasValue("Bearer oauth-access");
    assertThat(http.headers().firstValue("chatgpt-account-id")).hasValue("acct_7");
    assertThat(http.headers().firstValue("openai-beta"))
        .hasValue("responses=experimental");
    assertThat(http.headers().firstValue("user-agent")).hasValue("ajent/0.2.8");
  }

  @Test
  void serializesReasoningImagesAndEveryToolOutputFallback() {
    ToolUse rejected = new ToolUse(new ToolCallId("rejected"), new ToolName("write"),
        Map.of(), new ToolStatus.Rejected());
    ToolUse blank = new ToolUse(new ToolCallId("blank"), new ToolName("read"),
        Map.of(), new ToolStatus.Done(""));
    ToolUse pending = new ToolUse(new ToolCallId("pending"), new ToolName("bash"),
        Map.of(), new ToolStatus.Pending());
    var body = CodexWire.buildRequestBody(new CodexRequest(
        "gpt-5.3-codex-spark", "", List.of(
            new Message(Role.USER, "", List.of(
                new ImageContent("image/png", new byte[] {1, 2}),
                new ImageContent("image/png", new byte[0])), List.of()),
            new Message(Role.ASSISTANT, "", List.of(), List.of(rejected, blank, pending))),
        List.of(), 8192, "xhigh"));

    assertThat(body.at("/reasoning/effort").asText()).isEqualTo("xhigh");
    assertThat(body.at("/input/0/content/0/type").asText()).isEqualTo("input_image");
    assertThat(body.at("/input/0/content/0/image_url").asText())
        .startsWith("data:image/png;base64,");
    assertThat(body.toString()).doesNotContain("tools", "tool_choice");
    List<com.fasterxml.jackson.databind.JsonNode> input = java.util.stream.StreamSupport.stream(
        body.path("input").spliterator(), false).toList();
    assertThat(input).filteredOn(item -> item.path("call_id").asText().equals("rejected")
            && item.path("type").asText().equals("function_call_output"))
        .singleElement().satisfies(item ->
            assertThat(item.path("output").asText()).isEqualTo("(rejected by user)"));
    assertThat(input).filteredOn(item -> item.path("call_id").asText().equals("blank")
            && item.path("type").asText().equals("function_call_output"))
        .singleElement().satisfies(item ->
            assertThat(item.path("output").asText()).isEqualTo("(no output)"));
    assertThat(body.path("input").toString()).contains("\"call_id\":\"pending\"")
        .doesNotContain("\"call_id\":\"pending\",\"output\"");
  }
}
