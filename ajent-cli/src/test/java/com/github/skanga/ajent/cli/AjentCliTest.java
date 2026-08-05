package com.github.skanga.ajent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AjentCliTest {
  @Test
  void mainReturnsNormallyForSuccessfulCommands() {
    AjentCli.main(new String[] {"--version"});
  }

  @Test
  void processBoundaryUsesNativeWindowsTextNewlinesWithoutDoublingCrLf() {
    var windowsBytes = new ByteArrayOutputStream();
    PrintStream windows = AjentCli.processStream(windowsBytes, "\r\n");
    windows.print("one\ntwo\r\n");
    windows.flush();
    assertThat(windowsBytes.toString(StandardCharsets.UTF_8)).isEqualTo("one\r\ntwo\r\n");

    var unixBytes = new ByteArrayOutputStream();
    PrintStream unix = AjentCli.processStream(unixBytes, "\n");
    unix.print("one\ntwo\r\n");
    unix.flush();
    assertThat(unixBytes.toString(StandardCharsets.UTF_8)).isEqualTo("one\ntwo\r\n");
  }

  @Test
  void versionAliasesWriteOnlyToStdoutAndShortCircuitRemainingArguments() {
    for (String[] arguments : new String[][] {
        {"--version"}, {"-V"}, {"version"}, {"--version", "-k", "unused"}}) {
      var result = run(arguments);
      assertThat(result.exitCode()).as(String.join(" ", arguments)).isZero();
      assertThat(result.stdout()).isEqualTo("ajent 0.2.8\n");
      assertThat(result.stderr()).isEmpty();
    }
  }

  @Test
  void helpAliasesWriteTheGoldenUsageOnlyToStderr() {
    String expected = resource("/cli/help.stderr.txt");
    for (String[] arguments : new String[][] {{"--help"}, {"-h"}, {"help"}}) {
      var result = run(arguments);
      assertThat(result.exitCode()).as(String.join(" ", arguments)).isZero();
      assertThat(result.stdout()).isEmpty();
      assertThat(result.stderr()).isEqualTo(expected + "\n");
    }
  }

  @Test
  void unknownArgumentsWriteTheErrorThenUsageAndExitTwo() {
    var result = run("--definitely-invalid");
    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(result.stdout()).isEmpty();
    assertThat(result.stderr()).isEqualTo(
        "unknown arg: --definitely-invalid\n\n" + resource("/cli/help.stderr.txt") + "\n");
  }

  @Test
  void anOptionWithoutItsRequiredValueIsAnUnknownArgument() {
    var result = run("--model");
    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(result.stderr()).startsWith("unknown arg: --model\n\n");
  }

  @Test
  void allTopLevelOptionsAndSubcommandsParseLikeTheReference() {
    var parsed = CliArguments.parse(new String[] {
        "-k", "secret", "--model", "claude-opus-4-5", "-w", "work",
        "--sandbox", "off", "-p", "minimal", "--provider", "ollama", "acp"});
    assertThat(parsed.badArgument()).isEmpty();
    assertThat(parsed.subcommand()).isEqualTo(CliArguments.Subcommand.ACP);
    assertThat(parsed.key()).isEqualTo("secret");
    assertThat(parsed.model()).isEqualTo("claude-opus-4-5");
    assertThat(parsed.workspace()).isEqualTo("work");
    assertThat(parsed.sandbox()).isEqualTo("off");
    assertThat(parsed.profile()).isEqualTo("minimal");
    assertThat(parsed.provider()).isEqualTo("ollama");
  }

  @Test
  void airgapStopsTopLevelParsingAndForwardsTheRemainingTailVerbatim() {
    var parsed = CliArguments.parse(new String[] {
        "--model", "ignored-by-airgap-handler", "airgap", "--setup", "user@host",
        "--acp", "-m", "claude-haiku-4-5"});
    assertThat(parsed.subcommand()).isEqualTo(CliArguments.Subcommand.AIRGAP);
    assertThat(parsed.airgapArguments()).containsExactly(
        "--setup", "user@host", "--acp", "-m", "claude-haiku-4-5");
    assertThat(parsed.model()).isEqualTo("ignored-by-airgap-handler");
  }

  @Test
  void allReferenceSubcommandNamesAreRecognized() {
    assertThat(CliArguments.Subcommand.names()).containsExactlyInAnyOrder(
        "login", "logout", "status", "airgap", "acp", "mcp-serve", "skills",
        "version", "help");
  }

  @Test void dispatchesAuthenticationCommandsThroughTheCompositionSeam() {
    var services = new AjentCli.CommandServices() {
      @Override public int login(BufferedReader input, PrintStream output, PrintStream error) {
        output.print("login:" + read(input)); return 11;
      }
      @Override public int loginCodex(PrintStream output, PrintStream error) {
        output.print("codex-import"); return 19;
      }
      @Override public int logoutCodex(PrintStream output, PrintStream error) {
        output.print("codex-logout"); return 20;
      }
      @Override public int statusCodex(PrintStream output) {
        output.print("codex-status"); return 21;
      }
      @Override public int logout(PrintStream output, PrintStream error) {
        output.print("logout"); return 12;
      }
      @Override public int status(PrintStream output) { output.print("status"); return 13; }
      @Override public int skills(PrintStream output) { output.print("skills"); return 14; }
      @Override public int mcpServe(CliArguments arguments, BufferedReader input,
                                    PrintStream output, PrintStream error) {
        output.print("mcp:" + arguments.workspace()); return 15;
      }
      @Override public int acp(CliArguments arguments, BufferedReader input,
                               PrintStream output, PrintStream error) {
        output.print("acp:" + arguments.model()); return 16;
      }
      @Override public int airgap(CliArguments arguments, PrintStream output, PrintStream error) {
        output.print("airgap:" + String.join(",", arguments.airgapArguments())); return 17;
      }
      @Override public int interactive(CliArguments arguments, PrintStream error) {
        error.print("interactive:" + arguments.provider()); return 18;
      }
    };
    assertThat(run(services, "answer\n", "login")).satisfies(result -> {
      assertThat(result.exitCode()).isEqualTo(11); assertThat(result.stdout()).isEqualTo("login:answer");
    });
    assertThat(run(services, "", "login", "--provider", "codex")).satisfies(result -> {
      assertThat(result.exitCode()).isEqualTo(19);
      assertThat(result.stdout()).isEqualTo("codex-import");
    });
    assertThat(run(services, "", "logout", "--provider", "codex")).satisfies(result -> {
      assertThat(result.exitCode()).isEqualTo(20);
      assertThat(result.stdout()).isEqualTo("codex-logout");
    });
    assertThat(run(services, "", "status", "--provider", "codex")).satisfies(result -> {
      assertThat(result.exitCode()).isEqualTo(21);
      assertThat(result.stdout()).isEqualTo("codex-status");
    });
    assertThat(run(services, "", "logout")).satisfies(result -> {
      assertThat(result.exitCode()).isEqualTo(12); assertThat(result.stdout()).isEqualTo("logout");
    });
    assertThat(run(services, "", "status")).satisfies(result -> {
      assertThat(result.exitCode()).isEqualTo(13); assertThat(result.stdout()).isEqualTo("status");
    });
    assertThat(run(services, "", "skills")).satisfies(result -> {
      assertThat(result.exitCode()).isEqualTo(14); assertThat(result.stdout()).isEqualTo("skills");
    });
    assertThat(run(services, "", "mcp-serve", "--workspace", "work")).satisfies(result -> {
      assertThat(result.exitCode()).isEqualTo(15); assertThat(result.stdout()).isEqualTo("mcp:work");
    });
    assertThat(run(services, "", "acp", "--model", "model")).satisfies(result -> {
      assertThat(result.exitCode()).isEqualTo(16); assertThat(result.stdout()).isEqualTo("acp:model");
    });
    assertThat(run(services, "", "airgap", "host", "--acp", "-m", "model"))
        .satisfies(result -> {
          assertThat(result.exitCode()).isEqualTo(17);
          assertThat(result.stdout()).isEqualTo("airgap:host,--acp,-m,model");
        });
    assertThat(run(services, "", "--provider", "ollama")).satisfies(result -> {
      assertThat(result.exitCode()).isEqualTo(18);
      assertThat(result.stderr()).isEqualTo("interactive:ollama");
    });
  }

  private static Execution run(String... arguments) {
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    int exitCode = AjentCli.run(arguments,
        new PrintStream(stdout, true, StandardCharsets.UTF_8),
        new PrintStream(stderr, true, StandardCharsets.UTF_8));
    return new Execution(exitCode, stdout.toString(StandardCharsets.UTF_8),
        stderr.toString(StandardCharsets.UTF_8));
  }

  private static Execution run(AjentCli.CommandServices services, String input,
                               String... arguments) {
    var stdout = new ByteArrayOutputStream(); var stderr = new ByteArrayOutputStream();
    int exitCode = AjentCli.run(arguments, new BufferedReader(new StringReader(input)),
        new PrintStream(stdout, true, StandardCharsets.UTF_8),
        new PrintStream(stderr, true, StandardCharsets.UTF_8), services);
    return new Execution(exitCode, stdout.toString(StandardCharsets.UTF_8),
        stderr.toString(StandardCharsets.UTF_8));
  }

  private static String read(BufferedReader input) {
    try { return input.readLine(); } catch (Exception exception) { throw new AssertionError(exception); }
  }

  private static String resource(String path) {
    try (var stream = AjentCliTest.class.getResourceAsStream(path)) {
      if (stream == null) throw new AssertionError("Missing test resource " + path);
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private record Execution(int exitCode, String stdout, String stderr) {}
}
