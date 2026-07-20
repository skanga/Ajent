package com.github.skanga.ajent.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeExecutableParityIT {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PINNED_SHA256 =
      "8b108f09a62220136383835b52f1506f003e6da246a9b1335846ab3e11733fff";

  @Test
  void pinnedWindowsExecutableMatchesDeterministicAjentCliSurfaces(@TempDir Path home)
      throws Exception {
    Path root = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = root.resolve("ajent-cli/target/ajent.jar");

    assertThat(nativeBinary).isRegularFile();
    assertThat(ajentJar).isRegularFile();
    assertThat(sha256(nativeBinary)).isEqualTo(PINNED_SHA256);

    Map<String, String> isolated = Map.of(
        "HOME", home.toString(), "USERPROFILE", home.toString(), "APPDATA", home.toString());
    Path missingWorkspace = home.resolve("missing-workspace");
    for (List<String> arguments : List.of(
        List.of("--version"), List.of("--help"), List.of("--definitely-invalid"),
        List.of("status"), List.of("logout"), List.of("skills"),
        List.of("airgap", "--help"),
        List.of("acp", "--workspace", missingWorkspace.toString()),
        List.of("mcp-serve", "--workspace", missingWorkspace.toString()))) {
      Map<String, String> caseEnvironment = arguments.equals(List.of("skills"))
          ? Map.of() : isolated;
      Execution nativeRun = execute(command(nativeBinary, arguments), caseEnvironment);
      Execution javaRun = execute(javaCommand(ajentJar, arguments), caseEnvironment);

      assertThat(normalize(nativeRun, home, true)).as("native %s", arguments)
          .isEqualTo(normalize(javaRun, home, false));
    }
  }

  @Test
  void modelSettingsPersistBeforeSandboxValidationLikePinnedExecutable(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeHome = Files.createDirectories(root.resolve("native-home"));
    Path javaHome = Files.createDirectories(root.resolve("java-home"));
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    String seeded = """
        {
          "model_id":"old-model",
          "profile":2,
          "favorite_models":["favorite-a","favorite-b"],
          "provider":"anthropic",
          "provider_keys":{"groq":"saved-key"},
          "provider_models":{"anthropic":"old-model","ollama":"qwen3:14b"},
          "effort":"high",
          "always_allow_tools":["read","write"]
        }
        """;
    seedSettings(nativeHome, seeded);
    seedSettings(javaHome, seeded);
    List<String> arguments = List.of("--model", "gpt-parity", "--provider", "openai",
        "--workspace", workspace.toString(), "--sandbox", "invalid");

    Execution nativeRun = execute(command(nativeBinary, arguments), isolated(nativeHome));
    Execution javaRun = execute(javaCommand(ajentJar, arguments, javaHome), isolated(javaHome));

    assertThat(normalize(nativeRun, nativeHome, true))
        .isEqualTo(normalize(javaRun, javaHome, false));
    JsonNode nativeSettings = JSON.readTree(
        nativeHome.resolve(".agentty/settings.json").toFile());
    JsonNode javaSettings = JSON.readTree(
        javaHome.resolve(".agentty/settings.json").toFile());
    assertThat(javaSettings).isEqualTo(nativeSettings);
    assertThat(javaSettings.path("model_id").textValue()).isEqualTo("gpt-parity");
    assertThat(javaSettings.path("provider").textValue()).isEqualTo("anthropic");
    assertThat(javaSettings.at("/provider_models/openai").isMissingNode()).isTrue();
    assertThat(javaSettings.path("favorite_models")).hasSize(2);
    assertThat(javaSettings.path("always_allow_tools")).hasSize(2);
    assertThat(Files.exists(nativeHome.resolve(".agentty/settings.json.tmp"))).isFalse();
    assertThat(Files.exists(javaHome.resolve(".agentty/settings.json.tmp"))).isFalse();
  }

  private static void seedSettings(Path home, String content) throws Exception {
    Path data = Files.createDirectories(home.resolve(".agentty"));
    Files.writeString(data.resolve("settings.json"), content, StandardCharsets.UTF_8);
  }

  private static Map<String, String> isolated(Path home) {
    return Map.of("HOME", home.toString(), "USERPROFILE", home.toString(),
        "APPDATA", home.toString());
  }

  private static Execution normalize(Execution execution, Path home, boolean nativeProgram) {
    String prefix = home.toAbsolutePath().normalize().toString();
    String stdout = execution.stdout().replace(prefix, "<HOME>");
    String stderr = execution.stderr().replace(prefix, "<HOME>");
    if (nativeProgram) {
      stdout = normalizeProgramName(stdout);
      stderr = normalizeProgramName(stderr);
    }
    return new Execution(execution.exitCode(), stdout, stderr);
  }

  private static String normalizeProgramName(String value) {
    return value.replace(".config\\agentty", ".config\\<CONFIG_NAME>")
        .replace(".config/agentty", ".config/<CONFIG_NAME>")
        .replace("--remote-agentty", "--remote-<CONFIG_NAME>")
        .replace("agentty", "ajent")
        .replace("<CONFIG_NAME>", "agentty");
  }

  private static List<String> command(Path executable, List<String> arguments) {
    var result = new ArrayList<String>();
    result.add(executable.toString());
    result.addAll(arguments);
    return List.copyOf(result);
  }

  private static List<String> javaCommand(Path jar, List<String> arguments) {
    return javaCommand(jar, arguments, null);
  }

  private static List<String> javaCommand(Path jar, List<String> arguments, Path home) {
    String executable = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    var result = new ArrayList<String>();
    result.add(executable);
    if (home != null) result.add("-Duser.home=" + home);
    result.add("-jar");
    result.add(jar.toString());
    result.addAll(arguments);
    return List.copyOf(result);
  }

  private static Execution execute(List<String> command, Map<String, String> environment)
      throws Exception {
    var builder = new ProcessBuilder(command).redirectErrorStream(false);
    builder.environment().putAll(environment);
    Process process = builder.start();
    process.getOutputStream().close();
    if (!process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("timed out: " + command);
    }
    return new Execution(process.exitValue(),
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
        new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
  }

  private static String sha256(Path path) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name, "");
    if (value.isBlank()) throw new AssertionError("missing system property " + name);
    return value;
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("pom.xml"))
          && Files.isDirectory(current.resolve("ajent-parity"))) return current;
      current = current.getParent();
    }
    throw new IllegalStateException("Ajent repository root not found");
  }

  private record Execution(int exitCode, String stdout, String stderr) {}
}
