package com.github.skanga.ajent.cli;

import com.github.skanga.ajent.core.persistence.Settings;
import com.github.skanga.ajent.core.persistence.SettingsStore;
import com.github.skanga.ajent.provider.auth.Credential;
import com.github.skanga.ajent.provider.EnvironmentHttpClient;
import com.github.skanga.ajent.provider.auth.CredentialResolver;
import com.github.skanga.ajent.provider.auth.CredentialStore;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import com.github.skanga.ajent.provider.openai.ProviderAuthResolver;
import com.github.skanga.ajent.protocol.mcp.McpJsonRpcServer;
import com.github.skanga.ajent.runtime.DispatcherToolPort;
import com.github.skanga.ajent.runtime.LiveProviderFactory;
import com.github.skanga.ajent.runtime.ProviderBackedSubagentRunner;
import com.github.skanga.ajent.tools.catalog.NativeToolWireCatalog;
import com.github.skanga.ajent.tools.runtime.ToolRuntimeFactory;
import com.github.skanga.ajent.tools.process.ProcessSandbox;
import com.github.skanga.ajent.tools.web.JdkWebTransport;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Standalone stdio MCP command backed by Ajent's production local tool runtime. */
final class McpServeCommand {
  private static final int USAGE_ERROR = 2;
  private static final int SOFTWARE_ERROR = 70;
  private static final List<String> NATIVE_STANDALONE_ORDER = List.of(
      "todo", "read", "list_dir", "edit", "grep", "write", "bash", "glob",
      "web_fetch", "remember", "web_search", "find_definition", "diagnostics",
      "git_status", "git_diff", "git_log", "git_commit", "task", "forget",
      "wipe_memory", "skill", "search_docs");
  private final Path currentDirectory;
  private final Path home;
  private final Map<String, String> environment;
  private final CredentialStore credentials;
  private final HttpClient client;

  McpServeCommand(Path currentDirectory, Path home, Map<String, String> environment) {
    this(currentDirectory, home, environment,
        new CredentialStore(home.resolve(".agentty/credentials.json"), "mcp-test"),
        EnvironmentHttpClient.createProvider(environment));
  }

  McpServeCommand(Path currentDirectory, Path home, Map<String, String> environment,
                  CredentialStore credentials, HttpClient client) {
    this.currentDirectory = Objects.requireNonNull(currentDirectory, "currentDirectory")
        .toAbsolutePath().normalize();
    this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
    this.environment = Map.copyOf(environment);
    this.credentials = Objects.requireNonNull(credentials, "credentials");
    this.client = Objects.requireNonNull(client, "client");
  }

  static McpServeCommand systemDefault() {
    String configuredHome = System.getProperty("user.home", ".");
    return new McpServeCommand(Path.of(""), Path.of(configuredHome), System.getenv(),
        CredentialStore.systemDefault(), EnvironmentHttpClient.createProvider(System.getenv()));
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
    ProcessSandbox.Initialization processSandbox;
    try {
      processSandbox = ProcessSandbox.initialize(arguments.sandbox(), workspace);
    } catch (IllegalArgumentException exception) {
      error.print("ajent: " + exception.getMessage() + "\n");
      return USAGE_ERROR;
    }
    if (!processSandbox.valid()) {
      error.print("ajent: --sandbox=on but no backend available. "
          + processSandbox.description() + "\n");
      return USAGE_ERROR;
    }
    error.print("ajent: " + processSandbox.description() + "\n");
    Path docsRoot = resolveDocs(workspace);
    Settings settings = new SettingsStore(home.resolve(".agentty")).load();
    String provider = arguments.provider().isBlank() ? settings.provider() : arguments.provider();
    if (provider.isBlank()) provider = "anthropic";
    String model = arguments.model().isBlank() ? settings.modelId().value() : arguments.model();
    if (model.isBlank()) model = "claude-opus-4-5";
    CredentialResolver.Resolution anthropic = CredentialResolver.resolve(
        arguments.key(), environment, credentials.load(), System.currentTimeMillis());
    ProviderAuth auth = ProviderAuthResolver.resolve(provider,
        providerAuth(anthropic.credential()), arguments.key(),
        settings.providerKeys().getOrDefault(provider, ""), environment);
    try (var mcp = McpRuntime.connect(workspace, home, environment, error)) {
      var subagents = new ProviderBackedSubagentRunner(client);
      var configuration = new ToolRuntimeFactory.Configuration(
          workspace, workspace, home, docsRoot, new JdkWebTransport(), null, subagents,
          processSandbox.runner(), mcp.tools(), environment);
      var tools = ToolRuntimeFactory.compose(configuration);
      var dispatcher = tools.dispatcher();
      subagents.bind(new DispatcherToolPort(dispatcher));
      var providerConfiguration = new LiveProviderFactory.Configuration(
          provider, model, auth, settings.effort(), tools.systemPrompt(), 0, environment,
          tools::additionalTools);
      subagents.install(() -> providerConfiguration);
      Map<String, com.github.skanga.ajent.provider.ToolSpecification> nativeTools =
          NativeToolWireCatalog.all().stream().collect(java.util.stream.Collectors.toMap(
              com.github.skanga.ajent.provider.ToolSpecification::name,
              java.util.function.Function.identity()));
      var published = java.util.stream.Stream.concat(
          NATIVE_STANDALONE_ORDER.stream().map(nativeTools::get).map(specification ->
              new McpJsonRpcServer.PublishedTool(specification,
                  NativeToolWireCatalog.wireEffects(specification.name()))),
          tools.additionalTools().stream().map(specification ->
              new McpJsonRpcServer.PublishedTool(specification,
                  tools.effects(specification.name()).orElseThrow())))
          .toList();
      var server = new McpJsonRpcServer(published, dispatcher::execute, AjentCli.VERSION);
      server.serve(input, new PrintWriter(output, true, StandardCharsets.UTF_8));
      return 0;
    } catch (IOException exception) {
      String detail = exception.getMessage();
      error.print("ajent: mcp-serve failed: "
          + (detail == null ? exception.getClass().getSimpleName() : detail) + "\n");
      return SOFTWARE_ERROR;
    }
  }

  private static ProviderAuth providerAuth(Credential credential) {
    return switch (credential) {
      case Credential.None ignored -> new ProviderAuth.Empty();
      case Credential.ApiKey key -> new ProviderAuth.ApiKey(key.key());
      case Credential.OAuth oauth -> new ProviderAuth.Bearer(oauth.accessToken());
    };
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
