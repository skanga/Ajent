package com.github.skanga.ajent.provider;

import com.github.skanga.ajent.core.AgenttyDebugLog;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Blocking streaming HTTP adapter backed only by the JDK {@link HttpClient}. */
public final class ProviderHttpTransport {
  private static final int READ_BUFFER_SIZE = 16 * 1024;
  private static final int ERROR_BODY_MAX = 64 * 1024;
  private static final Duration DEFAULT_STREAM_IDLE_TIMEOUT = Duration.ofSeconds(90);
  private static final Duration DEFAULT_CANCEL_POLL_INTERVAL = Duration.ofMillis(50);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpClient client;
  private final Duration streamIdleTimeout;
  private final Duration cancelPollInterval;
  private final ApiDebugLog debug;

  public ProviderHttpTransport(HttpClient client) {
    this(client, System.getenv());
  }

  public ProviderHttpTransport(HttpClient client, Map<String, String> environment) {
    this(client, DEFAULT_STREAM_IDLE_TIMEOUT, DEFAULT_CANCEL_POLL_INTERVAL,
        ApiDebugLog.open(Map.copyOf(environment)));
  }

  ProviderHttpTransport(
      HttpClient client, Duration streamIdleTimeout, Duration cancelPollInterval) {
    this(client, streamIdleTimeout, cancelPollInterval, null);
  }

  private ProviderHttpTransport(
      HttpClient client, Duration streamIdleTimeout, Duration cancelPollInterval,
      ApiDebugLog debug) {
    this.client = Objects.requireNonNull(client, "client");
    this.streamIdleTimeout = requirePositive(streamIdleTimeout, "streamIdleTimeout");
    this.cancelPollInterval = requirePositive(cancelPollInterval, "cancelPollInterval");
    this.debug = debug;
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
          "not authenticated — run 'ajent login' or set ANTHROPIC_API_KEY"));
      return;
    }
    if (debug != null) debug.write("==== request ====%n%s%n==== /request ====%n",
        AnthropicWire.body(request));
    var decoder = debug == null
        ? new AnthropicStreamDecoder() : new AnthropicStreamDecoder(debug::event);
    stream(AnthropicWire.buildHttpRequest(request), decoder::feed, decoder::end,
        sink, cancelled, ProviderHttpTransport::anthropicHttpError, debug);
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
    stream(request, feed, end, sink, cancelled, errorFormatter, null);
  }

  private void stream(
      HttpRequest request,
      java.util.function.Function<byte[], List<StreamEvent>> feed,
      java.util.function.Supplier<List<StreamEvent>> end,
      Consumer<StreamEvent> sink,
      BooleanSupplier cancelled,
      java.util.function.BiFunction<Integer, byte[], String> errorFormatter,
      ApiDebugLog requestDebug) {
    Objects.requireNonNull(sink, "sink");
    Objects.requireNonNull(cancelled, "cancelled");
    Objects.requireNonNull(errorFormatter, "errorFormatter");
    if (cancelled.getAsBoolean()) {
      sink.accept(new StreamEvent.Error("cancelled"));
      return;
    }
    try {
      HttpResponse<InputStream> response = awaitResponse(request, cancelled);
      if (response == null) {
        sink.accept(new StreamEvent.Error("cancelled"));
        return;
      }
      if (requestDebug != null) requestDebug.write(
          "==== http status=%d ====%n", response.statusCode());
      try (InputStream body = response.body()) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          byte[] errorBody = body.readNBytes(ERROR_BODY_MAX);
          if (requestDebug != null) requestDebug.write("error body: %s%n",
              new String(errorBody, java.nio.charset.StandardCharsets.UTF_8));
          sink.accept(new StreamEvent.Error(
              errorFormatter.apply(response.statusCode(), errorBody), retryAfter(response),
              ProviderErrorPolicy.classifyHttpStatus(response.statusCode()), false));
          return;
        }
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        var closeReason = new AtomicReference<CloseReason>();
        var bodyOpen = new AtomicBoolean(true);
        var lastByteNanos = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        java.lang.Thread watchdog = java.lang.Thread.ofVirtual()
            .name("ajent-provider-watchdog")
            .start(() -> watchBody(body, bodyOpen, closeReason, lastByteNanos, cancelled));
        boolean terminalEvent = false;
        try {
          while (true) {
            int count = body.read(buffer);
            if (count < 0) break;
            if (count == 0) continue;
            lastByteNanos.set(System.nanoTime());
            if (requestDebug != null) requestDebug.write("-- chunk len=%d%n", count);
            terminalEvent |= dispatch(
                feed.apply(java.util.Arrays.copyOf(buffer, count)), sink);
            if (terminalEvent) return;
          }
        } catch (IOException exception) {
          if (closeReason.get() == null) throw exception;
        } finally {
          bodyOpen.set(false);
          watchdog.interrupt();
        }
        if (terminalEvent) return;
        if (closeReason.get() != null) {
          sink.accept(closeReason.get() == CloseReason.CANCELLED
              ? new StreamEvent.Error("cancelled")
              : new StreamEvent.Error("http: idle timeout (no bytes for "
                  + streamIdleTimeout.toSeconds() + "s)", Optional.empty(),
                  ErrorClass.TRANSIENT, false));
          return;
        }
        dispatch(end.get(), sink);
      }
    } catch (InterruptedException exception) {
      java.lang.Thread.currentThread().interrupt();
      sink.accept(new StreamEvent.Error("http: interrupted", Optional.empty(),
          ErrorClass.CANCELLED, false));
    } catch (IOException | ExecutionException exception) {
      sink.accept(new StreamEvent.Error("http: " + exception.getMessage(), Optional.empty(),
          ErrorClass.TRANSIENT, false));
    } catch (RuntimeException exception) {
      sink.accept(new StreamEvent.Error("http: " + exception.getMessage(), Optional.empty(),
          ErrorClass.TERMINAL, false));
    }
  }

  private HttpResponse<InputStream> awaitResponse(
      HttpRequest request, BooleanSupplier cancelled)
      throws InterruptedException, ExecutionException {
    var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
    for (;;) {
      if (cancelled.getAsBoolean()) {
        response.cancel(true);
        return null;
      }
      try {
        return response.get(cancelPollInterval.toNanos(), TimeUnit.NANOSECONDS);
      } catch (TimeoutException ignored) {
        // Poll the shared cancellation signal without imposing a total stream timeout.
      }
    }
  }

  private void watchBody(
      InputStream body,
      AtomicBoolean bodyOpen,
      AtomicReference<CloseReason> closeReason,
      java.util.concurrent.atomic.AtomicLong lastByteNanos,
      BooleanSupplier cancelled) {
    long idleNanos = streamIdleTimeout.toNanos();
    long pollNanos = cancelPollInterval.toNanos();
    while (bodyOpen.get()) {
      CloseReason reason = null;
      if (cancelled.getAsBoolean()) reason = CloseReason.CANCELLED;
      else if (System.nanoTime() - lastByteNanos.get() >= idleNanos) reason = CloseReason.IDLE;
      if (reason != null && closeReason.compareAndSet(null, reason)) {
        try {
          body.close();
        } catch (IOException ignored) {
          // The close is only a wake-up mechanism; the selected reason is authoritative.
        }
        return;
      }
      java.util.concurrent.locks.LockSupport.parkNanos(pollNanos);
      if (java.lang.Thread.interrupted()) return;
    }
  }

  private static boolean dispatch(List<StreamEvent> events, Consumer<StreamEvent> sink) {
    boolean terminal = false;
    for (StreamEvent event : events) {
      sink.accept(event);
      terminal |= event instanceof StreamEvent.Finished || event instanceof StreamEvent.Error;
    }
    return terminal;
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
      } catch (NumberFormatException failure) {
        AgenttyDebugLog.log("openai.retry_after.parse", failure);
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

  private static Duration requirePositive(Duration value, String name) {
    value = Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  private enum CloseReason { CANCELLED, IDLE }
}
