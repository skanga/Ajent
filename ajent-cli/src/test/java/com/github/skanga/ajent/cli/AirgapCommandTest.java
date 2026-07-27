package com.github.skanga.ajent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AirgapCommandTest {
  @Test
  void helpAndInvalidArgumentsUseTheAirgapUsage(@TempDir Path root) {
    assertThat(run(root, Map.of(), List.of("--help"), ignored -> 0)).satisfies(result -> {
      assertThat(result.code()).isZero();
      assertThat(result.error()).startsWith("usage: ajent airgap");
    });
    assertThat(run(root, Map.of(), List.of(), ignored -> 0).code()).isEqualTo(64);
    assertThat(run(root, Map.of(), List.of("host", "extra"), ignored -> 0).error())
        .startsWith("ajent airgap: unrecognized argument: extra");
    assertThat(run(root, Map.of(), List.of("--remote-ajent"), ignored -> 0).code())
        .isEqualTo(64);
  }

  @Test
  void printsNoPtyZedConfigurationAndForwardsAcpTail(@TempDir Path root) {
    var result = run(root, Map.of(),
        List.of("user@host", "--remote-ajent", "/opt/ajent", "--acp",
            "-m", "claude", "--profile", "ask"),
        ignored -> { throw new AssertionError("must not spawn"); });

    assertThat(result.code()).isZero();
    assertThat(result.error()).contains(
        "\"command\": \"ssh\"",
        "\"-T\", \"-R\", \"1080\"",
        "AJENT_SOCKS_PROXY=localhost:1080 exec /opt/ajent acp -m claude --profile ask",
        root.resolve(".config/zed/settings.json").toString(),
        "ajent (airgap)");
  }

  @Test
  void launchesInteractiveSshWithNativeOrderingAndForwardedEnvironment(@TempDir Path root) {
    var calls = new ArrayList<List<String>>();
    Map<String, String> environment = Map.of(
        "AJENT_AIRGAP_SSH", "-p 2222 -J bastion",
        "AJENT_CLIPBOARD_CMD", "printf 'image'",
        "TERM", "xterm-256color", "COLORTERM", "truecolor");
    var result = run(root, environment, List.of("user@host"), call -> {
      calls.add(call); return 23;
    });

    assertThat(result.code()).isEqualTo(23);
    assertThat(calls).singleElement().satisfies(call -> {
      assertThat(call).startsWith("ssh", "-t", "-R", "1080", "-o",
          "ServerAliveInterval=30");
      assertThat(call).containsSubsequence("-p", "2222", "-J", "bastion", "user@host");
      assertThat(call.getLast()).contains(
          "AJENT_CLIPBOARD_CMD='printf '\\''image'\\'''",
          "TERM='xterm-256color'", "COLORTERM='truecolor'", "exec ajent");
    });
  }

  @Test
  void clipboardRelayAddsReverseTunnelAndSynthesizesWaylandCallback(@TempDir Path root) {
    var calls = new ArrayList<List<String>>();
    var result = run(root, Map.of(
        "XDG_SESSION_TYPE", "wayland", "WAYLAND_DISPLAY", "wayland-1", "USER", "sam"),
        List.of("--clipboard-relay", "host"), call -> { calls.add(call); return 0; });

    assertThat(result.error()).contains("needs a running ssh-agent");
    assertThat(calls.getFirst()).containsSubsequence(
        "-A", "-R", "1175:localhost:22");
    assertThat(calls.getFirst().getLast()).contains(
        "sam@localhost WAYLAND_DISPLAY=wayland-1 wl-paste --type image/png");
  }

  @Test
  void setupCopiesCredentialsInThreeFailFastSteps(@TempDir Path root) throws Exception {
    Path credential = root.resolve(".config/ajent/credentials.json");
    Files.createDirectories(credential.getParent());
    Files.writeString(credential, "credential");
    var calls = new ArrayList<List<String>>();
    var result = run(root, Map.of(), List.of("--setup", "host", "--acp"), call -> {
      calls.add(call); return 0;
    });
    assertThat(result.code()).isZero();
    assertThat(calls).containsExactly(
        List.of("ssh", "host", "mkdir -p ~/.config/ajent && chmod 700 ~/.config/ajent"),
        List.of("scp", "-q", credential.toString(),
            "host:.config/ajent/credentials.json"),
        List.of("ssh", "host", "chmod 600 ~/.config/ajent/credentials.json"));
    assertThat(result.error()).contains("credentials copied");

    var attempts = new AtomicInteger();
    var failed = run(root, Map.of(), List.of("--setup", "host"), call ->
        attempts.incrementAndGet() == 2 ? 9 : 0);
    assertThat(failed.code()).isEqualTo(9);
    assertThat(failed.error()).contains("scp failed (exit 9)");
  }

  @Test
  void reportsMissingCredentialsAndSpawnFailure(@TempDir Path root) throws Exception {
    Path emptyHome = Files.createDirectories(root.resolve("empty"));
    assertThat(run(emptyHome, Map.of(), List.of("--setup", "host"), ignored -> 0).error())
        .contains("no local credentials", "ajent login");
    assertThat(run(root, Map.of(), List.of("host"), ignored -> -1)).satisfies(result -> {
      assertThat(result.code()).isEqualTo(1);
      assertThat(result.error()).contains("failed to run `ssh`");
    });
    assertThat(AirgapCommand.shellQuote("a'b")).isEqualTo("'a'\\''b'");
    assertThat(AirgapCommand.systemDefault()).isNotNull();
  }

  @Test
  void coversHomeSetupFailuresAndWindowsSettingsHints(@TempDir Path root) throws Exception {
    var noHomeError = new ByteArrayOutputStream();
    int noHome = new AirgapCommand(Map.of(), null, false, ignored -> 0).run(
        List.of("--setup", "host"), new PrintStream(new ByteArrayOutputStream()),
        new PrintStream(noHomeError));
    assertThat(noHome).isEqualTo(1);
    assertThat(noHomeError.toString(StandardCharsets.UTF_8)).contains("HOME is unset");

    Path credential = root.resolve(".config/ajent/credentials.json");
    Files.createDirectories(credential.getParent());
    Files.writeString(credential, "credential");
    assertThat(run(root, Map.of(), List.of("--setup", "host"), ignored -> -1))
        .satisfies(result -> {
          assertThat(result.code()).isEqualTo(1);
          assertThat(result.error()).contains("remote mkdir failed (ssh exit -1)");
        });
    var step = new AtomicInteger();
    assertThat(run(root, Map.of(), List.of("--setup", "host"), ignored ->
        step.incrementAndGet() == 3 ? 7 : 0).error()).contains("remote chmod failed");

    var windowsError = new ByteArrayOutputStream();
    int windows = new AirgapCommand(Map.of("APPDATA", "C:\\Data"), root, true, ignored -> 0)
        .run(List.of("host", "--acp"), new PrintStream(new ByteArrayOutputStream()),
            new PrintStream(windowsError));
    assertThat(windows).isZero();
    assertThat(windowsError.toString(StandardCharsets.UTF_8))
        .contains("C:\\Data\\Zed\\settings.json");
  }

  @Test
  void synthesizesX11ClipboardWithoutUserAndSkipsAgentWarning(@TempDir Path root) {
    var calls = new ArrayList<List<String>>();
    var result = run(root, Map.of("DISPLAY", ":1", "SSH_AUTH_SOCK", "agent.sock"),
        List.of("--clipboard-relay", "host"), call -> { calls.add(call); return 0; });
    assertThat(result.error()).doesNotContain("needs a running ssh-agent");
    assertThat(calls.getFirst().getLast()).contains(
        "localhost DISPLAY=:1 xclip -selection clipboard -t image/png -o");
  }

  private static Execution run(Path home, Map<String, String> environment,
                               List<String> arguments,
                               AirgapCommand.ProcessExecutor executor) {
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();
    int code = new AirgapCommand(environment, home, false, executor).run(arguments,
        new PrintStream(output, true, StandardCharsets.UTF_8),
        new PrintStream(error, true, StandardCharsets.UTF_8));
    return new Execution(code, output.toString(StandardCharsets.UTF_8),
        error.toString(StandardCharsets.UTF_8));
  }

  private record Execution(int code, String output, String error) {}
}
