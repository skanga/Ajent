package com.github.skanga.ajent.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
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
  private static final String TAGLINE = "a calm middleware between you and the model";

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
        Files.createDirectories(root.resolve("native-workspace")), nativeHome, "", "");
    Capture javaCapture = capturePiped(List.of(javaExecutable(),
            "--enable-native-access=ALL-UNNAMED", "-Duser.home=" + javaHome,
            "-Dajent.terminal.fixedSize=" + COLUMNS + "x" + ROWS,
            "-jar", ajentJar.toString()),
        Files.createDirectories(root.resolve("java-workspace")), javaHome, "", "");

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
    nativeViewport.feed(nativeCapture.frame());
    var javaViewport = new AnsiViewport(COLUMNS, ROWS);
    javaViewport.feed(javaCapture.frame());
    assertThat(stableRegion(javaViewport.lines())).as(
            "native viewport:%n%s%nJava viewport:%n%s",
            numbered(nativeViewport.lines()), numbered(javaViewport.lines()))
        .containsExactlyElementsOf(stableRegion(nativeViewport.lines()));
    assertThat(linesBeforeTagline(nativeViewport.lines())).anyMatch(line -> !line.isBlank());
    assertThat(linesBeforeTagline(javaViewport.lines())).anyMatch(line -> !line.isBlank());
  }

  @Test
  void typedComposerEditingMatchesTheNativeViewport(@TempDir Path root) throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path nativeHome = Files.createDirectories(root.resolve("native-home"));
    Path javaHome = Files.createDirectories(root.resolve("java-home"));
    Path nativeLauncher = launcher(root.resolve("native-composer.cmd"),
        List.of(nativeBinary.toString()));
    String interaction = "hello world" + "\u001b[D".repeat(5) + "X";
    String expected = "hello Xworld";

    Capture nativeCapture = capture(List.of("cmd.exe", "/d", "/c", nativeLauncher.toString()),
        Files.createDirectories(root.resolve("native-workspace")), nativeHome,
        interaction, expected);
    Capture javaCapture = capturePiped(List.of(javaExecutable(),
            "--enable-native-access=ALL-UNNAMED", "-Duser.home=" + javaHome,
            "-Dajent.terminal.fixedSize=" + COLUMNS + "x" + ROWS,
            "-jar", repository.resolve("ajent-cli/target/ajent.jar").toString()),
        Files.createDirectories(root.resolve("java-workspace")), javaHome,
        interaction, expected);

    assertCleanExit(nativeCapture, "native");
    assertCleanExit(javaCapture, "Java");
    var nativeViewport = new AnsiViewport(COLUMNS, ROWS);
    nativeViewport.feed(nativeCapture.frame());
    var javaViewport = new AnsiViewport(COLUMNS, ROWS);
    javaViewport.feed(javaCapture.frame());
    assertThat(nativeViewport.lines()).anyMatch(
        line -> withoutPaintedCursor(line).contains(expected));
    assertThat(javaViewport.lines()).anyMatch(
        line -> withoutPaintedCursor(line).contains(expected));
    assertThat(stableRegion(javaViewport.lines())).as(
            "native viewport:%n%s%nJava viewport:%n%s",
            numbered(nativeViewport.lines()), numbered(javaViewport.lines()))
        .containsExactlyElementsOf(stableRegion(nativeViewport.lines()));
  }

  @Test
  void multilineComposerMatchesTheNativeViewport(@TempDir Path root) throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path nativeHome = Files.createDirectories(root.resolve("native-home"));
    Path javaHome = Files.createDirectories(root.resolve("java-home"));
    Path nativeLauncher = launcher(root.resolve("native-multiline.cmd"),
        List.of(nativeBinary.toString()));
    String interaction = "first\u001b[13;2usecond";

    Capture nativeCapture = capture(List.of("cmd.exe", "/d", "/c", nativeLauncher.toString()),
        Files.createDirectories(root.resolve("native-workspace")), nativeHome,
        interaction, "second");
    Capture javaCapture = capturePiped(List.of(javaExecutable(),
            "--enable-native-access=ALL-UNNAMED", "-Duser.home=" + javaHome,
            "-Dajent.terminal.fixedSize=" + COLUMNS + "x" + ROWS,
            "-jar", repository.resolve("ajent-cli/target/ajent.jar").toString()),
        Files.createDirectories(root.resolve("java-workspace")), javaHome,
        interaction, "second");

    assertCleanExit(nativeCapture, "native");
    assertCleanExit(javaCapture, "Java");
    var nativeViewport = new AnsiViewport(COLUMNS, ROWS);
    nativeViewport.feed(nativeCapture.frame());
    var javaViewport = new AnsiViewport(COLUMNS, ROWS);
    javaViewport.feed(javaCapture.frame());
    assertThat(nativeViewport.lines()).anyMatch(line -> line.contains("┊ second"));
    assertThat(javaViewport.lines()).anyMatch(line -> line.contains("┊ second"));
    assertThat(stableRegion(javaViewport.lines())).as(
            "native viewport:%n%s%nJava viewport:%n%s",
            numbered(nativeViewport.lines()), numbered(javaViewport.lines()))
        .containsExactlyElementsOf(stableRegion(nativeViewport.lines()));
  }

  @Test
  void wideUnicodeSoftWrapAndBackspaceMatchTheNativeViewport(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path nativeHome = Files.createDirectories(root.resolve("native-home"));
    Path javaHome = Files.createDirectories(root.resolve("java-home"));
    Path nativeLauncher = launcher(root.resolve("native-unicode-wrap.cmd"),
        List.of(nativeBinary.toString()));
    String interaction = "0123456789".repeat(7) + "中界\u007fé";

    Capture nativeCapture = capture(List.of("cmd.exe", "/d", "/c", nativeLauncher.toString()),
        Files.createDirectories(root.resolve("native-workspace")), nativeHome,
        interaction, "é");
    Capture javaCapture = capturePiped(List.of(javaExecutable(),
            "--enable-native-access=ALL-UNNAMED", "-Duser.home=" + javaHome,
            "-Dajent.terminal.fixedSize=" + COLUMNS + "x" + ROWS,
            "-jar", repository.resolve("ajent-cli/target/ajent.jar").toString()),
        Files.createDirectories(root.resolve("java-workspace")), javaHome,
        interaction, "é");

    assertCleanExit(nativeCapture, "native");
    assertCleanExit(javaCapture, "Java");
    var nativeViewport = new AnsiViewport(COLUMNS, ROWS);
    nativeViewport.feed(nativeCapture.frame());
    var javaViewport = new AnsiViewport(COLUMNS, ROWS);
    javaViewport.feed(javaCapture.frame());
    assertThat(nativeViewport.lines()).anyMatch(line -> line.contains("中"));
    assertThat(javaViewport.lines()).anyMatch(line -> line.contains("中"));
    assertThat(stableRegion(javaViewport.lines())).as(
            "native viewport:%n%s%nJava viewport:%n%s",
            numbered(nativeViewport.lines()), numbered(javaViewport.lines()))
        .containsExactlyElementsOf(stableRegion(nativeViewport.lines()));
  }

  @Test
  void settledStreamedProviderTurnMatchesTheNativeViewport(@TempDir Path root)
      throws Exception {
    var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
    HttpServer provider = streamedTextProvider(requests);
    provider.start();
    String endpoint = "127.0.0.1:" + provider.getAddress().getPort();
    try {
      Path repository = repositoryRoot();
      Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
      Path nativeHome = Files.createDirectories(root.resolve("native-home"));
      Path javaHome = Files.createDirectories(root.resolve("java-home"));
      Path nativeLauncher = launcher(root.resolve("native-streamed-turn.cmd"),
          List.of(nativeBinary.toString()));

      Capture nativeCapture = capture(
          List.of("cmd.exe", "/d", "/c", nativeLauncher.toString()),
          Files.createDirectories(root.resolve("native-workspace")), nativeHome,
          endpoint, "parity-model", "say hello\r", "terminal parity response");
      Capture javaCapture = capturePiped(List.of(javaExecutable(),
              "--enable-native-access=ALL-UNNAMED", "-Duser.home=" + javaHome,
              "-Dajent.terminal.fixedSize=" + COLUMNS + "x" + ROWS,
              "-jar", repository.resolve("ajent-cli/target/ajent.jar").toString()),
          Files.createDirectories(root.resolve("java-workspace")), javaHome,
          endpoint, "parity-model", "say hello\r", "terminal parity response");

      assertCleanExit(nativeCapture, "native");
      assertCleanExit(javaCapture, "Java");
      assertThat(requests).hasSize(2).allMatch(request -> request.contains("say hello"));
      var nativeViewport = new AnsiViewport(COLUMNS, ROWS);
      nativeViewport.feed(nativeCapture.frame());
      var javaViewport = new AnsiViewport(COLUMNS, ROWS);
      javaViewport.feed(javaCapture.frame());
      assertThat(nativeViewport.lines()).anyMatch(
          line -> line.contains("terminal parity response"));
      assertThat(javaViewport.lines()).anyMatch(
          line -> line.contains("terminal parity response"));
      assertThat(regionFrom(javaViewport.lines(), "say hello")).as(
              "native viewport:%n%s%nJava viewport:%n%s",
              numbered(nativeViewport.lines()), numbered(javaViewport.lines()))
          .containsExactlyElementsOf(regionFrom(nativeViewport.lines(), "say hello"));
    } finally {
      provider.stop(0);
    }
  }

  @Test
  void permissionAndCompletedToolCardMatchTheNativeViewport(@TempDir Path root)
      throws Exception {
    var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
    HttpServer provider = permissionProvider(requests);
    provider.start();
    String endpoint = "127.0.0.1:" + provider.getAddress().getPort();
    try {
      Path repository = repositoryRoot();
      Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
      Path nativeHome = Files.createDirectories(root.resolve("native-home"));
      Path javaHome = Files.createDirectories(root.resolve("java-home"));
      Path nativeWorkspace = Files.createDirectories(root.resolve("native-workspace"));
      Path javaWorkspace = Files.createDirectories(root.resolve("java-workspace"));
      Path nativeLauncher = launcher(root.resolve("native-permission.cmd"),
          List.of(nativeBinary.toString()));

      StagedCapture nativeCapture = capturePermission(
          List.of("cmd.exe", "/d", "/c", nativeLauncher.toString()),
          nativeWorkspace, nativeHome, endpoint);
      StagedCapture javaCapture = capturePermissionPiped(List.of(javaExecutable(),
              "--enable-native-access=ALL-UNNAMED", "-Duser.home=" + javaHome,
              "-Dajent.terminal.fixedSize=" + COLUMNS + "x" + ROWS,
              "-jar", repository.resolve("ajent-cli/target/ajent.jar").toString()),
          javaWorkspace, javaHome, endpoint);

      assertCleanExit(nativeCapture.exitCode(), nativeCapture.output(), "native");
      assertCleanExit(javaCapture.exitCode(), javaCapture.output(), "Java");
      assertThat(requests).hasSize(4);

      assertMatchingRegion(nativeCapture.permissionFrame(), javaCapture.permissionFrame(),
          "E X E C U T E 1", "permission viewport");
      assertMatchingRegion(nativeCapture.finalFrame(), javaCapture.finalFrame(),
          "Bash  echo terminal parity", "completed tool viewport");
      assertMatchingRegion(nativeCapture.pickerFrame(), javaCapture.pickerFrame(),
          "Providers", "provider picker viewport");
      assertMatchingRegion(nativeCapture.movedPickerFrame(), javaCapture.movedPickerFrame(),
          "Providers", "navigated provider picker viewport");
    } finally {
      provider.stop(0);
    }
  }

  private static Capture capture(List<String> executable, Path workspace, Path home,
                                 String interaction, String expected)
      throws Exception {
    return capture(executable, workspace, home, "ollama", "qwen2.5-coder:7b",
        interaction, expected);
  }

  private static Capture capture(List<String> executable, Path workspace, Path home,
                                 String provider, String model,
                                 String interaction, String expected)
      throws Exception {
    var command = new ArrayList<>(executable);
    command.addAll(List.of("--provider", provider, "--model", model,
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
        drain(process.getInputStream(), output);
      } catch (java.io.IOException ignored) {
        // Closing the PTY during a normal shutdown may terminate the read first.
      }
    });
    Thread errorReader = Thread.startVirtualThread(() -> {
      try {
        drain(process.getErrorStream(), error);
      } catch (java.io.IOException ignored) {
        // Closing the PTY during a normal shutdown may terminate the read first.
      }
    });
    awaitWelcome(output, error, Duration.ofSeconds(8));
    awaitStableChrome(output, error, Duration.ofSeconds(5));
    if (!interaction.isEmpty() && process.isAlive()) {
      process.getOutputStream().write(interaction.getBytes(StandardCharsets.UTF_8));
      process.getOutputStream().flush();
      awaitViewportText(output, error, expected, Duration.ofSeconds(5));
    }
    String frame = combined(output, error);
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
    return new Capture(process.exitValue(), combined(output, error), frame);
  }

  private static Capture capturePiped(List<String> executable, Path workspace, Path home,
                                      String interaction, String expected)
      throws Exception {
    return capturePiped(executable, workspace, home, "ollama", "qwen2.5-coder:7b",
        interaction, expected);
  }

  private static Capture capturePiped(List<String> executable, Path workspace, Path home,
                                      String provider, String model,
                                      String interaction, String expected)
      throws Exception {
    var command = new ArrayList<>(executable);
    command.addAll(List.of("--provider", provider, "--model", model,
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
      try { drain(process.getInputStream(), output); }
      catch (java.io.IOException ignored) { }
    });
    awaitWelcome(output, new ByteArrayOutputStream(), Duration.ofSeconds(8));
    awaitStableChrome(output, new ByteArrayOutputStream(), Duration.ofSeconds(5));
    if (!interaction.isEmpty() && process.isAlive()) {
      process.getOutputStream().write(interaction.getBytes(StandardCharsets.UTF_8));
      process.getOutputStream().flush();
      awaitViewportText(output, new ByteArrayOutputStream(), expected, Duration.ofSeconds(5));
    }
    String frame = text(output);
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
    return new Capture(process.exitValue(), text(output), frame);
  }

  private static StagedCapture capturePermission(List<String> executable, Path workspace,
                                                  Path home, String provider)
      throws Exception {
    var command = new ArrayList<>(executable);
    command.addAll(List.of("--provider", provider, "--model", "parity-model",
        "--workspace", workspace.toString()));
    Map<String, String> environment = terminalEnvironment(home);
    PtyProcess process = new PtyProcessBuilder(command.toArray(String[]::new))
        .setDirectory(workspace.toString()).setEnvironment(environment)
        .setInitialColumns(COLUMNS).setInitialRows(ROWS).setConsole(false)
        .setRedirectErrorStream(true).start();
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();
    Thread reader = startDrain(process.getInputStream(), output);
    Thread errorReader = startDrain(process.getErrorStream(), error);
    return drivePermission(process, output, error, reader, errorReader, false);
  }

  private static StagedCapture capturePermissionPiped(List<String> executable, Path workspace,
                                                       Path home, String provider)
      throws Exception {
    var command = new ArrayList<>(executable);
    command.addAll(List.of("--provider", provider, "--model", "parity-model",
        "--workspace", workspace.toString()));
    ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile())
        .redirectErrorStream(true);
    builder.environment().putAll(terminalEnvironment(home));
    Process process = builder.start();
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();
    Thread reader = startDrain(process.getInputStream(), output);
    return drivePermission(process, output, error, reader, null, true);
  }

  private static StagedCapture drivePermission(Process process, ByteArrayOutputStream output,
      ByteArrayOutputStream error, Thread reader, Thread errorReader, boolean enhancedControlC)
      throws Exception {
    awaitWelcome(output, error, Duration.ofSeconds(8));
    awaitStableChrome(output, error, Duration.ofSeconds(5));
    process.getOutputStream().write("\u001b[Z".getBytes(StandardCharsets.US_ASCII));
    process.getOutputStream().flush();
    awaitViewportText(output, error, "A S K", Duration.ofSeconds(5));
    process.getOutputStream().write("test permission\r".getBytes(StandardCharsets.UTF_8));
    process.getOutputStream().flush();
    awaitAnyViewportText(output, error, List.of("Permission Required", "Allow tool:"),
        Duration.ofSeconds(8));
    String permissionFrame = combined(output, error);
    process.getOutputStream().write('y');
    process.getOutputStream().flush();
    awaitViewportText(output, error, "permission parity complete", Duration.ofSeconds(8));
    String finalFrame = combined(output, error);
    process.getOutputStream().write(16); // Ctrl+P
    process.getOutputStream().flush();
    awaitViewportText(output, error, "Providers", Duration.ofSeconds(5));
    Thread.sleep(150);
    String pickerFrame = combined(output, error);
    process.getOutputStream().write("\u001b[B".getBytes(StandardCharsets.US_ASCII));
    process.getOutputStream().flush();
    Thread.sleep(150);
    String movedPickerFrame = combined(output, error);
    byte[] closePicker = enhancedControlC
        ? "\u001b[27u".getBytes(StandardCharsets.US_ASCII)
        : new byte[] {27};
    process.getOutputStream().write(closePicker);
    process.getOutputStream().flush();
    Thread.sleep(400);
    if (process.isAlive()) {
      byte[] quit = enhancedControlC ? "\u001b[99;5u\r".getBytes(StandardCharsets.US_ASCII)
          : new byte[] {3};
      process.getOutputStream().write(quit);
      process.getOutputStream().flush();
      Thread.sleep(200);
      if (!enhancedControlC && process.isAlive()) {
        process.getOutputStream().write("Y\r".getBytes(StandardCharsets.US_ASCII));
        process.getOutputStream().flush();
      }
    }
    if (!process.waitFor(8, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(3, TimeUnit.SECONDS);
    }
    reader.join(Duration.ofSeconds(3));
    if (errorReader != null) errorReader.join(Duration.ofSeconds(3));
    return new StagedCapture(process.exitValue(), combined(output, error), permissionFrame,
        finalFrame, pickerFrame, movedPickerFrame);
  }

  private static Map<String, String> terminalEnvironment(Path home) {
    Map<String, String> environment = new HashMap<>(System.getenv());
    environment.put("APPDATA", home.toString());
    environment.put("LOCALAPPDATA", home.toString());
    environment.put("USERPROFILE", home.toString());
    environment.put("HOME", home.toString());
    environment.put("TERM", "xterm-256color");
    environment.put("COLUMNS", Integer.toString(COLUMNS));
    environment.put("LINES", Integer.toString(ROWS));
    return environment;
  }

  private static Thread startDrain(java.io.InputStream input, ByteArrayOutputStream output) {
    return Thread.startVirtualThread(() -> {
      try { drain(input, output); }
      catch (java.io.IOException ignored) { }
    });
  }

  private static void awaitWelcome(ByteArrayOutputStream output, ByteArrayOutputStream error,
                                   Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      String captured = combined(output, error);
      if (captured.contains(TAGLINE)) return;
      Thread.sleep(20);
    }
  }

  private static void awaitStableChrome(ByteArrayOutputStream output,
                                        ByteArrayOutputStream error, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      var viewport = new AnsiViewport(COLUMNS, ROWS);
      viewport.feed(combined(output, error));
      List<String> lines = viewport.lines();
      if (lines.stream().anyMatch(line -> line.contains(TAGLINE))
          && lines.stream().anyMatch(line -> line.contains("type a message"))
          && lines.stream().anyMatch(line -> line.contains("Ready"))) {
        Thread.sleep(100);
        return;
      }
      Thread.sleep(20);
    }
  }

  private static void awaitViewportText(ByteArrayOutputStream output,
                                        ByteArrayOutputStream error, String expected,
                                        Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      var viewport = new AnsiViewport(COLUMNS, ROWS);
      viewport.feed(combined(output, error));
      if (viewport.lines().stream()
          .map(NativeTerminalParityIT::withoutPaintedCursor)
          .anyMatch(line -> line.contains(expected))) {
        Thread.sleep(150);
        return;
      }
      Thread.sleep(20);
    }
  }

  private static void awaitAnyViewportText(ByteArrayOutputStream output,
      ByteArrayOutputStream error, List<String> expected, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      var viewport = new AnsiViewport(COLUMNS, ROWS);
      viewport.feed(combined(output, error));
      if (viewport.lines().stream().anyMatch(
          line -> expected.stream().anyMatch(line::contains))) {
        Thread.sleep(150);
        return;
      }
      Thread.sleep(20);
    }
  }

  private static String combined(ByteArrayOutputStream output, ByteArrayOutputStream error) {
    return text(output) + text(error);
  }

  private static String text(ByteArrayOutputStream output) {
    synchronized (output) {
      return output.toString(StandardCharsets.UTF_8);
    }
  }

  private static void drain(java.io.InputStream input, ByteArrayOutputStream output)
      throws java.io.IOException {
    byte[] buffer = new byte[8192];
    for (int read; (read = input.read(buffer)) >= 0;) {
      synchronized (output) {
        output.write(buffer, 0, read);
      }
    }
  }

  private static String withoutPaintedCursor(String value) {
    return value.replace("█", "");
  }

  private static void assertCleanExit(Capture capture, String label) {
    assertCleanExit(capture.exitCode(), capture.output(), label);
  }

  private static void assertCleanExit(int exitCode, String output, String label) {
    int windowsControlC = 0xc000013a;
    assertThat(exitCode).as("%s output:%n%s", label, output)
        .isIn(0, 130, windowsControlC);
    assertThat(output).doesNotContain("failed to initialize terminal");
  }

  private static HttpServer streamedTextProvider(List<String> requests) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      try (exchange) {
        requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String body = "data: {\"choices\":[{\"delta\":{\"content\":"
            + "\"terminal parity \"}}]}\n\n"
            + "data: {\"choices\":[{\"delta\":{\"content\":\"response\"}}]}\n\n"
            + "data: {\"usage\":{\"prompt_tokens\":21,\"completion_tokens\":3},"
            + "\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: [DONE]\n\n";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        for (int offset = 0; offset < bytes.length; offset += 11) {
          exchange.getResponseBody().write(bytes, offset, Math.min(11, bytes.length - offset));
          exchange.getResponseBody().flush();
        }
      }
    });
    return server;
  }

  private static HttpServer permissionProvider(List<String> requests) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      try (exchange) {
        String request = new String(exchange.getRequestBody().readAllBytes(),
            StandardCharsets.UTF_8);
        requests.add(request);
        String body;
        if (request.contains("\"role\":\"tool\"")) {
          body = "data: {\"choices\":[{\"delta\":{\"content\":"
              + "\"permission parity complete\"}}]}\n\n"
              + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
              + "data: [DONE]\n\n";
        } else {
          body = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
              + "\"id\":\"tc_bash_permission\",\"type\":\"function\",\"function\":{"
              + "\"name\":\"bash\",\"arguments\":"
              + "\"{\\\"command\\\":\\\"echo terminal parity\\\"}\"}}]}}]}\n\n"
              + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
              + "data: [DONE]\n\n";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    return server;
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

  private static List<String> stableRegion(List<String> lines) {
    int start = taglineRow(lines);
    int end = lines.size();
    while (end > start && lines.get(end - 1).isBlank()) end--;
    return lines.subList(start, end);
  }

  private static List<String> linesBeforeTagline(List<String> lines) {
    return lines.subList(0, taglineRow(lines));
  }

  private static List<String> regionFrom(List<String> lines, String anchor) {
    int start = 0;
    while (start < lines.size() && !lines.get(start).contains(anchor)) start++;
    if (start == lines.size()) {
      throw new AssertionError("anchor missing from viewport: " + anchor + "\n" + numbered(lines));
    }
    int end = lines.size();
    while (end > start && lines.get(end - 1).isBlank()) end--;
    return lines.subList(start, end);
  }

  private static void assertMatchingRegion(String nativeFrame, String javaFrame,
                                           String anchor, String description) {
    var nativeViewport = new AnsiViewport(COLUMNS, ROWS);
    nativeViewport.feed(nativeFrame);
    var javaViewport = new AnsiViewport(COLUMNS, ROWS);
    javaViewport.feed(javaFrame);
    assertThat(normalizeDynamicCells(regionFrom(javaViewport.lines(), anchor))).as(
            "%s native viewport:%n%s%nJava viewport:%n%s", description,
            numbered(nativeViewport.lines()), numbered(javaViewport.lines()))
        .containsExactlyElementsOf(normalizeDynamicCells(
            regionFrom(nativeViewport.lines(), anchor)));
  }

  private static List<String> normalizeDynamicCells(List<String> lines) {
    String[] spinner = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    return lines.stream().map(line -> {
      String normalized = line;
      if (line.contains("R U N N I N G")) {
        for (String frame : spinner) normalized = normalized.replace(frame, "⣿");
      }
      if (line.contains("approve ")) {
        normalized = normalized.replaceFirst("\\d+\\.\\ds", "#.#s");
      }
      if (line.contains("D O N E")) {
        normalized = normalized.replaceFirst(
            "\\d+(?:\\.\\d+)?(?:ms|s)\\s+(?=│$)", "#time ");
      }
      return normalized;
    }).toList();
  }

  private static int taglineRow(List<String> lines) {
    for (int index = 0; index < lines.size(); index++) {
      if (lines.get(index).contains(TAGLINE)) return index;
    }
    throw new AssertionError("tagline missing from viewport:\n" + numbered(lines));
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name, "").strip();
    if (value.isEmpty()) throw new IllegalStateException("missing system property: " + name);
    return value;
  }

  private record Capture(int exitCode, String output, String frame) {}
  private record StagedCapture(int exitCode, String output, String permissionFrame,
                               String finalFrame, String pickerFrame,
                               String movedPickerFrame) {}
}
