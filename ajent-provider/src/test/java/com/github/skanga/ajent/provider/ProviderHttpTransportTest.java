package com.github.skanga.ajent.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import com.github.skanga.ajent.provider.anthropic.AnthropicRequest;
import com.github.skanga.ajent.provider.openai.Endpoint;
import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderHttpTransportTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private HttpServer server;
  @TempDir Path temporaryDirectory;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
    ApiDebugLog.closeAllForTests();
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
  void postsAnthropicRequestAndStreamsItsTypedEvents() throws Exception {
    var captured = new AtomicReference<Captured>();
    start(exchange -> {
      captured.set(capture(exchange));
      byte[] response = ("event: message_start\n"
          + "data: {\"message\":{\"usage\":{\"input_tokens\":3}}}\n\n"
          + "event: content_block_start\n"
          + "data: {\"content_block\":{\"type\":\"text\"}}\n\n"
          + "event: content_block_delta\n"
          + "data: {\"delta\":{\"type\":\"text_delta\",\"text\":\"Claude\"}}\n\n"
          + "event: content_block_stop\ndata: {}\n\n"
          + "event: message_delta\n"
          + "data: {\"delta\":{\"stop_reason\":\"end_turn\"},"
          + "\"usage\":{\"output_tokens\":2}}\n\n"
          + "event: message_stop\ndata: {}\n\n").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("content-type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0);
      for (int offset = 0; offset < response.length; offset += 7) {
        exchange.getResponseBody().write(response, offset, Math.min(7, response.length - offset));
        exchange.getResponseBody().flush();
      }
      exchange.close();
    });
    var events = new ArrayList<StreamEvent>();

    new ProviderHttpTransport(HttpClient.newHttpClient()).streamAnthropic(
        anthropicRequest(new ProviderAuth.ApiKey("anthropic-key")),
        events::add, () -> false);

    assertThat(events).containsExactly(
        new StreamEvent.Started(),
        new StreamEvent.Usage(3, 0, 0, 0),
        new StreamEvent.TextDelta("Claude"),
        new StreamEvent.TextBlockClosed(),
        new StreamEvent.Usage(0, 2, 0, 0),
        new StreamEvent.Finished(StopReason.END_TURN));
    assertThat(captured.get().apiKey()).isEqualTo("anthropic-key");
    assertThat(captured.get().path()).isEqualTo("/v1/messages?beta=true");
    assertThat(JSON.readTree(captured.get().body()).path("model").textValue())
        .isEqualTo("claude-opus-4-6");
  }

  @Test
  void appendsOptInAnthropicWireDiagnosticsWithoutLoggingCredentials() throws Exception {
    start(exchange -> {
      capture(exchange);
      byte[] response = ("event: message_start\n"
          + "data: {\"message\":{\"usage\":{\"input_tokens\":1}}}\n\n"
          + "event: message_stop\ndata: {}\n\n").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, 0);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    Path log = temporaryDirectory.resolve("raw-api.log");
    var events = new ArrayList<StreamEvent>();

    new ProviderHttpTransport(HttpClient.newHttpClient(), Map.of(
        "AJENT_DEBUG_API", "1", "AJENT_DEBUG_FILE", log.toString()))
        .streamAnthropic(anthropicRequest(new ProviderAuth.ApiKey("secret-api-key")),
            events::add, () -> false);

    String text = Files.readString(log);
    assertThat(text).contains(
        "==== request ====",
        "\"model\":\"claude-opus-4-6\"",
        "==== http status=200 ====",
        "-- chunk len=",
        "<< event=message_start data={\"message\"",
        "<< event=message_stop data={}");
    assertThat(text).doesNotContain("secret-api-key");
    assertThat(events).contains(new StreamEvent.Started(),
        new StreamEvent.Finished(StopReason.UNSPECIFIED));
  }

  @Test
  void anthropicErrorsCarryRetryHintsAndMissingAuthNeverSends() throws Exception {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    start(exchange -> {
      calls.incrementAndGet();
      capture(exchange);
      byte[] response = "{\"error\":{\"message\":\"expired\"}}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("retry-after", "11");
      exchange.sendResponseHeaders(401, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    var transport = new ProviderHttpTransport(HttpClient.newHttpClient());
    var events = new ArrayList<StreamEvent>();

    transport.streamAnthropic(anthropicRequest(new ProviderAuth.Bearer("expired")),
        events::add, () -> false);

    assertThat(events).singleElement().satisfies(event -> {
      var error = assertThat(event).asInstanceOf(
          org.assertj.core.api.InstanceOfAssertFactories.type(StreamEvent.Error.class)).actual();
      assertThat(error.message()).contains("HTTP 401", "expired", "ajent login");
      assertThat(error.retryAfter()).contains(Duration.ofSeconds(11));
      assertThat(error.errorClass()).isEqualTo(ErrorClass.AUTH);
    });

    var missing = new ArrayList<StreamEvent>();
    transport.streamAnthropic(anthropicRequest(new ProviderAuth.Empty()),
        missing::add, () -> false);
    assertThat(missing).containsExactly(new StreamEvent.Error(
        "not authenticated — run 'ajent login' or set ANTHROPIC_API_KEY"));
    assertThat(calls).hasValue(1);
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
  void ollamaNotFoundAddsThePinnedModelPullHint() throws Exception {
    start(exchange -> {
      capture(exchange);
      byte[] response = "{\"error\":\"model not found\"}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(404, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    var events = new ArrayList<StreamEvent>();

    new ProviderHttpTransport(HttpClient.newHttpClient())
        .streamOllama(request(false, new ProviderAuth.Empty()), events::add, () -> false);

    assertThat(events).containsExactly(new StreamEvent.Error(
        "HTTP 404: model not found  (model not loaded — run 'ollama pull model')",
        java.util.Optional.empty(), ErrorClass.TERMINAL, false));
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

    assertThat(events).containsExactly(new StreamEvent.Error(
        "http: [cancelled] h1 plain stream  (is the server running? start it with "
            + "'ollama serve', or check the --provider host:port)"));
  }

  @Test
  void rendersNativeDialectSpecificCancellationDetails() throws Exception {
    start(HttpExchange::close);
    var transport = new ProviderHttpTransport(HttpClient.newHttpClient());
    var anthropicEvents = new ArrayList<StreamEvent>();
    var ollamaEvents = new ArrayList<StreamEvent>();

    transport.streamAnthropic(
        anthropicRequest(new ProviderAuth.ApiKey("key")), anthropicEvents::add, () -> true);
    transport.streamOllama(
        request(false, new ProviderAuth.Empty()), ollamaEvents::add, () -> true);

    assertThat(anthropicEvents).containsExactly(
        new StreamEvent.Error("http: [cancelled] h1 plain stream"));
    assertThat(ollamaEvents).containsExactly(new StreamEvent.Error(
        "http: [cancelled] h1 plain stream  (is Ollama running? start it with "
            + "'ollama serve', or check --provider host:port)"));
  }

  @Test
  void cancellationUnblocksAStalledResponseBody() throws Exception {
    var serverRelease = new CountDownLatch(1);
    start(exchange -> {
      exchange.sendResponseHeaders(200, 0);
      exchange.getResponseBody().write(("data: {\"choices\":[{\"delta\":{"
          + "\"content\":\"started\"}}]}\n\n").getBytes(StandardCharsets.UTF_8));
      exchange.getResponseBody().flush();
      serverRelease.await(5, TimeUnit.SECONDS);
      exchange.close();
    });
    var cancelled = new AtomicBoolean();
    var firstEvent = new CountDownLatch(1);
    var events = java.util.Collections.synchronizedList(new ArrayList<StreamEvent>());
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var future = executor.submit(() -> new ProviderHttpTransport(HttpClient.newHttpClient())
          .streamOpenAi(request(false, new ProviderAuth.Bearer("token")), event -> {
            events.add(event);
            firstEvent.countDown();
          }, cancelled::get));
      assertThat(firstEvent.await(5, TimeUnit.SECONDS)).isTrue();

      cancelled.set(true);
      try {
        future.get(2, TimeUnit.SECONDS);
      } finally {
        serverRelease.countDown();
      }
    }

    assertThat(events).containsExactly(
        new StreamEvent.TextDelta("started"), new StreamEvent.Error(
            "http: [cancelled] h1 plain stream  (is the server running? start it with "
                + "'ollama serve', or check the --provider host:port)"));
  }

  @Test
  void byteIdleTimeoutTerminatesAStalledResponseAsTransient() throws Exception {
    var serverRelease = new CountDownLatch(1);
    start(exchange -> {
      exchange.sendResponseHeaders(200, 0);
      exchange.getResponseBody().flush();
      serverRelease.await(5, TimeUnit.SECONDS);
      exchange.close();
    });
    var events = new ArrayList<StreamEvent>();
    try {
      new ProviderHttpTransport(HttpClient.newHttpClient(), Duration.ofMillis(200),
          Duration.ofMillis(10)).streamOpenAi(
              request(false, new ProviderAuth.Bearer("token")), events::add, () -> false);
    } finally {
      serverRelease.countDown();
    }

    assertThat(events).singleElement().satisfies(event -> {
      var error = assertThat(event).asInstanceOf(
          org.assertj.core.api.InstanceOfAssertFactories.type(StreamEvent.Error.class)).actual();
      assertThat(error.message()).contains("idle timeout", "no bytes");
      assertThat(error.errorClass()).isEqualTo(ErrorClass.TRANSIENT);
    });
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

  private AnthropicRequest anthropicRequest(ProviderAuth auth) {
    return new AnthropicRequest(
        "claude-opus-4-6", "system",
        List.of(new Message(Role.USER, "hello", List.of(), List.of())),
        List.of(), 64_000, auth, 0, "",
        java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()
            + "/v1/messages?beta=true"), "identity");
  }

  private static Captured capture(HttpExchange exchange) throws IOException {
    return new Captured(
        exchange.getRequestMethod(),
        exchange.getRequestHeaders().getFirst("authorization"),
        exchange.getRequestHeaders().getFirst("x-api-key"),
        exchange.getRequestURI().toString(),
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
  }

  private record Captured(
      String method, String authorization, String apiKey, String path, String body) {}

  @FunctionalInterface
  private interface ThrowingHandler {
    void handle(HttpExchange exchange) throws Exception;
  }
}
