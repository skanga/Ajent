package com.github.skanga.ajent.cli;

import com.github.skanga.ajent.protocol.mcp.McpConfigLoader;
import com.github.skanga.ajent.protocol.mcp.McpConnectionPool;
import com.github.skanga.ajent.protocol.mcp.McpExternalToolRuntime;
import com.github.skanga.ajent.protocol.mcp.McpRegistry;
import com.github.skanga.ajent.tools.runtime.ExternalToolRuntime;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Owns configured MCP client sessions and their live agent-tool projection. */
final class McpRuntime implements AutoCloseable {
  private final McpRegistry registry;
  private final ExternalToolRuntime tools;

  private McpRuntime(McpRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
    tools = new McpExternalToolRuntime(registry);
  }

  static McpRuntime connect(
      Path workspace, Path home, Map<String, String> environment, PrintStream error) {
    var configuration = McpConfigLoader.load(workspace, home, environment);
    McpConnectionPool.ConnectResult connected = McpConnectionPool.connect(
        configuration, AjentCli.VERSION, line -> error.print("mcp: " + line + "\n"));
    connected.diagnostics().forEach(line -> error.print("ajent: " + line + "\n"));
    return new McpRuntime(connected.registry());
  }

  ExternalToolRuntime tools() { return tools; }
  McpRegistry registry() { return registry; }

  @Override public void close() { registry.close(); }
}
