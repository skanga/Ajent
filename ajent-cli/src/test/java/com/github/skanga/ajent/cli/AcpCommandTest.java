package com.github.skanga.ajent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.provider.auth.CredentialStore;
import com.github.skanga.ajent.provider.auth.Credential;
import com.github.skanga.ajent.core.persistence.Settings;
import com.github.skanga.ajent.core.persistence.SettingsStore;
import com.github.skanga.ajent.domain.ModelId;
import com.github.skanga.ajent.domain.Profile;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AcpCommandTest {
  @Test
  void servesLifecycleOverCleanStdoutAndUsesAskByDefault(@TempDir Path root) throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Execution result = run(root, workspace, "", """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
        {"jsonrpc":"2.0","id":2,"method":"session/new","params":{"cwd":"work"}}
        """);

    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).contains("\"protocolVersion\":1", "\"sessionId\"");
    assertThat(result.stderr()).contains(
        "ajent: sandbox: off", "ajent: ACP agent ready on stdio (profile=ask)");
  }

  @Test
  void tracesEveryAcpFrameToStderrWhenEnabled(@TempDir Path root) throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Execution result = run(root, workspace, "", """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
        """, Map.of("AGENTTY_ACP_TRACE", "1"), workspace.toString(), "off");

    assertThat(result.exitCode()).isZero();
    assertThat(result.stderr()).contains("acp ← {\"jsonrpc\":\"2.0\"",
        "acp → {\"jsonrpc\":\"2.0\"");
  }

  @Test
  void rejectsInvalidProfileBeforeOpeningProtocol(@TempDir Path root) throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Execution result = run(root, workspace, "bogus", "");

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(result.stdout()).isEmpty();
    assertThat(result.stderr()).endsWith(
        "ajent: --profile must be write, ask, or minimal (got 'bogus')\n");
  }

  @Test
  void validatesWorkspaceAndSandboxModes(@TempDir Path root) throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    assertThat(run(root, workspace, "", "", Map.of(), "missing", "off").exitCode())
        .isEqualTo(2);
    assertThat(run(root, workspace, "", "", Map.of(), workspace.toString(), "invalid")
        .stderr()).contains("--sandbox must be auto, on, or off");
    // Force an OS with no sandbox backend so this stays deterministic regardless of whether the
    // host has bwrap/sandbox-exec installed.
    String previousOs = System.setProperty("os.name", "none");
    try {
      assertThat(run(root, workspace, "", "", Map.of(), workspace.toString(), "on")
          .stderr()).contains("--sandbox=on but no backend available");
    } finally {
      if (previousOs == null) System.clearProperty("os.name");
      else System.setProperty("os.name", previousOs);
    }
  }

  @Test
  void acceptsEveryProfileAndCredentialKindAndExecutesLogout(@TempDir Path root)
      throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    var store = new CredentialStore(root.resolve("credentials.json"), "test-machine");
    assertThat(store.save(new Credential.ApiKey("key"))).isTrue();
    assertThat(run(root, workspace, "write", """
        {"jsonrpc":"2.0","id":1,"method":"logout","params":{}}
        """, Map.of(), workspace.toString(), "off", store).exitCode()).isZero();
    assertThat(store.load()).isEmpty();

    assertThat(store.save(new Credential.OAuth("access", "refresh", Long.MAX_VALUE))).isTrue();
    assertThat(run(root, workspace, "minimal", "", Map.of(), workspace.toString(), "off", store)
        .stderr()).contains("profile=minimal");
  }

  @Test
  void restoresSavedProviderModelAndResolvesConfiguredDocs(@TempDir Path root) throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Path home = Files.createDirectories(root.resolve("home"));
    var saved = new Settings(new ModelId("saved-model"), Profile.WRITE, java.util.List.of(),
        "ollama", Map.of(), Map.of(), "high", java.util.List.of());
    assertThat(new SettingsStore(home.resolve(".agentty")).save(saved)).isTrue();
    Path docs = Files.createDirectories(root.resolve("external-docs"));
    var store = new CredentialStore(root.resolve("credentials.json"), "test-machine");
    var command = new AcpCommand(workspace, home, Map.of("AGENTTY_DOCS_DIR", docs.toString()),
        store, HttpClient.newHttpClient());
    var arguments = new CliArguments(CliArguments.Subcommand.ACP, "", "",
        "", "off", "ask", "", java.util.List.of(), java.util.Optional.empty());
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();
    assertThat(command.run(arguments, new BufferedReader(new StringReader("")),
        new PrintStream(output), new PrintStream(error))).isZero();
    assertThat(error.toString(StandardCharsets.UTF_8)).contains("ACP agent ready");
    assertThat(AcpCommand.systemDefault()).isNotNull();
  }

  @Test
  void defaultsBlankProviderAndModelToAnthropic(@TempDir Path root) throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    var command = new AcpCommand(workspace, root.resolve("home"), Map.of(),
        new CredentialStore(root.resolve("credentials.json"), "test-machine"),
        HttpClient.newHttpClient());
    var arguments = new CliArguments(CliArguments.Subcommand.ACP, "", "", "",
        "off", "", "", java.util.List.of(), java.util.Optional.empty());
    assertThat(command.run(arguments, new BufferedReader(new StringReader("")),
        new PrintStream(new ByteArrayOutputStream()),
        new PrintStream(new ByteArrayOutputStream()))).isZero();
  }

  private static Execution run(Path root, Path workspace, String profile, String input) {
    return run(root, workspace, profile, input, Map.of(), workspace.toString(), "off");
  }

  private static Execution run(Path root, Path workspace, String profile, String input,
                               Map<String, String> environment, String workspaceArgument,
                               String sandbox) {
    return run(root, workspace, profile, input, environment, workspaceArgument, sandbox,
        new CredentialStore(root.resolve("credentials.json"), "test-machine"));
  }

  private static Execution run(Path root, Path workspace, String profile, String input,
                               Map<String, String> environment, String workspaceArgument,
                               String sandbox, CredentialStore store) {
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();
    var command = new AcpCommand(workspace, root.resolve("home"), environment, store,
        HttpClient.newHttpClient());
    var arguments = new CliArguments(CliArguments.Subcommand.ACP, "", "qwen3:14b",
        workspaceArgument, sandbox, profile, "ollama", java.util.List.of(),
        java.util.Optional.empty());
    int exitCode = command.run(arguments, new BufferedReader(new StringReader(input)),
        new PrintStream(output, true, StandardCharsets.UTF_8),
        new PrintStream(error, true, StandardCharsets.UTF_8));
    return new Execution(exitCode, output.toString(StandardCharsets.UTF_8),
        error.toString(StandardCharsets.UTF_8));
  }

  private record Execution(int exitCode, String stdout, String stderr) {}
}
