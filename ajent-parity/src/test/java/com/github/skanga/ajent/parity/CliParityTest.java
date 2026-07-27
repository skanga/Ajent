package com.github.skanga.ajent.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.cli.AjentCli;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

class CliParityTest {
  @Test
  void retainedReferenceFixturesHaveTheirRecordedHashes() {
    assertThat(sha256(bytes("/reference/cli/help.stderr.txt")))
        .isEqualTo("e43c1dd9fe6f109bc277b85ce345f175d64f9b530a94ff22ac60fd0cdd3efb26");
    assertThat(sha256(bytes("/reference/cli/version.stdout.txt")))
        .isEqualTo("bd3e0ea03c912e83beec2c84ff44c497ce39573ee249e9829ee2222bc4a1e239");
    assertThat(sha256(bytes("/reference/cli/invalid.stderr.txt")))
        .isEqualTo("4bbd044f421c0d455fa1f12a2e8d325fef3a7d83300e11b93aa4875b488e55df");
  }

  @Test
  void helpRetainsReferenceSurfacePlusDocumentedAjentProviders() {
    var execution = run("--help");
    assertThat(execution.exitCode()).isZero();
    assertThat(execution.stdout()).isEmpty();
    assertThat(execution.stderr()).isEqualTo(ajentHelp(
        reference("/reference/cli/help.stderr.txt")) + "\n");
  }

  @Test
  void versionMatchesReferenceAfterOnlyTheDeclaredProgramNameNormalization() {
    var execution = run("--version");
    assertThat(execution.exitCode()).isZero();
    assertThat(execution.stderr()).isEmpty();
    assertThat(execution.stdout()).isEqualTo(reference("/reference/cli/version.stdout.txt")
        .replace("agentty", "ajent"));
  }

  @Test
  void invalidArgumentIncludesTheCurrentAjentHelp() {
    var execution = run("--definitely-invalid");
    assertThat(execution.exitCode()).isEqualTo(2);
    assertThat(execution.stdout()).isEmpty();
    assertThat(execution.stderr()).isEqualTo(ajentHelp(
        reference("/reference/cli/invalid.stderr.txt")) + "\n");
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

  private static String reference(String path) {
    return new String(bytes(path), StandardCharsets.UTF_8);
  }

  private static String ajentHelp(String reference) {
    return reference.replace("agentty", "ajent")
        .replace("Authenticate (OAuth via claude.ai or API key)",
            "Authenticate Anthropic, or import Codex CLI login")
        .replace("                      or an OpenAI-compatible one: openai | groq |\n",
            "                      codex (ChatGPT subscription), or an\n"
                + "                      OpenAI-compatible one: openai | groq |\n");
  }

  private static byte[] bytes(String path) {
    try (var stream = CliParityTest.class.getResourceAsStream(path)) {
      if (stream == null) throw new AssertionError("Missing fixture " + path);
      return stream.readAllBytes();
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private static String sha256(byte[] value) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(value);
      return java.util.HexFormat.of().formatHex(hash);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private record Execution(int exitCode, String stdout, String stderr) {}
}
