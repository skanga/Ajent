package com.github.skanga.ajent.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.provider.ChatRequest;
import com.github.skanga.ajent.provider.ToolSpecification;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import java.net.http.HttpRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiRequestTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void buildsHostedChatCompletionsBodyAndBearerHeaders() throws Exception {
    var request = request(Endpoint.fromSpec("groq"), new ProviderAuth.ApiKey("sk-test"));

    var body = OpenAiWire.buildRequestBody(request);
    HttpRequest http = OpenAiWire.buildHttpRequest(request);

    assertThat(body.path("model").textValue()).isEqualTo("model-x");
    assertThat(body.path("stream").booleanValue()).isTrue();
    assertThat(body.path("max_tokens").intValue()).isEqualTo(4096);
    assertThat(body.at("/stream_options/include_usage").booleanValue()).isTrue();
    assertThat(body.at("/messages/0/role").textValue()).isEqualTo("system");
    assertThat(body.at("/messages/1/content").textValue()).isEqualTo("hello");
    assertThat(body.at("/tools/0/function/name").textValue()).isEqualTo("read");
    assertThat(http.uri().toString())
        .isEqualTo("https://api.groq.com/openai/v1/chat/completions");
    assertThat(http.method()).isEqualTo("POST");
    assertThat(http.headers().firstValue("accept")).hasValue("application/json");
    assertThat(http.headers().firstValue("content-type")).hasValue("application/json");
    assertThat(http.headers().firstValue("authorization")).hasValue("Bearer sk-test");
    assertThat(http.headers().firstValue("user-agent")).hasValue("ajent/0.2.8");
  }

  @Test
  void buildsOpenAiNativeOllamaShapeWithoutAuthorizationWhenEmpty() {
    var request = request(Endpoint.fromSpec("ollama"), new ProviderAuth.Empty());

    var body = OpenAiWire.buildRequestBody(request);
    HttpRequest http = OpenAiWire.buildHttpRequest(request);

    assertThat(body.path("max_tokens").isMissingNode()).isTrue();
    assertThat(body.at("/options/num_predict").intValue()).isEqualTo(4096);
    assertThat(body.at("/messages/0/role").textValue()).isEqualTo("system");
    assertThat(body.at("/tools/0/function/name").textValue()).isEqualTo("read");
    assertThat(http.uri().toString()).isEqualTo("http://localhost:11434/api/chat");
    assertThat(http.headers().firstValue("authorization")).isEmpty();
  }

  private static ChatRequest request(Endpoint endpoint, ProviderAuth auth) {
    return new ChatRequest(
        "model-x",
        "system text",
        List.of(new Message(Role.USER, "hello", List.of(), List.of())),
        List.of(new ToolSpecification(
            "read", "Read", JSON.createObjectNode().put("type", "object"), false)),
        4096,
        auth,
        endpoint,
        16_384,
        false);
  }
}
