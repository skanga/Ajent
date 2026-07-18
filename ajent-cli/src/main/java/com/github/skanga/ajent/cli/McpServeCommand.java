package com.github.skanga.ajent.cli;

import com.github.skanga.ajent.protocol.mcp.McpJsonRpcServer;
import com.github.skanga.ajent.tools.catalog.NativeToolWireCatalog;
import com.github.skanga.ajent.tools.runtime.ToolRuntimeFactory;
import com.github.skanga.ajent.tools.web.JdkWebTransport;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Standalone stdio MCP command backed by Ajent's production local tool runtime. */
final class McpServeCommand {
  private static final int USAGE_ERROR = 2;
  private static final int SOFTWARE_ERROR = 70;
  private final Path currentDirectory;
  private final Path home;
  private final Map<String, String> environment;

  McpServeCommand(Path currentDirectory, Path home, Map<String, String> environment) {
    this.currentDirectory = Objects.requireNonNull(currentDirectory, "currentDirectory")
        .toAbsolutePath().normalize();
    this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
    this.environment = Map.copyOf(environment);
  }

  static McpServeCommand systemDefault() {
    String configuredHome = System.getProperty("user.home", ".");
    return new McpServeCommand(Path.of(""), Path.of(configuredHome), System.getenv());
  }

  int run(CliArguments arguments, BufferedReader input, PrintStream output, PrintStream error) {
    Path workspace;
    try {
      workspace = arguments.workspace().isBlank()
          ? currentDirectory : resolve(arguments.workspace());
    } catch (InvalidPathException exception) {
      workspace = null;
    }
    if (workspace == null || !Files.isDirectory(workspace)) {
      error.print("ajent: --workspace path is not a directory: "
          + arguments.workspace() + "\n");
      return USAGE_ERROR;
    }
    Path docsRoot = resolveDocs(workspace);
    var configuration = new ToolRuntimeFactory.Configuration(
        workspace, workspace, home, docsRoot, new JdkWebTransport(), null, null);
    var dispatcher = ToolRuntimeFactory.create(configuration);
    var published = NativeToolWireCatalog.all().stream()
        .map(specification -> new McpJsonRpcServer.PublishedTool(
            specification, NativeToolWireCatalog.wireEffects(specification.name())))
        .toList();
    var server = new McpJsonRpcServer(published, dispatcher::execute, AjentCli.VERSION);
    try {
      server.serve(input, new PrintWriter(output, true, StandardCharsets.UTF_8));
      return 0;
    } catch (IOException exception) {
      String detail = exception.getMessage();
      error.print("ajent: mcp-serve failed: "
          + (detail == null ? exception.getClass().getSimpleName() : detail) + "\n");
      return SOFTWARE_ERROR;
    }
  }

  private Path resolveDocs(Path workspace) {
    String configured = environment.getOrDefault("AGENTTY_DOCS_DIR", "");
    if (!configured.isBlank()) {
      try {
        return resolve(configured);
      } catch (InvalidPathException exception) {
        return null;
      }
    }
    Path docs = workspace.resolve("docs");
    if (Files.isDirectory(docs)) {
      return docs;
    }
    Path knowledge = workspace.resolve(".agentty/knowledge");
    return Files.isDirectory(knowledge) ? knowledge : null;
  }

  private Path resolve(String value) {
    Path path = Path.of(value);
    return (path.isAbsolute() ? path : currentDirectory.resolve(path)).toAbsolutePath().normalize();
  }
}
