package com.github.skanga.ajent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AjentCliTest {
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
      assertThat(result.stderr()).isEqualTo(expected);
    }
  }

  @Test
  void unknownArgumentsWriteTheErrorThenUsageAndExitTwo() {
    var result = run("--definitely-invalid");
    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(result.stdout()).isEmpty();
    assertThat(result.stderr()).isEqualTo(
        "unknown arg: --definitely-invalid\n\n" + resource("/cli/help.stderr.txt"));
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

  private static Execution run(String... arguments) {
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    int exitCode = AjentCli.run(arguments,
        new PrintStream(stdout, true, StandardCharsets.UTF_8),
        new PrintStream(stderr, true, StandardCharsets.UTF_8));
    return new Execution(exitCode, stdout.toString(StandardCharsets.UTF_8),
        stderr.toString(StandardCharsets.UTF_8));
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
