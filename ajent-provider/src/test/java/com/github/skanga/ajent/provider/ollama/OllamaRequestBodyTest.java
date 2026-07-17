package com.github.skanga.ajent.provider.ollama;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.provider.ChatRequest;
import com.github.skanga.ajent.provider.ToolSpecification;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import com.github.skanga.ajent.provider.openai.Endpoint;
import java.util.List;
import org.junit.jupiter.api.Test;

class OllamaRequestBodyTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void nativeBodyKeepsModelResidentAndAdvertisesNativeTools() {
    var body = OllamaWire.buildRequestBody(request(false));

    assertThat(body.path("model").textValue()).isEqualTo("qwen");
    assertThat(body.path("stream").booleanValue()).isTrue();
    assertThat(body.path("keep_alive").textValue()).isEqualTo("10m");
    assertThat(body.at("/options/num_ctx").intValue()).isEqualTo(16_384);
    assertThat(body.at("/messages/0/role").textValue()).isEqualTo("system");
    assertThat(body.at("/tools/0/function/name").textValue()).isEqualTo("bash");
    assertThat(body.path("format").isMissingNode()).isTrue();
  }

  @Test
  void jsonProtocolInlinesCatalogOmitsNativeToolsAndAddsGrammarSchema() {
    var body = OllamaWire.buildRequestBody(request(true));

    assertThat(body.path("tools").isMissingNode()).isTrue();
    assertThat(body.at("/messages/0/content").textValue())
        .contains("tool_name", "tool_args", "bash");
    assertThat(body.at("/format/type").textValue()).isEqualTo("object");
    assertThat(body.at("/format/required")).extracting(node -> node.textValue())
        .containsExactly("tool_name", "tool_args");
    assertThat(body.at("/options/temperature").doubleValue()).isEqualTo(0.2);
  }

  private static ChatRequest request(boolean jsonProtocol) {
    return new ChatRequest(
        "qwen", "system", List.of(new Message(Role.USER, "hi", List.of(), List.of())),
        List.of(new ToolSpecification(
            "bash", "Run shell", JSON.createObjectNode().put("type", "object"), false)),
        4096, new ProviderAuth.Empty(), Endpoint.fromSpec("ollama"), 16_384, jsonProtocol);
  }
}
