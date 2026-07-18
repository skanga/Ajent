package com.github.skanga.ajent.tools.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JdkWebTransportTest {
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void sendsMethodHeadersAndUtf8BodyAndReturnsResponseMetadata() throws Exception {
    start(exchange -> {
      String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      String response = exchange.getRequestMethod() + ":"
          + exchange.getRequestHeaders().getFirst("X-Test") + ":" + requestBody;
      exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
      byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(201, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });

    var response = new JdkWebTransport().send(new WebTransport.Request(
        "POST", url("/submit"), Map.of("X-Test", "yes"), "héllo"));

    assertThat(response.status()).isEqualTo(201);
    assertThat(response.body()).isEqualTo("POST:yes:héllo");
    assertThat(response.header("content-type")).isEqualTo("text/plain; charset=utf-8");
    assertThat(response.error()).isEmpty();
  }

  @Test
  void followsNormalRedirectsAndSupportsGetAndHead() throws Exception {
    start(exchange -> {
      if (exchange.getRequestURI().getPath().equals("/redirect")) {
        exchange.getResponseHeaders().add("Location", "/final");
        exchange.sendResponseHeaders(302, -1);
      } else if (exchange.getRequestMethod().equals("HEAD")) {
        exchange.getResponseHeaders().add("X-Final", "head");
        exchange.sendResponseHeaders(204, -1);
      } else {
        byte[] bytes = "done".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
      exchange.close();
    });
    var transport = new JdkWebTransport();

    assertThat(transport.send(new WebTransport.Request(
        "GET", url("/redirect"), Map.of(), "ignored")).body()).isEqualTo("done");
    var head = transport.send(new WebTransport.Request(
        "HEAD", url("/final"), Map.of(), "ignored"));
    assertThat(head.status()).isEqualTo(204);
    assertThat(head.header("x-final")).isEqualTo("head");
    assertThat(head.body()).isEmpty();
  }

  @Test
  void convertsInvalidRequestsAndConnectionFailuresToTransportErrors() {
    var transport = new JdkWebTransport();
    var invalid = transport.send(new WebTransport.Request(
        "GET", "not a URL", Map.of(), ""));
    var refused = transport.send(new WebTransport.Request(
        "GET", "http://127.0.0.1:1/unavailable", Map.of(), ""));

    assertThat(invalid.status()).isZero();
    assertThat(invalid.error()).isNotBlank();
    assertThat(refused.status()).isZero();
    assertThat(refused.error()).isNotBlank();
  }

  private void start(com.sun.net.httpserver.HttpHandler handler) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", handler);
    server.start();
  }

  private String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }
}
