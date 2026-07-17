package com.github.skanga.ajent.provider.openai;

import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import com.github.skanga.ajent.provider.wire.LineFramer;
import com.github.skanga.ajent.provider.wire.SseFramer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Incremental OpenAI-compatible SSE or native NDJSON stream decoder. */
public final class OpenAiStreamDecoder {
  private enum Protocol { SSE, NDJSON }

  private final Protocol protocol;
  private final Set<String> knownTools;
  private final SseFramer sse = new SseFramer();
  private final LineFramer ndjson = new LineFramer(256 * 1024);
  private final OpenAiStreamParser.ParseContext context;
  private boolean terminal;

  private OpenAiStreamDecoder(Protocol protocol, Set<String> knownTools) {
    this.protocol = Objects.requireNonNull(protocol, "protocol");
    this.knownTools = Set.copyOf(knownTools);
    context = new OpenAiStreamParser.ParseContext(this.knownTools);
  }

  public static OpenAiStreamDecoder sse(Set<String> knownTools) {
    return new OpenAiStreamDecoder(Protocol.SSE, knownTools);
  }

  public static OpenAiStreamDecoder ndjson(Set<String> knownTools) {
    return new OpenAiStreamDecoder(Protocol.NDJSON, knownTools);
  }

  public List<StreamEvent> feed(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes");
    if (terminal) return List.of();
    if (protocol == Protocol.SSE) {
      sse.feed(bytes, event -> {
        if ("[DONE]".equals(event.data())) {
          context.markDone();
          context.complete();
          terminal = true;
        } else {
          consumeOpenAi(event.data());
        }
      });
    } else {
      ndjson.feed(bytes, this::consumeNative);
    }
    return context.drain();
  }

  /** Completes a successful HTTP body even when the provider omitted its sentinel. */
  public List<StreamEvent> end() {
    if (terminal) return List.of();
    terminal = true;
    context.complete();
    var result = new ArrayList<>(context.drain());
    if (context.allEvents().stream().noneMatch(StreamEvent.Finished.class::isInstance)
        && context.allEvents().stream().noneMatch(StreamEvent.Error.class::isInstance)) {
      result.add(new StreamEvent.Finished(StopReason.UNSPECIFIED));
    }
    return List.copyOf(result);
  }

  private void consumeOpenAi(String payload) {
    try {
      OpenAiStreamParser.consumeOpenAiFrame(OpenAiStreamParser.JSON.readTree(payload), context);
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      context.addError("invalid provider JSON: " + exception.getOriginalMessage());
    }
  }

  private void consumeNative(String line) {
    if (line.isBlank()) return;
    try {
      OpenAiStreamParser.consumeNativeFrame(OpenAiStreamParser.JSON.readTree(line), context);
      if (OpenAiStreamParser.JSON.readTree(line).path("done").asBoolean(false)) {
        context.complete();
        terminal = true;
      }
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      context.addError("invalid provider JSON: " + exception.getOriginalMessage());
    }
  }
}
