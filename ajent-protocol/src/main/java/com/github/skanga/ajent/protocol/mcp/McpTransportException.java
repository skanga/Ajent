package com.github.skanga.ajent.protocol.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.Serial;

/** Typed MCP protocol/transport failure. */
@SuppressWarnings("serial")
public final class McpTransportException extends RuntimeException {
  @Serial
  private static final long serialVersionUID = 1L;

  private final int code;
  private final transient JsonNode data;

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
