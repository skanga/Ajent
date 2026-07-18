package com.github.skanga.ajent.cli;

import com.github.skanga.ajent.core.persistence.Settings;
import com.github.skanga.ajent.core.persistence.SettingsStore;
import com.github.skanga.ajent.domain.Profile;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.provider.auth.Credential;
import com.github.skanga.ajent.provider.auth.CredentialResolver;
import com.github.skanga.ajent.provider.auth.CredentialStore;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import com.github.skanga.ajent.provider.openai.Endpoint;
import com.github.skanga.ajent.provider.openai.ProviderAuthResolver;
import com.github.skanga.ajent.protocol.acp.AcpJsonRpcServer;
import com.github.skanga.ajent.runtime.AgentSessionFactory;
import com.github.skanga.ajent.runtime.LiveProviderFactory;
import com.github.skanga.ajent.tools.process.ProcessSandbox;
import com.github.skanga.ajent.tools.runtime.ToolRuntimeFactory;
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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/** Headless ACP stdio composition root. */
final class AcpCommand {
  private static final int USAGE_ERROR = 2;
  private static final int SOFTWARE_ERROR = 70;
  private static final String DEFAULT_MODEL = "claude-opus-4-5";
  private final Path currentDirectory;
  private final Path home;
  private final Map<String, String> environment;
  private final CredentialStore credentials;
  private final HttpClient client;

  AcpCommand(Path currentDirectory, Path home, Map<String, String> environment,
             CredentialStore credentials, HttpClient client) {
    this.currentDirectory = Objects.requireNonNull(currentDirectory, "currentDirectory")
        .toAbsolutePath().normalize();
    this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
    this.environment = Map.copyOf(environment);
    this.credentials = Objects.requireNonNull(credentials, "credentials");
    this.client = Objects.requireNonNull(client, "client");
  }

  static AcpCommand systemDefault() {
    Path home = Path.of(System.getProperty("user.home", "."));
    return new AcpCommand(Path.of(""), home, System.getenv(),
        CredentialStore.systemDefault(), HttpClient.newHttpClient());
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
    ProcessSandbox.Initialization sandbox;
    try {
      sandbox = ProcessSandbox.initialize(arguments.sandbox(), workspace);
    } catch (IllegalArgumentException exception) {
      error.print("ajent: " + exception.getMessage() + "\n");
      return USAGE_ERROR;
    }
    if (!sandbox.valid()) {
      error.print("ajent: --sandbox=on but no backend available. "
          + sandbox.description() + "\n");
      return USAGE_ERROR;
    }
    error.print("ajent: " + sandbox.description() + "\n");

    Profile profile;
    try {
      profile = profile(arguments.profile());
    } catch (IllegalArgumentException exception) {
      error.print("ajent: " + exception.getMessage() + "\n");
      return USAGE_ERROR;
    }
    Path dataDirectory = home.resolve(".agentty");
    Settings settings = new SettingsStore(dataDirectory).load();
    String provider = arguments.provider().isBlank() ? settings.provider() : arguments.provider();
    if (provider.isBlank()) provider = "anthropic";
    String model = arguments.model().isBlank() ? settings.modelId().value() : arguments.model();
    if (model.isBlank()) model = DEFAULT_MODEL;

    CredentialResolver.Resolution anthropic = CredentialResolver.resolve(
        arguments.key(), environment, credentials.load(), System.currentTimeMillis());
    ProviderAuth anthropicAuth = providerAuth(anthropic.credential());
    ProviderAuth auth = ProviderAuthResolver.resolve(
        provider, anthropicAuth, arguments.key(),
        settings.providerKeys().getOrDefault(provider, ""), environment);
    var activeAuth = new AtomicReference<>(auth);
    Path docsRoot = resolveDocs(workspace);
    var toolConfiguration = new ToolRuntimeFactory.Configuration(
        workspace, workspace, home, docsRoot, new JdkWebTransport(), null, null,
        sandbox.runner());
    var tools = ToolRuntimeFactory.compose(toolConfiguration);
    var providerConfiguration = new LiveProviderFactory.Configuration(
        provider, model, auth, settings.effort(), tools.systemPrompt(), 0, environment);
    var sessions = new AgentSessionFactory(
        tools, providerConfiguration, client, dataDirectory);
    boolean keylessLocal = !provider.equals("anthropic") && !Endpoint.fromSpec(provider).useTls();
    var server = new AcpJsonRpcServer(
        dataDirectory, AcpCommand::randomThreadId, profile, model,
        () -> !activeAuth.get().isEmpty(), () -> {
          activeAuth.set(new ProviderAuth.Empty());
          credentials.clear();
        }, AjentCli.VERSION, sessions::create, sessions.contextMax(),
        () -> !activeAuth.get().isEmpty() || keylessLocal);
    error.print("ajent: ACP agent ready on stdio (profile="
        + profile.name().toLowerCase(java.util.Locale.ROOT) + ")\n");
    try {
      server.serve(input, new PrintWriter(output, true, StandardCharsets.UTF_8));
      return 0;
    } catch (IOException exception) {
      String detail = exception.getMessage();
      error.print("ajent: acp failed: "
          + (detail == null ? exception.getClass().getSimpleName() : detail) + "\n");
      return SOFTWARE_ERROR;
    }
  }

  private static Profile profile(String value) {
    return switch (value) {
      case "", "ask" -> Profile.ASK;
      case "write" -> Profile.WRITE;
      case "minimal" -> Profile.MINIMAL;
      default -> throw new IllegalArgumentException(
          "--profile must be write, ask, or minimal (got '" + value + "')");
    };
  }

  private static ProviderAuth providerAuth(Credential credential) {
    return switch (credential) {
      case Credential.None ignored -> new ProviderAuth.Empty();
      case Credential.ApiKey key -> new ProviderAuth.ApiKey(key.key());
      case Credential.OAuth oauth -> new ProviderAuth.Bearer(oauth.accessToken());
    };
  }

  private static ThreadId randomThreadId() {
    return new ThreadId("%016x".formatted(ThreadLocalRandom.current().nextLong()));
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
    if (Files.isDirectory(docs)) return docs;
    Path knowledge = workspace.resolve(".agentty/knowledge");
    return Files.isDirectory(knowledge) ? knowledge : null;
  }

  private Path resolve(String value) {
    Path path = Path.of(value);
    return (path.isAbsolute() ? path : currentDirectory.resolve(path)).toAbsolutePath().normalize();
  }
}
