package com.github.skanga.ajent.protocol.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.provider.ToolSpecification;
import com.github.skanga.ajent.tools.policy.Effect;
import com.github.skanga.ajent.tools.policy.EffectSet;
import com.github.skanga.ajent.tools.runtime.FileChange;
import com.github.skanga.ajent.tools.runtime.ToolError;
import com.github.skanga.ajent.tools.runtime.ToolErrorKind;
import com.github.skanga.ajent.tools.runtime.ToolOutput;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class McpJsonRpcServerTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test void servesNativeToolsWithExactMcpShapesAnnotationsAndResults() throws Exception {
    var readSchema = JSON.readTree("""
        {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}
        """);
    var emptySchema = JSON.readTree("{\"type\":\"object\"}");
    var tools = List.of(
        new McpJsonRpcServer.PublishedTool(
            new ToolSpecification("read", "Read a file", readSchema, false),
            EffectSet.of(Effect.READ_FS)),
        new McpJsonRpcServer.PublishedTool(
            new ToolSpecification("write", "Write a file", emptySchema, true),
            EffectSet.of(Effect.WRITE_FS)),
        new McpJsonRpcServer.PublishedTool(
            new ToolSpecification("web_fetch", "Fetch a URL", emptySchema, false),
            EffectSet.of(Effect.NET)));
    var server = new McpJsonRpcServer(tools, (name, arguments) -> switch (name) {
      case "read" -> new ToolResult.Success(new ToolOutput("contents"));
      case "write" -> new ToolResult.Success(new ToolOutput("", Optional.of(
          new FileChange("file.txt", 3, 1, "old", "new"))));
      default -> new ToolResult.Failure(new ToolError(ToolErrorKind.NETWORK, "offline"));
    }, "test-version");

    JsonNode initialized = result(server, 1, "initialize", """
        {"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"test-client","version":"1"}}
        """);
    assertThat(initialized.path("protocolVersion").textValue()).isEqualTo("2025-11-25");
    assertThat(initialized.path("capabilities").path("tools")).isEmpty();
    assertThat(initialized.path("serverInfo").path("name").textValue()).isEqualTo("ajent");
    assertThat(initialized.path("serverInfo").path("version").textValue())
        .isEqualTo("test-version");
    assertThat(initialized.path("serverInfo").path("title").textValue())
        .isEqualTo("ajent native tools");
    assertThat(initialized.path("serverInfo").has("description")).isFalse();
    assertThat(initialized.path("instructions").textValue())
        .contains("native coding tools served over MCP");
    assertThat(result(server, 2, "ping", "{}")).isEmpty();
    assertThat(server.handleLine(
        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
        .isEmpty();

    JsonNode listed = result(server, 3, "tools/list", "{}").path("tools");
    assertThat(listed).extracting(tool -> tool.path("name").textValue())
        .containsExactly("read", "write", "web_fetch");
    assertThat(listed.get(0).path("description").textValue()).isEqualTo("Read a file");
    assertThat(listed.get(0).path("inputSchema")).isEqualTo(readSchema);
    assertThat(listed.get(0).path("annotations").path("readOnlyHint").booleanValue()).isTrue();
    assertThat(listed.get(0).path("annotations").path("destructiveHint").booleanValue()).isFalse();
    assertThat(listed.get(0).path("annotations").path("openWorldHint").booleanValue()).isFalse();
    assertThat(listed.get(1).path("annotations").path("readOnlyHint").booleanValue()).isFalse();
    assertThat(listed.get(1).path("annotations").path("destructiveHint").booleanValue()).isTrue();
    assertThat(listed.get(2).path("annotations").path("openWorldHint").booleanValue()).isTrue();

    JsonNode read = result(server, 4, "tools/call", """
        {"name":"read","arguments":{"path":"file.txt"}}
        """);
    assertThat(read.path("isError").booleanValue()).isFalse();
    assertThat(read.path("content").get(0).path("type").textValue()).isEqualTo("text");
    assertThat(read.path("content").get(0).path("text").textValue()).isEqualTo("contents");
    JsonNode write = result(server, 5, "tools/call", """
        {"name":"write","arguments":{}}
        """);
    assertThat(write.path("content").get(0).path("text").textValue()).isEqualTo("(no output)");
    assertThat(write.path("structuredContent")).isEqualTo(JSON.readTree(
        "{\"path\":\"file.txt\",\"added\":3,\"removed\":1}"));
    JsonNode failed = result(server, 6, "tools/call", """
        {"name":"web_fetch","arguments":{}}
        """);
    assertThat(failed.path("isError").booleanValue()).isTrue();
    assertThat(failed.path("content").get(0).path("text").textValue())
        .isEqualTo("[network] offline");

    JsonNode unknown = response(server, 7, "tools/call",
        "{\"name\":\"missing\",\"arguments\":{}}");
    assertThat(unknown.path("error").path("code").intValue()).isEqualTo(-32602);
    assertThat(unknown.path("error").path("message").textValue())
        .isEqualTo("unknown tool: missing");
  }

  @Test void mapsJsonRpcEnvelopeAndExecutionFailuresAndServesLines() throws Exception {
    var emptySchema = JSON.readTree("{\"type\":\"object\"}");
    var boom = new McpJsonRpcServer.PublishedTool(
        new ToolSpecification("boom", "", emptySchema, false), EffectSet.of(Effect.EXEC));
    assertThatIllegalArgumentException().isThrownBy(() -> new McpJsonRpcServer(
        List.of(boom, boom), (name, arguments) -> new ToolResult.Success(
            new ToolOutput("unused")), "test"));
    var server = new McpJsonRpcServer(List.of(boom), (name, arguments) -> {
      throw new IllegalStateException();
    }, "test");
    assertThat(parse(server.handleLine("{").getFirst()).path("error").path("code").intValue())
        .isEqualTo(-32700);
    assertThat(parse(server.handleLine("[]").getFirst()).path("error").path("code").intValue())
        .isEqualTo(-32600);
    assertThat(parse(server.handleLine("null").getFirst()).path("error").path("code").intValue())
        .isEqualTo(-32600);
    assertThat(parse(server.handleLine(
        "{\"jsonrpc\":\"1.0\",\"id\":\"bad-version\",\"method\":\"ping\"}")
        .getFirst()).path("id").textValue()).isEqualTo("bad-version");
    assertThat(parse(server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":9}").getFirst())
        .path("error").path("code").intValue()).isEqualTo(-32600);
    assertThat(response(server, 2, "missing", "{}").path("error").path("code").intValue())
        .isEqualTo(-32601);
    assertThat(response(server, 3, "tools/call", "{\"name\":1}")
        .path("error").path("code").intValue()).isEqualTo(-32602);
    assertThat(parse(server.handleLine(
        "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"ping\",\"params\":[]}")
        .getFirst()).path("error").path("code").intValue()).isEqualTo(-32602);
    assertThat(server.handleLine(
        "{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"params\":[]}"))
        .isEmpty();
    assertThat(server.handleLine(
        "{\"jsonrpc\":\"2.0\",\"method\":\"missing\"}"))
        .isEmpty();
    assertThat(server.handleLine(
        "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"boom\"}}"))
        .isEmpty();
    JsonNode thrown = response(server, 11, "tools/call", "{\"name\":\"boom\"}");
    assertThat(thrown.path("error").path("code").intValue()).isEqualTo(-32603);
    assertThat(thrown.path("error").path("message").textValue())
        .isEqualTo("IllegalStateException");
    assertThat(result(server, 12, "initialize", """
        {"protocolVersion":"","capabilities":{},"clientInfo":{"name":"c","version":"1"}}
        """).path("protocolVersion").textValue()).isEqualTo("2025-11-25");
    JsonNode listed = result(server, 13, "tools/list", "{}").path("tools").get(0);
    assertThat(listed.has("description")).isFalse();
    assertThat(listed.path("annotations").path("destructiveHint").booleanValue()).isTrue();

    var input = new java.io.BufferedReader(new java.io.StringReader(
        "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}\n"
            + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"));
    var output = new java.io.StringWriter();
    server.serve(input, new java.io.PrintWriter(output));
    assertThat(output.toString().lines()).hasSize(1);
    assertThat(parse(output.toString().strip()).path("id").intValue()).isEqualTo(4);
  }

  private static JsonNode result(
      McpJsonRpcServer server, int id, String method, String parameters) throws Exception {
    JsonNode response = response(server, id, method, parameters);
    assertThat(response.has("error")).as(response.toString()).isFalse();
    return response.path("result");
  }

  private static JsonNode response(
      McpJsonRpcServer server, int id, String method, String parameters) throws Exception {
    String request = "{\"jsonrpc\":\"2.0\",\"id\":" + id
        + ",\"method\":" + JSON.writeValueAsString(method)
        + ",\"params\":" + parameters.strip() + "}";
    return parse(server.handleLine(request).getLast());
  }

  private static JsonNode parse(String value) {
    try {
      return JSON.readTree(value);
    } catch (java.io.IOException exception) {
      throw new AssertionError(exception);
    }
  }
}
