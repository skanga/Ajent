package com.github.skanga.ajent.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import com.github.skanga.ajent.provider.openai.Endpoint;
import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProviderHttpTransportTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void postsOpenAiRequestAndStreamsSseChunks() throws Exception {
    var captured = new AtomicReference<Captured>();
    start(exchange -> {
      captured.set(capture(exchange));
      byte[] first = "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n"
          .getBytes(StandardCharsets.UTF_8);
      byte[] last = ("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
          + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("content-type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0);
      exchange.getResponseBody().write(first);
      exchange.getResponseBody().flush();
      exchange.getResponseBody().write(last);
      exchange.close();
    });
    var events = new ArrayList<StreamEvent>();

    new ProviderHttpTransport(HttpClient.newHttpClient())
        .streamOpenAi(request(false, new ProviderAuth.Bearer("token")), events::add, () -> false);

    assertThat(events).containsExactly(
        new StreamEvent.TextDelta("Hi"),
        new StreamEvent.Finished(StopReason.END_TURN));
    assertThat(captured.get().method()).isEqualTo("POST");
    assertThat(captured.get().authorization()).isEqualTo("Bearer token");
    assertThat(JSON.readTree(captured.get().body()).path("model").textValue()).isEqualTo("model");
  }

  @Test
  void streamsDedicatedOllamaNdjson() throws Exception {
    start(exchange -> {
      capture(exchange);
      byte[] response = ("{\"message\":{\"content\":\"local\"}}\n"
          + "{\"message\":{\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\"}\n")
          .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    var events = new ArrayList<StreamEvent>();

    new ProviderHttpTransport(HttpClient.newHttpClient())
        .streamOllama(request(false, new ProviderAuth.Empty()), events::add, () -> false);

    assertThat(events).containsExactly(
        new StreamEvent.TextDelta("local"),
        new StreamEvent.Finished(StopReason.END_TURN));
  }

  @Test
  void reportsRetryAfterForNonSuccessAndRefusesMissingHostedAuth() throws Exception {
    start(exchange -> {
      capture(exchange);
      byte[] response = "{\"error\":{\"message\":\"busy\"}}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("retry-after", "7");
      exchange.sendResponseHeaders(429, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    var events = new ArrayList<StreamEvent>();
    var transport = new ProviderHttpTransport(HttpClient.newHttpClient());

    transport.streamOpenAi(request(false, new ProviderAuth.Bearer("x")), events::add, () -> false);

    assertThat(events).singleElement().satisfies(event -> {
      var error = assertThat(event).asInstanceOf(
          org.assertj.core.api.InstanceOfAssertFactories.type(StreamEvent.Error.class)).actual();
      assertThat(error.message()).contains("429", "busy");
      assertThat(error.retryAfter()).contains(Duration.ofSeconds(7));
      assertThat(error.errorClass()).isEqualTo(ErrorClass.RATE_LIMIT);
    });

    var authEvents = new ArrayList<StreamEvent>();
    var hosted = new ChatRequest("m", "", List.of(), List.of(), 1,
        new ProviderAuth.Empty(), Endpoint.fromSpec("openai"), 0, false);
    transport.streamOpenAi(hosted, authEvents::add, () -> false);
    assertThat(authEvents).containsExactly(new StreamEvent.Error(
        "not authenticated — set the provider's API key (e.g. OPENAI_API_KEY) or run 'ajent login'"));
  }

  @Test
  void observesCancellationBeforeSending() {
    var events = new ArrayList<StreamEvent>();

    new ProviderHttpTransport(HttpClient.newHttpClient())
        .streamOpenAi(request(false, new ProviderAuth.Empty()), events::add, () -> true);

    assertThat(events).containsExactly(new StreamEvent.Error("cancelled"));
  }

  private void start(ThrowingHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      try {
        handler.handle(exchange);
      } catch (Exception exception) {
        exchange.close();
        throw new IOException(exception);
      }
    });
    server.start();
  }

  private ChatRequest request(boolean jsonProtocol, ProviderAuth auth) {
    int port = server == null ? 1 : server.getAddress().getPort();
    return new ChatRequest(
        "model", "system", List.of(new Message(Role.USER, "hello", List.of(), List.of())),
        List.of(), 1024, auth,
        new Endpoint("127.0.0.1", port, "/chat", "/models", false, "test", false),
        8192, jsonProtocol);
  }

  private static Captured capture(HttpExchange exchange) throws IOException {
    return new Captured(
        exchange.getRequestMethod(),
        exchange.getRequestHeaders().getFirst("authorization"),
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
  }

  private record Captured(String method, String authorization, String body) {}

  @FunctionalInterface
  private interface ThrowingHandler {
    void handle(HttpExchange exchange) throws Exception;
  }
}
