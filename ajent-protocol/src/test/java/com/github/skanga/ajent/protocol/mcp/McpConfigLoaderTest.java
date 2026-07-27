package com.github.skanga.ajent.protocol.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class McpConfigLoaderTest {
  @Test void resolvesExplicitThenProjectThenUserAndGatesOnlyProject(@TempDir Path root)
      throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Path home = Files.createDirectories(root.resolve("home"));
    Path project = Files.createDirectories(workspace.resolve(".ajent")).resolve("mcp.json");
    Path user = Files.createDirectories(home.resolve(".ajent")).resolve("mcp.json");
    Path explicit = root.resolve("explicit.json");
    Files.writeString(project, config("project", "project-command"));
    Files.writeString(user, config("user", "user-command"));
    Files.writeString(explicit, config("explicit", "explicit-command"));

    McpConfigLoader.LoadResult selected = McpConfigLoader.load(workspace, home,
        Map.of("AJENT_MCP_CONFIG", explicit.toString()));
    assertThat(selected.path()).contains(explicit.toAbsolutePath());
    assertThat(selected.projectLocal()).isFalse();
    assertThat(selected.permitted()).isTrue();
    assertThat(selected.servers()).singleElement().satisfies(server ->
        assertThat(server.name()).isEqualTo("explicit"));

    McpConfigLoader.LoadResult blocked = McpConfigLoader.load(workspace, home, Map.of());
    assertThat(blocked.path()).contains(project.toAbsolutePath());
    assertThat(blocked.projectLocal()).isTrue();
    assertThat(blocked.permitted()).isFalse();
    assertThat(blocked.servers()).isEmpty();
    assertThat(blocked.diagnostics()).singleElement().asString()
        .contains("AJENT_MCP_ALLOW_PROJECT=1");

    McpConfigLoader.LoadResult allowed = McpConfigLoader.load(workspace, home,
        Map.of("AJENT_MCP_ALLOW_PROJECT", "Yes"));
    assertThat(allowed.permitted()).isTrue();
    assertThat(allowed.servers()).singleElement().satisfies(server ->
        assertThat(server.name()).isEqualTo("project"));

    Files.delete(project);
    McpConfigLoader.LoadResult fallback = McpConfigLoader.load(workspace, home, Map.of());
    assertThat(fallback.path()).contains(user.toAbsolutePath());
    assertThat(fallback.projectLocal()).isFalse();
    assertThat(fallback.servers()).singleElement().satisfies(server ->
        assertThat(server.name()).isEqualTo("user"));

    McpConfigLoader.LoadResult missingExplicit = McpConfigLoader.load(workspace, home,
        Map.of("AJENT_MCP_CONFIG", root.resolve("absent.json").toString()));
    assertThat(missingExplicit.path()).isEmpty();
    assertThat(missingExplicit.servers()).isEmpty();
  }

  @Test void parsesStdioHttpLegacyCoercionAndTimeouts(@TempDir Path root) throws Exception {
    Path config = root.resolve("mcp.json");
    Files.writeString(config, """
        {"servers":{
          "stdio":{"command":"server","args":["--port",8080,true],
                   "env":{"TOKEN":"secret","COUNT":3}},
          "remote":{"command":"ignored","url":"http://127.0.0.1:9999/mcp",
                    "headers":{"Authorization":"Bearer x","X-Retry":2}},
          "typed":{"type":"streamable-http","headers":{}},
          "invalid":{}
        }}
        """);
    McpConfigLoader.LoadResult loaded = McpConfigLoader.load(root, root,
        Map.of("AJENT_MCP_CONFIG", config.toString(),
            "AJENT_MCP_TIMEOUT_MS", "2500",
            "AJENT_MCP_CONNECT_TIMEOUT_MS", "9000"));

    assertThat(loaded.callTimeout()).isEqualTo(Duration.ofMillis(2500));
    assertThat(loaded.connectTimeout()).isEqualTo(Duration.ofMillis(9000));
    assertThat(loaded.servers()).hasSize(3);
    assertThat(loaded.servers().get(0)).isInstanceOfSatisfying(
        McpConfigLoader.Server.Stdio.class, server -> {
          assertThat(server.name()).isEqualTo("stdio");
          assertThat(server.command()).isEqualTo("server");
          assertThat(server.arguments()).containsExactly("--port", "8080", "true");
          assertThat(server.environment()).containsEntry("TOKEN", "secret")
              .containsEntry("COUNT", "3");
        });
    assertThat(loaded.servers().get(1)).isInstanceOfSatisfying(
        McpConfigLoader.Server.Http.class, server -> {
          assertThat(server.name()).isEqualTo("remote");
          assertThat(server.url()).isEqualTo("http://127.0.0.1:9999/mcp");
          assertThat(server.headers()).containsEntry("Authorization", "Bearer x")
              .containsEntry("X-Retry", "2");
        });
    assertThat(loaded.servers().get(2)).isInstanceOfSatisfying(
        McpConfigLoader.Server.Http.class, server -> assertThat(server.url()).isEmpty());
    assertThat(loaded.diagnostics()).singleElement().asString()
        .contains("invalid").contains("no command");
  }

  @Test void malformedDocumentsAndTimeoutsDegradeToDiagnostics(@TempDir Path root)
      throws Exception {
    Path malformed = root.resolve("malformed.json");
    Files.writeString(malformed, "{");
    McpConfigLoader.LoadResult bad = McpConfigLoader.load(root, root,
        Map.of("AJENT_MCP_CONFIG", malformed.toString(),
            "AJENT_MCP_TIMEOUT_MS", "nope",
            "AJENT_MCP_CONNECT_TIMEOUT_MS", "-1"));
    assertThat(bad.servers()).isEmpty();
    assertThat(bad.callTimeout()).isEqualTo(Duration.ofSeconds(60));
    assertThat(bad.connectTimeout()).isEqualTo(Duration.ofSeconds(15));
    assertThat(bad.diagnostics()).anyMatch(message -> message.contains("failed to parse"));

    Path empty = root.resolve("empty.json");
    Files.writeString(empty, "{}");
    McpConfigLoader.LoadResult noServers = McpConfigLoader.load(root, root,
        Map.of("AJENT_MCP_CONFIG", empty.toString()));
    assertThat(noServers.servers()).isEmpty();
    assertThat(noServers.diagnostics()).isEmpty();
  }

  private static String config(String name, String command) {
    return "{\"mcpServers\":{\"" + name + "\":{\"command\":\""
        + command + "\"}}}";
  }
}
