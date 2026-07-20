package com.github.skanga.ajent.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Pinned-executable terminal captures under a real deterministic Windows PTY. */
@EnabledOnOs(OS.WINDOWS)
final class NativeTerminalParityIT {
  private static final int COLUMNS = 80;
  private static final int ROWS = 24;

  @Test
  void bothExecutablesStartAndShutDownCleanlyUnderAn80By24Console(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeHome = Files.createDirectories(root.resolve("native-home"));
    Path javaHome = Files.createDirectories(root.resolve("java-home"));

    Path nativeLauncher = launcher(root.resolve("native-terminal.cmd"),
        List.of(nativeBinary.toString()));
    Capture nativeCapture = capture(List.of("cmd.exe", "/d", "/c", nativeLauncher.toString()),
        Files.createDirectories(root.resolve("native-workspace")),
        nativeHome);
    Capture javaCapture = capturePiped(List.of(javaExecutable(),
            "--enable-native-access=ALL-UNNAMED", "-Duser.home=" + javaHome,
            "-Dajent.terminal.fixedSize=" + COLUMNS + "x" + ROWS,
            "-jar", ajentJar.toString()),
        Files.createDirectories(root.resolve("java-workspace")), javaHome);

    int windowsControlC = 0xc000013a;
    assertThat(nativeCapture.exitCode()).as("native output:%n%s", nativeCapture.output())
        .isIn(0, windowsControlC);
    assertThat(javaCapture.exitCode()).as("Java output:%n%s", javaCapture.output())
        .isIn(0, 130, windowsControlC);
    assertThat(nativeCapture.output()).doesNotContain("failed to initialize terminal");
    assertThat(javaCapture.output()).doesNotContain("failed to initialize terminal");
    assertThat(nativeCapture.output()).hasSizeGreaterThan(256)
        .doesNotContain(nativeHome.toString(), javaHome.toString());
    assertThat(javaCapture.output()).hasSizeGreaterThan(256)
        .doesNotContain(nativeHome.toString(), javaHome.toString());
    String nativeFrame = unwrapped(nativeCapture.output());
    String javaFrame = unwrapped(javaCapture.output());
    for (String visible : List.of("a calm middleware between you and the model", "type to begin",
        "palette", "threads", "profile", "models", "provider", "new", "Ready",
        "Ollama", "WRITE")) {
      assertThat(nativeFrame).as("native startup label").containsIgnoringCase(visible);
      assertThat(javaFrame).as("Java startup label").containsIgnoringCase(visible);
    }

    var nativeViewport = new AnsiViewport(COLUMNS, ROWS);
    nativeViewport.feed(nativeCapture.output());
    var javaViewport = new AnsiViewport(COLUMNS, ROWS);
    javaViewport.feed(javaCapture.output());
    assertThat(javaViewport.lines().subList(7, ROWS)).as(
            "native viewport:%n%s%nJava viewport:%n%s",
            numbered(nativeViewport.lines()), numbered(javaViewport.lines()))
        .containsExactlyElementsOf(nativeViewport.lines().subList(7, ROWS));
    assertThat(nativeViewport.lines().subList(0, 7)).anyMatch(line -> !line.isBlank());
    assertThat(javaViewport.lines().subList(0, 7)).anyMatch(line -> !line.isBlank());
  }

  private static Capture capture(List<String> executable, Path workspace, Path home)
      throws Exception {
    var command = new ArrayList<>(executable);
    command.addAll(List.of("--provider", "ollama", "--model", "qwen2.5-coder:7b",
        "--workspace", workspace.toString()));
    Map<String, String> environment = new HashMap<>(System.getenv());
    environment.put("APPDATA", home.toString());
    environment.put("LOCALAPPDATA", home.toString());
    environment.put("USERPROFILE", home.toString());
    environment.put("HOME", home.toString());
    environment.put("TERM", "xterm-256color");
    environment.put("COLUMNS", Integer.toString(COLUMNS));
    environment.put("LINES", Integer.toString(ROWS));
    PtyProcess process = new PtyProcessBuilder(command.toArray(String[]::new))
        .setDirectory(workspace.toString())
        .setEnvironment(environment)
        .setInitialColumns(COLUMNS)
        .setInitialRows(ROWS)
        .setConsole(false)
        .setRedirectErrorStream(true)
        .start();
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();
    Thread reader = Thread.startVirtualThread(() -> {
      try {
        process.getInputStream().transferTo(output);
      } catch (java.io.IOException ignored) {
        // Closing the PTY during a normal shutdown may terminate the read first.
      }
    });
    Thread errorReader = Thread.startVirtualThread(() -> {
      try {
        process.getErrorStream().transferTo(error);
      } catch (java.io.IOException ignored) {
        // Closing the PTY during a normal shutdown may terminate the read first.
      }
    });
    awaitWelcome(output, error, Duration.ofSeconds(8));
    if (process.isAlive()) {
      process.getOutputStream().write(3);
      process.getOutputStream().flush();
      Thread.sleep(200);
      if (process.isAlive()) {
        process.getOutputStream().write("Y\r".getBytes(StandardCharsets.US_ASCII));
        process.getOutputStream().flush();
      }
    }
    if (!process.waitFor(8, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(3, TimeUnit.SECONDS);
    }
    reader.join(Duration.ofSeconds(3));
    errorReader.join(Duration.ofSeconds(3));
    return new Capture(process.exitValue(), output.toString(StandardCharsets.UTF_8)
        + error.toString(StandardCharsets.UTF_8));
  }

  private static Capture capturePiped(List<String> executable, Path workspace, Path home)
      throws Exception {
    var command = new ArrayList<>(executable);
    command.addAll(List.of("--provider", "ollama", "--model", "qwen2.5-coder:7b",
        "--workspace", workspace.toString()));
    ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile())
        .redirectErrorStream(true);
    Map<String, String> environment = builder.environment();
    environment.put("APPDATA", home.toString());
    environment.put("LOCALAPPDATA", home.toString());
    environment.put("USERPROFILE", home.toString());
    environment.put("HOME", home.toString());
    environment.put("TERM", "xterm-256color");
    environment.put("COLUMNS", Integer.toString(COLUMNS));
    environment.put("LINES", Integer.toString(ROWS));
    Process process = builder.start();
    var output = new ByteArrayOutputStream();
    Thread reader = Thread.startVirtualThread(() -> {
      try { process.getInputStream().transferTo(output); }
      catch (java.io.IOException ignored) { }
    });
    awaitWelcome(output, new ByteArrayOutputStream(), Duration.ofSeconds(8));
    if (process.isAlive()) {
      // Use the enhanced-keyboard encoding requested by ENTER_INLINE. An external JLine
      // terminal can consume raw ETX as an input signal before it reaches Ajent's decoder.
      process.getOutputStream().write("\u001b[99;5u\r".getBytes(StandardCharsets.US_ASCII));
      process.getOutputStream().flush();
    }
    if (!process.waitFor(8, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(3, TimeUnit.SECONDS);
    }
    reader.join(Duration.ofSeconds(3));
    return new Capture(process.exitValue(), output.toString(StandardCharsets.UTF_8));
  }

  private static void awaitWelcome(ByteArrayOutputStream output, ByteArrayOutputStream error,
                                   Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      String captured = output.toString(StandardCharsets.UTF_8)
          + error.toString(StandardCharsets.UTF_8);
      if (captured.contains("a calm middleware between you and the model")) {
        Thread.sleep(150);
        return;
      }
      Thread.sleep(20);
    }
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("pom.xml"))
          && Files.isDirectory(current.resolve("ajent-parity"))) return current;
      current = current.getParent();
    }
    throw new IllegalStateException("Ajent repository root not found");
  }

  private static String javaExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
  }

  private static Path launcher(Path path, List<String> command) throws java.io.IOException {
    String invocation = command.stream().map(NativeTerminalParityIT::cmdQuote)
        .collect(java.util.stream.Collectors.joining(" "));
    return Files.writeString(path, "@echo off\r\nchcp 65001 >nul\r\n" + invocation
        + " %*\r\n", StandardCharsets.UTF_8);
  }

  private static String cmdQuote(String argument) {
    return "\"" + argument.replace("\"", "\"\"") + "\"";
  }

  private static String unwrapped(String output) {
    return output.replace("\r", "").replace("\n", "");
  }

  private static String numbered(List<String> lines) {
    var result = new StringBuilder();
    for (int index = 0; index < lines.size(); index++) {
      result.append(String.format("%02d|%s%n", index, lines.get(index)));
    }
    return result.toString();
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name, "").strip();
    if (value.isEmpty()) throw new IllegalStateException("missing system property: " + name);
    return value;
  }

  private record Capture(int exitCode, String output) {}
}
