package com.github.skanga.ajent.protocol.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** Typed MCP protocol/transport failure. */
public final class McpTransportException extends RuntimeException {
  private final int code;
  private final JsonNode data;

  public McpTransportException(int code, String message) {
    this(code, message, JsonNodeFactory.instance.nullNode(), null);
  }

  public McpTransportException(int code, String message, JsonNode data) {
    this(code, message, data, null);
  }

  public McpTransportException(int code, String message, Throwable cause) {
    this(code, message, JsonNodeFactory.instance.nullNode(), cause);
  }

  private McpTransportException(int code, String message, JsonNode data, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.data = data == null ? JsonNodeFactory.instance.nullNode() : data.deepCopy();
  }

  public int code() { return code; }
  public JsonNode data() { return data.deepCopy(); }
}
