package com.github.skanga.ajent.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record ToolSpecification(
    String name, String description, JsonNode inputSchema, boolean eagerInputStreaming) {
  public ToolSpecification {
    name = Objects.requireNonNull(name, "name");
    description = Objects.requireNonNull(description, "description");
    inputSchema = Objects.requireNonNull(inputSchema, "inputSchema").deepCopy();
  }

  @Override
  public JsonNode inputSchema() {
    return inputSchema.deepCopy();
  }
}
