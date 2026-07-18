package com.github.skanga.ajent.tools.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Bounded synchronous JDK HTTP adapter with TLS and normal redirect support. */
public final class JdkWebTransport implements WebTransport {
  private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
  private final HttpClient client;

  public JdkWebTransport() {
    this(HttpClient.newBuilder()
        .connectTimeout(REQUEST_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build());
  }

  JdkWebTransport(HttpClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public Response send(Request request) {
    Objects.requireNonNull(request, "request");
    try {
      var builder = HttpRequest.newBuilder(URI.create(request.url())).timeout(REQUEST_TIMEOUT);
      request.headers().forEach(builder::header);
      String method = request.method() == null ? "GET" : request.method().toUpperCase(java.util.Locale.ROOT);
      HttpRequest.BodyPublisher body = method.equals("GET") || method.equals("HEAD")
          ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofString(
              request.body() == null ? "" : request.body(), StandardCharsets.UTF_8);
      builder.method(method, body);
      HttpResponse<java.io.InputStream> response = client.send(
          builder.build(), HttpResponse.BodyHandlers.ofInputStream());
      try (var stream = response.body()) {
        byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
          return failure("HTTP response exceeded 8 MiB cap");
        }
        return new Response(response.statusCode(), response.headers().map(),
            new String(bytes, StandardCharsets.UTF_8), "");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return failure("HTTP request interrupted");
    } catch (IOException | IllegalArgumentException | SecurityException exception) {
      String detail = exception.getMessage();
      return failure(detail == null || detail.isBlank()
          ? exception.getClass().getSimpleName() : detail);
    }
  }

  private static Response failure(String detail) {
    return new Response(0, Map.of(), "", detail);
  }
}
