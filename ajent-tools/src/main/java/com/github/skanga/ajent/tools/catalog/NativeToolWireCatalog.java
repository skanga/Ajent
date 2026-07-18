package com.github.skanga.ajent.tools.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.provider.ToolSpecification;
import com.github.skanga.ajent.tools.policy.Effect;
import com.github.skanga.ajent.tools.policy.EffectSet;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Exact MCP descriptions and input schemas published by the pinned AgenTTY implementation. */
public final class NativeToolWireCatalog {
  private static final String RESOURCE = "native-tools.json";
  private static final Catalog CATALOG = load();

  private NativeToolWireCatalog() {}

  public static List<ToolSpecification> all() {
    return CATALOG.all();
  }

  public static Optional<ToolSpecification> byName(String name) {
    return Optional.ofNullable(CATALOG.byName().get(name));
  }

  /** Effects used only to project MCP annotations, distinct from runtime permission effects. */
  public static EffectSet wireEffects(String name) {
    var effects = CATALOG.wireEffects().get(name);
    if (effects == null) {
      throw new IllegalArgumentException("unknown native tool: " + name);
    }
    return effects;
  }

  private static Catalog load() {
    ObjectMapper json = new ObjectMapper();
    try (InputStream stream = NativeToolWireCatalog.class.getResourceAsStream(RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("missing native tool catalog resource: " + RESOURCE);
      }
      return parse(json.readTree(stream));
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read native tool catalog", exception);
    }
  }

  static List<ToolSpecification> validate(JsonNode definitions) {
    return parse(definitions).all();
  }

  private static Catalog parse(JsonNode definitions) {
    if (definitions == null || !definitions.isArray()) {
      throw new IllegalStateException("native tool catalog must be a JSON array");
    }
    var specifications = new java.util.ArrayList<ToolSpecification>();
    var indexed = new LinkedHashMap<String, ToolSpecification>();
    var effectsByName = new LinkedHashMap<String, EffectSet>();
    int index = 0;
    for (JsonNode definition : definitions) {
      if (!definition.isObject()) {
        throw malformed(index, "definition must be an object");
      }
      String name = requiredText(definition, "name", index);
      String description = requiredText(definition, "description", index);
      JsonNode inputSchema = definition.path("inputSchema");
      if (!inputSchema.isObject()) {
        throw malformed(index, name + ": inputSchema must be an object");
      }
      ToolSpec metadata = ToolCatalog.byName(name).orElse(null);
      if (metadata == null) {
        throw malformed(index, "unknown native tool: " + name);
      }
      var wireEffects = parseAnnotations(definition.path("annotations"), name, index);
      var specification = new ToolSpecification(
          name, description, inputSchema, metadata.eagerInputStreaming());
      if (indexed.putIfAbsent(name, specification) != null) {
        throw malformed(index, "duplicate native tool: " + name);
      }
      specifications.add(specification);
      effectsByName.put(name, wireEffects);
      index++;
    }
    List<String> actual = specifications.stream().map(ToolSpecification::name).toList();
    List<String> operational = ToolCatalog.all().stream().map(ToolSpec::name).toList();
    if (actual.size() != operational.size()
        || !new java.util.HashSet<>(actual).equals(new java.util.HashSet<>(operational))) {
      throw new IllegalStateException(
          "native and operational tool catalog names differ: " + actual);
    }
    return new Catalog(
        List.copyOf(specifications), Map.copyOf(indexed), Map.copyOf(effectsByName));
  }

  private static String requiredText(JsonNode definition, String field, int index) {
    JsonNode value = definition.path(field);
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw malformed(index, field + " must be non-blank text");
    }
    return value.textValue();
  }

  private static EffectSet parseAnnotations(
      JsonNode annotations, String name, int index) {
    if (!annotations.isObject()) {
      throw malformed(index, name + ": annotations must be an object");
    }
    boolean readOnly = requiredBoolean(annotations, "readOnlyHint", name, index);
    boolean destructive = requiredBoolean(annotations, "destructiveHint", name, index);
    boolean openWorld = requiredBoolean(annotations, "openWorldHint", name, index);
    if (readOnly == destructive) {
      throw malformed(index, name + ": readOnlyHint must be the inverse of destructiveHint");
    }
    var effects = EffectSet.pure();
    if (destructive) {
      effects = effects.union(EffectSet.of(Effect.EXEC));
    }
    if (openWorld) {
      effects = effects.union(EffectSet.of(Effect.NET));
    }
    return effects;
  }

  private static boolean requiredBoolean(
      JsonNode annotations, String field, String name, int index) {
    JsonNode value = annotations.path(field);
    if (!value.isBoolean()) {
      throw malformed(index, name + ": " + field + " must be boolean");
    }
    return value.booleanValue();
  }

  private static IllegalStateException malformed(int index, String detail) {
    return new IllegalStateException("invalid native tool catalog entry " + index + ": " + detail);
  }

  private record Catalog(
      List<ToolSpecification> all,
      Map<String, ToolSpecification> byName,
      Map<String, EffectSet> wireEffects) {}
}
