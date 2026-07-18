package com.github.skanga.ajent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.domain.CancellationSignal;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Deterministic, renderer-independent trace of pure reducer transitions. */
public final class CanonicalReducerTrace {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  public record Entry(String beforeHash, String message, List<String> effects,
                      String afterHash) {
    public Entry {
      beforeHash = Objects.requireNonNull(beforeHash, "beforeHash");
      message = Objects.requireNonNull(message, "message");
      effects = List.copyOf(effects);
      afterHash = Objects.requireNonNull(afterHash, "afterHash");
    }
  }

  public record Result(AgentState state, List<Entry> entries) {
    public Result {
      state = Objects.requireNonNull(state, "state");
      entries = List.copyOf(entries);
    }
  }

  private CanonicalReducerTrace() {}

  /** Applies the scripted messages in order and captures every pure reducer boundary. */
  public static Result capture(AgentReducer reducer, AgentState initial,
                               List<? extends RuntimeMessage> messages) {
    Objects.requireNonNull(reducer, "reducer");
    Objects.requireNonNull(initial, "initial");
    Objects.requireNonNull(messages, "messages");
    AgentState state = initial;
    var entries = new ArrayList<Entry>(messages.size());
    for (RuntimeMessage message : messages) {
      Objects.requireNonNull(message, "message");
      String before = hash(state);
      AgentReducer.Step step = reducer.update(state, message);
      entries.add(new Entry(before, canonicalJson(message),
          step.effects().stream().map(CanonicalReducerTrace::canonicalJson).toList(),
          hash(step.state())));
      state = step.state();
    }
    return new Result(state, entries);
  }

  /** SHA-256 of the complete canonical semantic projection of a reducer state. */
  public static String hash(AgentState state) {
    return sha256(canonicalJson(Objects.requireNonNull(state, "state")));
  }

  /** One canonical JSON object per reducer step, suitable for raw fixture comparison. */
  public static String jsonLines(Result result) {
    Objects.requireNonNull(result, "result");
    var output = new StringBuilder();
    for (Entry entry : result.entries()) {
      try {
        ObjectNode line = NODES.objectNode();
        line.put("beforeHash", entry.beforeHash());
        line.set("message", JSON.readTree(entry.message()));
        ArrayNode effects = line.putArray("effects");
        for (String effect : entry.effects()) effects.add(JSON.readTree(effect));
        line.put("afterHash", entry.afterHash());
        output.append(JSON.writeValueAsString(line)).append('\n');
      } catch (Exception exception) {
        throw new IllegalArgumentException("cannot render reducer trace", exception);
      }
    }
    return output.toString();
  }

  /** Stable digest for reviewing or pinning a complete JSON-lines trace. */
  public static String traceHash(Result result) {
    return sha256(jsonLines(result));
  }

  /** Stable JSON with explicit variant names, sorted maps/sets, and no object identity. */
  public static String canonicalJson(Object value) {
    try {
      return JSON.writeValueAsString(node(value));
    } catch (Exception exception) {
      throw new IllegalArgumentException("cannot canonicalize reducer trace value", exception);
    }
  }

  private static JsonNode node(Object value) {
    if (value == null) return NODES.nullNode();
    if (value instanceof String string) return NODES.textNode(string);
    if (value instanceof Character character) return NODES.textNode(character.toString());
    if (value instanceof Boolean bool) return NODES.booleanNode(bool);
    if (value instanceof Byte number) return NODES.numberNode(number);
    if (value instanceof Short number) return NODES.numberNode(number);
    if (value instanceof Integer number) return NODES.numberNode(number);
    if (value instanceof Long number) return NODES.numberNode(number);
    if (value instanceof Float number) return NODES.numberNode(number);
    if (value instanceof Double number) return NODES.numberNode(number);
    if (value instanceof Enum<?> enumeration) return NODES.textNode(enumeration.name());
    if (value instanceof Instant instant) return NODES.textNode(instant.toString());
    if (value instanceof Duration duration) return NODES.numberNode(duration.toNanos());
    if (value instanceof byte[] bytes)
      return NODES.textNode(Base64.getEncoder().encodeToString(bytes));
    if (value instanceof Optional<?> optional)
      return optional.map(CanonicalReducerTrace::node).orElseGet(NODES::nullNode);
    if (value instanceof CancellationSignal cancellation) {
      var result = NODES.objectNode();
      result.put("$type", "CancellationSignal");
      result.put("cancelled", cancellation.isCancelled());
      return result;
    }
    if (value instanceof Map<?, ?> map) return mapNode(map);
    if (value instanceof Collection<?> collection) return collectionNode(collection);
    Class<?> type = value.getClass();
    if (type.isRecord()) return recordNode(value, type);
    throw new IllegalArgumentException("unsupported canonical trace value: " + type.getName());
  }

  private static ObjectNode mapNode(Map<?, ?> map) {
    var result = NODES.objectNode();
    map.entrySet().stream()
        .sorted(Comparator.comparing(entry -> Objects.toString(entry.getKey())))
        .forEach(entry -> result.set(Objects.toString(entry.getKey()), node(entry.getValue())));
    return result;
  }

  private static ArrayNode collectionNode(Collection<?> collection) {
    var values = collection.stream().map(CanonicalReducerTrace::node).toList();
    if (collection instanceof java.util.Set<?>) {
      values = values.stream().sorted(Comparator.comparing(JsonNode::toString)).toList();
    }
    var result = NODES.arrayNode();
    values.forEach(result::add);
    return result;
  }

  private static ObjectNode recordNode(Object value, Class<?> type) {
    var result = NODES.objectNode();
    result.put("$type", variantName(type));
    RecordComponent[] components = type.getRecordComponents();
    java.util.Arrays.sort(components, Comparator.comparing(RecordComponent::getName));
    for (RecordComponent component : components) {
      try {
        result.set(component.getName(), node(component.getAccessor().invoke(value)));
      } catch (IllegalAccessException | InvocationTargetException exception) {
        throw new IllegalArgumentException("cannot read " + type.getName() + "."
            + component.getName(), exception);
      }
    }
    return result;
  }

  private static String variantName(Class<?> type) {
    Class<?> enclosing = type.getEnclosingClass();
    return enclosing == null ? type.getSimpleName()
        : variantName(enclosing) + "." + type.getSimpleName();
  }

  private static String sha256(String value) {
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }
}
