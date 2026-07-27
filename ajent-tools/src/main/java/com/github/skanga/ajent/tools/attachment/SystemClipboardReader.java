package com.github.skanga.ajent.tools.attachment;

import com.github.skanga.ajent.domain.Attachment;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

/** Platform clipboard reader with AgenTTY's command override and binary size bound. */
public final class SystemClipboardReader implements ClipboardReader {
  static final int CAP = 8 * 1024 * 1024;
  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);

  interface DesktopAccess {
    Optional<Attachment> image();
    Optional<String> text();
  }

  @FunctionalInterface interface CommandCapture {
    Optional<byte[]> run(List<String> command);
  }

  private enum Platform { WINDOWS, MAC, UNIX }

  private final Map<String, String> environment;
  private final Platform platform;
  private final DesktopAccess desktop;
  private final CommandCapture capture;

  public SystemClipboardReader(Map<String, String> environment) {
    this(environment, System.getProperty("os.name", ""), desktopAccess(),
        command -> capture(command, COMMAND_TIMEOUT));
  }

  SystemClipboardReader(Map<String, String> environment, String operatingSystem,
      DesktopAccess desktop, CommandCapture capture) {
    this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    String os = Objects.requireNonNull(operatingSystem, "operatingSystem")
        .toLowerCase(Locale.ROOT);
    platform = os.contains("win") ? Platform.WINDOWS
        : os.contains("mac") ? Platform.MAC : Platform.UNIX;
    this.desktop = Objects.requireNonNull(desktop, "desktop");
    this.capture = Objects.requireNonNull(capture, "capture");
  }

  @Override public Optional<Attachment> image() {
    String override = environment.getOrDefault("AJENT_CLIPBOARD_CMD", "");
    if (!override.isBlank()) {
      return capture.run(shell(override))
          .flatMap(bytes -> ImagePaste.raw(bytes, "<clipboard>"));
    }
    return desktop.image().or(() -> imageCommands().stream()
        .map(capture::run).flatMap(Optional::stream)
        .map(bytes -> ImagePaste.raw(bytes, "<clipboard>"))
        .flatMap(Optional::stream).findFirst());
  }

  @Override public Optional<String> text() {
    return desktop.text().filter(value -> !value.isEmpty()).or(() -> textCommands().stream()
        .map(capture::run).flatMap(Optional::stream).filter(bytes -> bytes.length > 0)
        .map(bytes -> new String(bytes, StandardCharsets.UTF_8)).findFirst());
  }

  private List<List<String>> imageCommands() {
    return switch (platform) {
      case WINDOWS -> List.of();
      case MAC -> List.of(List.of("pngpaste", "-"));
      case UNIX -> List.of(
          List.of("wl-paste", "--type", "image/png"),
          List.of("xclip", "-selection", "clipboard", "-t", "image/png", "-o"));
    };
  }

  private List<List<String>> textCommands() {
    return switch (platform) {
      case WINDOWS -> List.of();
      case MAC -> List.of(List.of("pbpaste"));
      case UNIX -> List.of(
          List.of("wl-paste", "--no-newline"),
          List.of("xclip", "-selection", "clipboard", "-o"));
    };
  }

  private List<String> shell(String command) {
    return platform == Platform.WINDOWS
        ? List.of("cmd.exe", "/d", "/s", "/c", command)
        : List.of("/bin/sh", "-c", command);
  }

  static Optional<byte[]> capture(List<String> command, Duration timeout) {
    Process process = null;
    try {
      process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      Process started = process;
      var output = new AtomicReference<byte[]>();
      Thread reader = Thread.startVirtualThread(() -> {
        try { output.set(started.getInputStream().readNBytes(CAP + 1)); }
        catch (IOException ignored) { }
      });
      boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!completed) process.destroyForcibly();
      reader.join(1_000);
      byte[] bytes = output.get();
      return completed && process.exitValue() == 0 && bytes != null
          && bytes.length > 0 && bytes.length <= CAP ? Optional.of(bytes) : Optional.empty();
    } catch (IOException | InterruptedException | RuntimeException exception) {
      if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
      return Optional.empty();
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
    }
  }

  private static DesktopAccess desktopAccess() {
    return new DesktopAccess() {
      @Override public Optional<Attachment> image() {
        try {
          var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
          Image source = (Image) clipboard.getData(DataFlavor.imageFlavor);
          var converted = new BufferedImage(
              source.getWidth(null), source.getHeight(null), BufferedImage.TYPE_INT_ARGB);
          Graphics2D graphics = converted.createGraphics();
          try { graphics.drawImage(source, 0, 0, null); }
          finally { graphics.dispose(); }
          var output = new ByteArrayOutputStream();
          ImageIO.write(converted, "png", output);
          return output.size() <= CAP
              ? ImagePaste.raw(output.toByteArray(), "<clipboard>") : Optional.empty();
        } catch (Exception | LinkageError exception) {
          return Optional.empty();
        }
      }

      @Override public Optional<String> text() {
        try {
          Object value = Toolkit.getDefaultToolkit().getSystemClipboard()
              .getData(DataFlavor.stringFlavor);
          return value instanceof String text ? Optional.of(text) : Optional.empty();
        } catch (Exception | LinkageError exception) {
          return Optional.empty();
        }
      }
    };
  }
}
