package com.github.skanga.ajent.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.provider.ToolSpecification;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AnthropicWireTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test void buildsExactOAuthRequestWithBetasCachePinsToolsAndThinking() throws Exception {
    var schema = JsonNodeFactory.instance.objectNode().put("type", "object");
    var request = new AnthropicRequest(
        "claude-opus-4-6[1m]", "system",
        List.of(new Message(Role.USER, "hello", List.of(), List.of())),
        List.of(new ToolSpecification("read", "Read", schema, true)),
        64_000, new ProviderAuth.Bearer("oauth-token"), 2, "high",
        URI.create("http://localhost:8123/v1/messages?beta=true"),
        "{\"device_id\":\"device\",\"session_id\":\"session\"}");

    HttpRequest wire = AnthropicWire.buildHttpRequest(request);
    var body = JSON.readTree(wire.bodyPublisher().orElseThrow()
        .contentLength() == 0 ? "{}" : AnthropicWire.body(request));

    assertThat(wire.method()).isEqualTo("POST");
    assertThat(wire.uri()).isEqualTo(request.endpoint());
    assertThat(wire.headers().firstValue("authorization")).contains("Bearer oauth-token");
    assertThat(wire.headers().firstValue("x-api-key")).isEmpty();
    assertThat(wire.headers().firstValue("anthropic-version")).contains("2023-06-01");
    assertThat(wire.headers().firstValue("x-app")).contains("ajent");
    assertThat(wire.headers().firstValue("anthropic-beta")).contains(
        "claude-code-20250219,oauth-2025-04-20,context-1m-2025-08-07,"
            + "context-management-2025-06-27,prompt-caching-scope-2026-01-05,"
            + "fine-grained-tool-streaming-2025-05-14");
    assertThat(body.path("model").textValue()).isEqualTo("claude-opus-4-6[1m]");
    assertThat(body.path("max_tokens").intValue()).isEqualTo(64_000);
    assertThat(body.path("stream").booleanValue()).isTrue();
    assertThat(body.path("system").get(0).path("text").textValue())
        .isEqualTo("You are Claude Code, Anthropic's official CLI for Claude.");
    assertThat(body.path("system").get(1).path("cache_control").path("type").textValue())
        .isEqualTo("ephemeral");
    assertThat(body.path("messages").get(0).path("content").get(0).path("text").textValue())
        .isEqualTo("hello");
    assertThat(body.path("tools").get(0).path("eager_input_streaming").booleanValue()).isTrue();
    assertThat(body.path("tools").get(0).path("cache_control").path("type").textValue())
        .isEqualTo("ephemeral");
    assertThat(body.path("metadata").path("user_id").textValue()).isEqualTo(request.userId());
    assertThat(body.path("thinking").path("type").textValue()).isEqualTo("adaptive");
    assertThat(body.path("output_config").path("effort").textValue()).isEqualTo("high");
  }

  @Test void apiKeyUsesItsTypedHeaderAndOmitsOAuthPreambleAndThinking() throws Exception {
    var request = new AnthropicRequest(
        "claude-haiku-4-5", "system", List.of(), List.of(), 8_192,
        new ProviderAuth.ApiKey("api-key"), 0, "", URI.create("https://example.test/messages"),
        "identity");

    HttpRequest wire = AnthropicWire.buildHttpRequest(request);
    var body = JSON.readTree(AnthropicWire.body(request));

    assertThat(wire.headers().firstValue("x-api-key")).contains("api-key");
    assertThat(wire.headers().firstValue("authorization")).isEmpty();
    assertThat(wire.headers().firstValue("anthropic-beta")).contains(
        "context-management-2025-06-27,prompt-caching-scope-2026-01-05");
    assertThat(body.path("system")).hasSize(1);
    assertThat(body.has("thinking")).isFalse();
    assertThat(body.has("output_config")).isFalse();
  }
}
