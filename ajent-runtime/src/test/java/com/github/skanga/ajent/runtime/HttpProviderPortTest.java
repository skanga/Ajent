package com.github.skanga.ajent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.MessageId;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.SessionPhase;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.provider.ChatRequest;
import com.github.skanga.ajent.provider.ProviderHttpTransport;
import com.github.skanga.ajent.provider.anthropic.AnthropicRequest;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import com.github.skanga.ajent.provider.openai.Endpoint;
import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class HttpProviderPortTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private HttpServer server;

  @AfterEach void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test void resolvesAndRoutesEveryProviderKindAtCallTime() throws Exception {
    var paths = java.util.Collections.synchronizedList(new ArrayList<String>());
    start(exchange -> {
      paths.add(exchange.getRequestURI().getPath());
      exchange.getRequestBody().readAllBytes();
      byte[] response = switch (exchange.getRequestURI().getPath()) {
        case "/anthropic" -> ("event: content_block_delta\n"
            + "data: {\"delta\":{\"type\":\"text_delta\",\"text\":\"a\"}}\n\n"
            + "event: message_delta\n"
            + "data: {\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n"
            + "event: message_stop\ndata: {}\n\n").getBytes(StandardCharsets.UTF_8);
        case "/openai" -> ("data: {\"choices\":[{\"delta\":{\"content\":\"o\"}}]}\n\n"
            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
        case "/api/chat" -> ("{\"message\":{\"content\":\"l\"}}\n"
            + "{\"message\":{},\"done\":true,\"done_reason\":\"stop\"}\n")
            .getBytes(StandardCharsets.UTF_8);
        default -> throw new AssertionError("unexpected path");
      };
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    var selected = new AtomicReference<>("anthropic");
    var port = new HttpProviderPort(new ProviderHttpTransport(HttpClient.newHttpClient()),
        messages -> request(selected.get(), messages));

    assertThat(stream(port)).containsExactly(
        new StreamEvent.TextDelta("a"), new StreamEvent.Finished(StopReason.END_TURN));
    selected.set("openai");
    assertThat(stream(port)).containsExactly(
        new StreamEvent.TextDelta("o"), new StreamEvent.Finished(StopReason.END_TURN));
    selected.set("ollama");
    assertThat(stream(port)).containsExactly(
        new StreamEvent.TextDelta("l"), new StreamEvent.Finished(StopReason.END_TURN));
    assertThat(paths).containsExactly("/anthropic", "/openai", "/api/chat");
  }

  @Test void liveAnthropicResponseCompletesARealAgentLoopTurn() throws Exception {
    var capturedBody = new AtomicReference<String>();
    start(exchange -> {
      capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] response = ("event: content_block_delta\n"
          + "data: {\"delta\":{\"type\":\"text_delta\",\"text\":\"done\"}}\n\n"
          + "event: message_delta\n"
          + "data: {\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n"
          + "event: message_stop\ndata: {}\n\n").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    var provider = new HttpProviderPort(new ProviderHttpTransport(HttpClient.newHttpClient()),
        messages -> request("anthropic", messages));
    var idle = new CountDownLatch(1);
    var ids = new AtomicInteger();
    var reducer = new AgentReducer(new AgentReducer.Context(System::nanoTime,
        () -> Instant.parse("2026-07-17T00:00:00Z"),
        () -> new MessageId("http-" + ids.incrementAndGet()),
        call -> PermissionVerdict.ALLOW));
    Thread thread = new Thread(new ThreadId("live"), "Live", List.of());

    try (var loop = new AgentLoop(AgentState.initial(thread), reducer, provider,
        call -> new ToolCompletion.Success("unused"),
        call -> new PermissionPort.Decision(true, false), ignored -> {}, state -> {
          if (state.phase() instanceof SessionPhase.Idle
              && state.thread().messages().size() == 2) idle.countDown();
        })) {
      loop.dispatch(new RuntimeMessage.Submit("work", List.of()));
      assertThat(idle.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(loop.state().thread().messages()).extracting(Message::text)
          .containsExactly("work", "done");
    }
    assertThat(JSON.readTree(capturedBody.get()).path("messages").get(0)
        .path("content").get(0).path("text").textValue()).isEqualTo("work");
  }

  private List<StreamEvent> stream(HttpProviderPort port) {
    var events = new ArrayList<StreamEvent>();
    port.stream(1, List.of(new Message(Role.USER, "hello", List.of(), List.of())),
        new com.github.skanga.ajent.domain.CancellationSignal(), events::add);
    return events;
  }

  private HttpProviderPort.Request request(String selection, List<Message> messages) {
    int port = server.getAddress().getPort();
    return switch (selection) {
      case "anthropic" -> new HttpProviderPort.Request.Anthropic(new AnthropicRequest(
          "claude-opus-4-6", "system", messages, List.of(), 64_000,
          new ProviderAuth.ApiKey("key"), 0, "",
          URI.create("http://127.0.0.1:" + port + "/anthropic"), "identity"));
      case "openai" -> new HttpProviderPort.Request.OpenAi(new ChatRequest(
          "model", "system", messages, List.of(), 8_192, new ProviderAuth.ApiKey("key"),
          endpoint(port, "/openai", false), 8_192, false));
      case "ollama" -> new HttpProviderPort.Request.Ollama(new ChatRequest(
          "model", "system", messages, List.of(), 4_096, new ProviderAuth.Empty(),
          endpoint(port, "/api/chat", true), 8_192, false));
      default -> throw new IllegalArgumentException(selection);
    };
  }

  private static Endpoint endpoint(int port, String path, boolean nativeApi) {
    return new Endpoint("127.0.0.1", port, path, "/models", false, "test", nativeApi);
  }

  private void start(com.sun.net.httpserver.HttpHandler handler) throws java.io.IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", handler);
    server.start();
  }
}
