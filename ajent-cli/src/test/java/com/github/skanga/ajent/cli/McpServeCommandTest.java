package com.github.skanga.ajent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpServeCommandTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void servesExactCatalogAndExecutesToolsInsideSelectedWorkspace(@TempDir Path root)
      throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Path home = Files.createDirectories(root.resolve("home"));
    Files.writeString(workspace.resolve("ParityMap.cpp"),
        "int parityAnswer() {\n  return 42;\n}\n");
    String input = String.join("\n",
        frame(1, "initialize", "{\"protocolVersion\":\"2025-11-25\"}"),
        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}",
        frame(2, "tools/list", "{}"),
        frame(3, "tools/call", "{\"name\":\"write\",\"arguments\":{"
            + "\"path\":\"served.txt\",\"content\":\"through MCP\"}}"),
        frame(4, "tools/call", "{\"name\":\"repo_map\",\"arguments\":{}}"),
        frame(5, "tools/call", "{\"name\":\"task\",\"arguments\":{}}")) + "\n";
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    var parsed = CliArguments.parse(new String[] {
        "mcp-serve", "--workspace", workspace.toString(), "--sandbox", "off"});

    int exit = new McpServeCommand(root, home, Map.of()).run(parsed,
        new BufferedReader(new StringReader(input)), print(stdout), print(stderr));

    assertThat(exit).isZero();
    assertThat(stderr.toString(StandardCharsets.UTF_8)).isEqualTo("ajent: sandbox: off\n");
    List<com.fasterxml.jackson.databind.JsonNode> messages = new ArrayList<>();
    for (String line : stdout.toString(StandardCharsets.UTF_8).lines().toList()) {
      messages.add(JSON.readTree(line));
    }
    assertThat(messages).hasSize(5);
    assertThat(messages.get(0).path("result").path("serverInfo").path("name").asText())
        .isEqualTo("ajent");
    assertThat(messages.get(1).path("result").path("tools"))
        .extracting(node -> node.path("name").asText())
        .containsExactly("read", "edit", "write", "bash", "grep", "glob", "list_dir",
            "repo_map", "todo", "web_fetch", "web_search", "find_definition",
            "diagnostics", "git_status", "git_diff", "git_log", "git_commit", "remember",
            "forget", "wipe_memory", "task", "skill", "search_docs");
    assertThat(messages.get(2).path("result").path("isError").asBoolean()).isFalse();
    assertThat(messages.get(3).path("result").path("content").path(0).path("text").asText())
        .contains("Repository map", "ParityMap.cpp", "parityAnswer");
    assertThat(messages.get(4).path("result").path("content").path(0).path("text").asText())
        .isEqualTo("[unknown] task: subagent unavailable "
            + "(not configured, or max nesting depth reached).");
    assertThat(Files.readString(workspace.resolve("served.txt"))).isEqualTo("through MCP");
  }

  @Test
  void rejectsAnUnknownSandboxModeBeforeProtocolStartup(@TempDir Path root) throws Exception {
    Path home = Files.createDirectories(root.resolve("home"));
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    var parsed = CliArguments.parse(new String[] {"mcp-serve", "--sandbox", "sideways"});

    int exit = new McpServeCommand(root, home, Map.of()).run(parsed,
        new BufferedReader(new StringReader("")), print(stdout), print(stderr));

    assertThat(exit).isEqualTo(2);
    assertThat(stdout.toString(StandardCharsets.UTF_8)).isEmpty();
    assertThat(stderr.toString(StandardCharsets.UTF_8)).isEqualTo(
        "ajent: --sandbox must be auto, on, or off (got 'sideways')\n");
  }

  @Test
  void rejectsAnExplicitNonDirectoryWorkspaceBeforeWritingProtocolOutput(@TempDir Path root)
      throws Exception {
    Path home = Files.createDirectories(root.resolve("home"));
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    var parsed = CliArguments.parse(new String[] {"mcp-serve", "--workspace", "missing"});

    int exit = new McpServeCommand(root, home, Map.of()).run(parsed,
        new BufferedReader(new StringReader("")), print(stdout), print(stderr));

    assertThat(exit).isEqualTo(2);
    assertThat(stdout.toString(StandardCharsets.UTF_8)).isEmpty();
    assertThat(stderr.toString(StandardCharsets.UTF_8))
        .isEqualTo("ajent: --workspace path is not a directory: missing\n");
  }

  private static String frame(int id, String method, String parameters) {
    return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method
        + "\",\"params\":" + parameters + '}';
  }

  private static PrintStream print(ByteArrayOutputStream bytes) {
    return new PrintStream(bytes, true, StandardCharsets.UTF_8);
  }
}
