package com.github.skanga.ajent.protocol.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class McpStdioTransportTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test void correlatesConcurrentResponsesAndRoutesNotificationsAndInboundRequests()
      throws Exception {
    var transportRef = new AtomicReference<McpStdioTransport>();
    var slowId = new AtomicReference<JsonNode>();
    var slowSent = new CountDownLatch(1);
    var outbound = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    var closed = new AtomicBoolean();
    var transport = new McpStdioTransport(line -> {
      JsonNode frame = parse(line);
      outbound.add(frame);
      if ("slow".equals(frame.path("method").asText())) {
        slowId.set(frame.path("id"));
        slowSent.countDown();
      } else if ("fast".equals(frame.path("method").asText())) {
        transportRef.get().acceptLine(response(frame.path("id"), "fast-result"));
        transportRef.get().acceptLine(response(slowId.get(), "slow-result"));
      }
    }, () -> !closed.get(), () -> closed.set(true));
    transportRef.set(transport);
    var notification = new CompletableFuture<String>();
    transport.onNotification((method, parameters) -> notification.complete(
        method + ":" + parameters.path("value").asText()));

    var slow = new CompletableFuture<JsonNode>();
    java.lang.Thread.startVirtualThread(() -> {
      try {
        slow.complete(transport.request("slow", JSON.createObjectNode(), Duration.ofSeconds(5)));
      } catch (Throwable failure) {
        slow.completeExceptionally(failure);
      }
    });
    assertThat(slowSent.await(5, TimeUnit.SECONDS)).isTrue();
    JsonNode fast = transport.request("fast", JSON.createObjectNode(), Duration.ofSeconds(5));
    assertThat(fast.path("value").textValue()).isEqualTo("fast-result");
    assertThat(slow.get(5, TimeUnit.SECONDS).path("value").textValue())
        .isEqualTo("slow-result");

    transport.acceptLine("""
        {"jsonrpc":"2.0","method":"notifications/tools/list_changed",
         "params":{"value":"changed"}}
        """);
    assertThat(notification.get(5, TimeUnit.SECONDS))
        .isEqualTo("notifications/tools/list_changed:changed");
    transport.acceptLine("""
        {"jsonrpc":"2.0","id":"server-1","method":"roots/list","params":{}}
        """);
    assertThat(outbound.getLast().path("id").textValue()).isEqualTo("server-1");
    assertThat(outbound.getLast().path("error").path("code").intValue()).isEqualTo(-32601);

    transport.close();
    assertThat(closed).isTrue();
    assertThat(transport.alive()).isFalse();
  }

  @Test void mapsRpcErrorsTimeoutCancellationMalformedFramesAndRemoteClose() throws Exception {
    var transportRef = new AtomicReference<McpStdioTransport>();
    var outbound = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    var transport = new McpStdioTransport(line -> {
      JsonNode frame = parse(line);
      outbound.add(frame);
      if ("error".equals(frame.path("method").asText())) {
        transportRef.get().acceptLine("{\"jsonrpc\":\"2.0\",\"id\":"
            + frame.path("id") + ",\"error\":{\"code\":-32042,"
            + "\"message\":\"authorize\",\"data\":{\"url\":\"https://x\"}}}");
      }
    }, () -> true, () -> {});
    transportRef.set(transport);

    assertThatThrownBy(() -> transport.request(
        "error", JSON.createObjectNode(), Duration.ofSeconds(1)))
        .isInstanceOf(McpTransportException.class)
        .hasMessage("authorize")
        .satisfies(failure -> {
          var rpc = (McpTransportException) failure;
          assertThat(rpc.code()).isEqualTo(-32042);
          assertThat(rpc.data().path("url").textValue()).isEqualTo("https://x");
        });
    assertThatThrownBy(() -> transport.request(
        "hang", JSON.createObjectNode(), Duration.ofMillis(25)))
        .isInstanceOf(McpTransportException.class).hasMessageContaining("timed out");
    assertThat(outbound).anySatisfy(frame -> {
      assertThat(frame.path("method").asText()).isEqualTo("notifications/cancelled");
      assertThat(frame.path("params").path("reason").asText()).contains("timed out");
    });
    transport.acceptLine("not-json");
    transport.acceptLine("[]");
    transport.remoteClosed();
    assertThat(transport.alive()).isFalse();
    assertThatThrownBy(() -> transport.notify("notification", JSON.createObjectNode()))
        .isInstanceOf(McpTransportException.class).hasMessageContaining("closed");
  }

  private static String response(JsonNode id, String value) {
    return "{\"jsonrpc\":\"2.0\",\"id\":" + id
        + ",\"result\":{\"value\":" + quote(value) + "}}";
  }

  private static String quote(String value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private static JsonNode parse(String value) {
    try {
      return JSON.readTree(value);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }
}
