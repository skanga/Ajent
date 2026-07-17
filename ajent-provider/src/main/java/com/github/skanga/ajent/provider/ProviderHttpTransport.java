package com.github.skanga.ajent.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.provider.ollama.OllamaStreamDecoder;
import com.github.skanga.ajent.provider.ollama.OllamaWire;
import com.github.skanga.ajent.provider.anthropic.AnthropicRequest;
import com.github.skanga.ajent.provider.anthropic.AnthropicStreamDecoder;
import com.github.skanga.ajent.provider.anthropic.AnthropicWire;
import com.github.skanga.ajent.provider.openai.OpenAiStreamDecoder;
import com.github.skanga.ajent.provider.openai.OpenAiWire;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Blocking streaming HTTP adapter backed only by the JDK {@link HttpClient}. */
public final class ProviderHttpTransport {
  private static final int READ_BUFFER_SIZE = 16 * 1024;
  private static final int ERROR_BODY_MAX = 64 * 1024;
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpClient client;

  public ProviderHttpTransport(HttpClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  public void streamOpenAi(
      ChatRequest request, Consumer<StreamEvent> sink, BooleanSupplier cancelled) {
    Objects.requireNonNull(request, "request");
    if (request.endpoint().useTls() && request.auth().isEmpty()) {
      sink.accept(new StreamEvent.Error(
          "not authenticated — set the provider's API key (e.g. OPENAI_API_KEY) "
              + "or run 'ajent login'"));
      return;
    }
    OpenAiStreamDecoder decoder = request.endpoint().nativeApi()
        ? OpenAiStreamDecoder.ndjson(toolNames(request))
        : OpenAiStreamDecoder.sse(toolNames(request));
    stream(OpenAiWire.buildHttpRequest(request), decoder::feed, decoder::end,
        sink, cancelled);
  }

  public void streamAnthropic(
      AnthropicRequest request, Consumer<StreamEvent> sink, BooleanSupplier cancelled) {
    Objects.requireNonNull(request, "request");
    if (request.auth().isEmpty()) {
      sink.accept(new StreamEvent.Error(
          "not authenticated â€” run 'ajent login' or set ANTHROPIC_API_KEY"));
      return;
    }
    var decoder = new AnthropicStreamDecoder();
    stream(AnthropicWire.buildHttpRequest(request), decoder::feed, decoder::end,
        sink, cancelled, ProviderHttpTransport::anthropicHttpError);
  }

  public void streamOllama(
      ChatRequest request, Consumer<StreamEvent> sink, BooleanSupplier cancelled) {
    var decoder = new OllamaStreamDecoder(toolNames(request), request.jsonProtocol());
    stream(OllamaWire.buildHttpRequest(request), decoder::feed, decoder::end,
        sink, cancelled);
  }

  private void stream(
      HttpRequest request,
      java.util.function.Function<byte[], List<StreamEvent>> feed,
      java.util.function.Supplier<List<StreamEvent>> end,
      Consumer<StreamEvent> sink,
      BooleanSupplier cancelled) {
    stream(request, feed, end, sink, cancelled, ProviderHttpTransport::httpError);
  }

  private void stream(
      HttpRequest request,
      java.util.function.Function<byte[], List<StreamEvent>> feed,
      java.util.function.Supplier<List<StreamEvent>> end,
      Consumer<StreamEvent> sink,
      BooleanSupplier cancelled,
      java.util.function.BiFunction<Integer, byte[], String> errorFormatter) {
    Objects.requireNonNull(sink, "sink");
    Objects.requireNonNull(cancelled, "cancelled");
    Objects.requireNonNull(errorFormatter, "errorFormatter");
    if (cancelled.getAsBoolean()) {
      sink.accept(new StreamEvent.Error("cancelled"));
      return;
    }
    try {
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          byte[] errorBody = body.readNBytes(ERROR_BODY_MAX);
          sink.accept(new StreamEvent.Error(
              errorFormatter.apply(response.statusCode(), errorBody), retryAfter(response),
              ProviderErrorPolicy.classifyHttpStatus(response.statusCode()), false));
          return;
        }
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        while (true) {
          if (cancelled.getAsBoolean()) {
            sink.accept(new StreamEvent.Error("cancelled"));
            return;
          }
          int count = body.read(buffer);
          if (count < 0) break;
          if (count == 0) continue;
          dispatch(feed.apply(java.util.Arrays.copyOf(buffer, count)), sink);
        }
        dispatch(end.get(), sink);
      }
    } catch (InterruptedException exception) {
      java.lang.Thread.currentThread().interrupt();
      sink.accept(new StreamEvent.Error("http: interrupted", Optional.empty(),
          ErrorClass.CANCELLED, false));
    } catch (IOException exception) {
      sink.accept(new StreamEvent.Error("http: " + exception.getMessage(), Optional.empty(),
          ErrorClass.TRANSIENT, false));
    } catch (RuntimeException exception) {
      sink.accept(new StreamEvent.Error("http: " + exception.getMessage(), Optional.empty(),
          ErrorClass.TERMINAL, false));
    }
  }

  private static void dispatch(List<StreamEvent> events, Consumer<StreamEvent> sink) {
    events.forEach(sink);
  }

  private static java.util.Set<String> toolNames(ChatRequest request) {
    return request.tools().stream().map(ToolSpecification::name)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private static Optional<Duration> retryAfter(HttpResponse<?> response) {
    return response.headers().firstValue("retry-after").flatMap(value -> {
      try {
        long seconds = Long.parseLong(value);
        return seconds > 0 ? Optional.of(Duration.ofSeconds(seconds)) : Optional.empty();
      } catch (NumberFormatException ignored) {
        return Optional.empty();
      }
    });
  }

  private static String httpError(int status, byte[] body) {
    String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
    try {
      JsonNode error = JSON.readTree(text).path("error");
      String message = error.path("message").asText(error.isTextual() ? error.textValue() : text);
      return "HTTP " + status + ": " + message;
    } catch (IOException ignored) {
      return "HTTP " + status + ": " + text;
    }
  }

  private static String anthropicHttpError(int status, byte[] body) {
    String message = httpError(status, body);
    return status == 401 || status == 403
        ? message + "  (run 'ajent login' to re-authenticate)"
        : message;
  }
}
