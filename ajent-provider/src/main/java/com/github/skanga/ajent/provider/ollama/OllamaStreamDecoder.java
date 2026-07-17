package com.github.skanga.ajent.provider.ollama;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import com.github.skanga.ajent.provider.wire.LineFramer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Incremental decoder for Ollama's {@code /api/chat} NDJSON response body. */
public final class OllamaStreamDecoder {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> TOOL_KEYS = List.of("tool_name", "tool", "name");
  private static final List<String> RESPONSE_KEYS =
      List.of("text", "response", "content", "message", "answer");

  private final Set<String> knownTools;
  private final boolean jsonProtocol;
  private final LineFramer lines = new LineFramer(256 * 1024);
  private final StringBuilder framedWire = new StringBuilder();
  private final StringBuilder content = new StringBuilder();
  private final Map<StreamEvent, Integer> delivered = new HashMap<>();
  private boolean terminal;
  private boolean responseActive;
  private boolean responseRejected;
  private boolean responseDone;
  private boolean nativeError;
  private int responseSearchStart;
  private int responseValuePosition = -1;

  public OllamaStreamDecoder(Set<String> knownTools, boolean jsonProtocol) {
    this.knownTools = Set.copyOf(knownTools);
    this.jsonProtocol = jsonProtocol;
  }

  public List<StreamEvent> feed(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes");
    if (terminal) return List.of();
    var progressive = new ArrayList<StreamEvent>();
    lines.feed(bytes, line -> {
      framedWire.append(line).append('\n');
      if (jsonProtocol) {
        appendContent(line);
        progressive.addAll(progressiveResponse());
      } else {
        List<StreamEvent> decoded =
            OllamaStreamParser.parseNdjson(line + '\n', knownTools, false);
        nativeError |= decoded.stream().anyMatch(StreamEvent.Error.class::isInstance);
        progressive.addAll(decoded);
      }
      if (isDone(line)) terminal = true;
    });
    var result = new ArrayList<>(progressive);
    if (jsonProtocol) result.addAll(drain(false));
    return List.copyOf(result);
  }

  /** Completes a successful response whose server closed without a done frame. */
  public List<StreamEvent> end() {
    if (terminal) return List.of();
    terminal = true;
    if (!jsonProtocol) {
      return nativeError ? List.of()
          : List.of(new StreamEvent.Finished(StopReason.UNSPECIFIED));
    }
    var result = new ArrayList<>(drain(true));
    if (result.stream().noneMatch(StreamEvent.Finished.class::isInstance)
        && delivered.keySet().stream().noneMatch(StreamEvent.Finished.class::isInstance)
        && delivered.keySet().stream().noneMatch(StreamEvent.Error.class::isInstance)) {
      var finished = new StreamEvent.Finished(StopReason.UNSPECIFIED);
      markDelivered(finished);
      result.add(finished);
    }
    return List.copyOf(result);
  }

  private void appendContent(String line) {
    try {
      JsonNode value = JSON.readTree(line).path("message").path("content");
      if (value.isTextual()) content.append(value.textValue());
    } catch (JsonProcessingException ignored) {
      // The normal parser below turns malformed provider JSON into StreamError.
    }
  }

  private boolean isDone(String line) {
    try {
      return JSON.readTree(line).path("done").asBoolean(false);
    } catch (JsonProcessingException ignored) {
      return false;
    }
  }

  private List<StreamEvent> progressiveResponse() {
    if (responseDone || responseRejected) return List.of();
    String hold = content.toString();
    if (!responseActive) {
      KeyValue tool = firstStringValue(hold, TOOL_KEYS, 0);
      if (tool == null) return List.of();
      if (!"response".equals(tool.value())) {
        responseRejected = true;
        return List.of();
      }
      responseActive = true;
      responseSearchStart = tool.end();
    }
    if (responseValuePosition < 0) {
      responseValuePosition = firstValueStart(hold, RESPONSE_KEYS, responseSearchStart);
      if (responseValuePosition < 0) return List.of();
    }
    StringBuilder decoded = new StringBuilder();
    int index = responseValuePosition;
    while (index < hold.length()) {
      char value = hold.charAt(index);
      if (value == '"') {
        responseDone = true;
        responseValuePosition = index + 1;
        break;
      }
      if (value != '\\') {
        decoded.append(value);
        index++;
        continue;
      }
      if (index + 1 >= hold.length()) break;
      char escaped = hold.charAt(index + 1);
      if (escaped == 'u') {
        if (index + 6 > hold.length()) break;
        try {
          decoded.append((char) Integer.parseInt(hold.substring(index + 2, index + 6), 16));
          index += 6;
          continue;
        } catch (NumberFormatException ignored) {
          decoded.append('u');
          index += 2;
          continue;
        }
      }
      decoded.append(switch (escaped) {
        case 'n' -> '\n';
        case 't' -> '\t';
        case 'r' -> '\r';
        case 'b' -> '\b';
        case 'f' -> '\f';
        default -> escaped;
      });
      index += 2;
    }
    responseValuePosition = index;
    if (decoded.isEmpty()) return List.of();
    var event = new StreamEvent.TextDelta(decoded.toString());
    markDelivered(event);
    return List.of(event);
  }

  private List<StreamEvent> drain(boolean forceTerminal) {
    List<StreamEvent> parsed = OllamaStreamParser.parseNdjson(
        framedWire.toString(), knownTools, jsonProtocol);
    var available = new ArrayList<StreamEvent>();
    for (StreamEvent event : parsed) {
      if (event instanceof StreamEvent.Finished && !terminal && !forceTerminal) continue;
      if (event instanceof StreamEvent.TextDelta) {
        if (responseActive) continue;
        if (jsonProtocol && !terminal && !forceTerminal) continue;
      }
      available.add(event);
    }
    return unseen(available);
  }

  private List<StreamEvent> unseen(List<StreamEvent> current) {
    Map<StreamEvent, Integer> remaining = new HashMap<>(delivered);
    var result = new ArrayList<StreamEvent>();
    for (StreamEvent event : current) {
      int count = remaining.getOrDefault(event, 0);
      if (count > 0) {
        remaining.put(event, count - 1);
      } else {
        result.add(event);
        markDelivered(event);
      }
    }
    return List.copyOf(result);
  }

  private void markDelivered(StreamEvent event) {
    delivered.merge(event, 1, Integer::sum);
  }

  private static KeyValue firstStringValue(String source, List<String> keys, int start) {
    KeyValue best = null;
    for (String key : keys) {
      int keyPosition = source.indexOf('"' + key + '"', start);
      if (keyPosition < 0) continue;
      int colon = skipWhitespace(source, keyPosition + key.length() + 2);
      if (colon >= source.length() || source.charAt(colon) != ':') continue;
      int quote = skipWhitespace(source, colon + 1);
      if (quote >= source.length() || source.charAt(quote) != '"') continue;
      int end = closingQuote(source, quote + 1);
      if (end < 0) continue;
      var candidate = new KeyValue(source.substring(quote + 1, end), quote + 1, end + 1);
      if (best == null || keyPosition < best.keyPosition()) {
        best = new KeyValue(candidate.value(), candidate.start(), candidate.end(), keyPosition);
      }
    }
    return best;
  }

  private static int firstValueStart(String source, List<String> keys, int start) {
    int bestKey = Integer.MAX_VALUE;
    int bestValue = -1;
    for (String key : keys) {
      int keyPosition = source.indexOf('"' + key + '"', start);
      if (keyPosition < 0) continue;
      int colon = skipWhitespace(source, keyPosition + key.length() + 2);
      if (colon >= source.length() || source.charAt(colon) != ':') continue;
      int quote = skipWhitespace(source, colon + 1);
      if (quote >= source.length() || source.charAt(quote) != '"') continue;
      if (keyPosition < bestKey) {
        bestKey = keyPosition;
        bestValue = quote + 1;
      }
    }
    return bestValue;
  }

  private static int skipWhitespace(String source, int start) {
    int result = start;
    while (result < source.length() && Character.isWhitespace(source.charAt(result))) result++;
    return result;
  }

  private static int closingQuote(String source, int start) {
    boolean escaped = false;
    for (int index = start; index < source.length(); index++) {
      char value = source.charAt(index);
      if (!escaped && value == '"') return index;
      if (!escaped && value == '\\') escaped = true;
      else escaped = false;
    }
    return -1;
  }

  private record KeyValue(String value, int start, int end, int keyPosition) {
    private KeyValue(String value, int start, int end) {
      this(value, start, end, start);
    }
  }
}
