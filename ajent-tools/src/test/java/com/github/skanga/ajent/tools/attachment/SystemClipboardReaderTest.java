package com.github.skanga.ajent.tools.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.Attachment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SystemClipboardReaderTest {
  private static final byte[] PNG = {
      (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};

  @Test void commandOverrideIsAuthoritativeAndUsesThePlatformShell() {
    var commands = new ArrayList<List<String>>();
    var desktop = desktop(image("desktop"), Optional.of("desktop text"));
    var windows = new SystemClipboardReader(
        Map.of("AGENTTY_CLIPBOARD_CMD", "get-image"), "Windows 11", desktop,
        command -> { commands.add(command); return Optional.of(PNG); });

    assertThat(windows.image()).get().extracting(Attachment::path).isEqualTo("<clipboard>");
    assertThat(commands).containsExactly(
        List.of("cmd.exe", "/d", "/s", "/c", "get-image"));

    var unix = new SystemClipboardReader(
        Map.of("AGENTTY_CLIPBOARD_CMD", "get-image"), "Linux", desktop,
        command -> { commands.add(command); return Optional.of("bad".getBytes()); });
    assertThat(unix.image()).isEmpty();
    assertThat(commands.getLast()).isEqualTo(List.of("/bin/sh", "-c", "get-image"));
  }

  @Test void desktopContentWinsAndEmptyDesktopTextFallsThrough() {
    var commands = new ArrayList<List<String>>();
    var reader = new SystemClipboardReader(Map.of(), "Windows",
        desktop(image("desktop"), Optional.of("ready")),
        command -> { commands.add(command); return Optional.empty(); });

    assertThat(reader.image()).get().extracting(Attachment::path).isEqualTo("desktop");
    assertThat(reader.text()).contains("ready");
    assertThat(commands).isEmpty();

    var empty = new SystemClipboardReader(Map.of(), "Windows",
        desktop(Optional.empty(), Optional.of("")), command -> Optional.empty());
    assertThat(empty.image()).isEmpty();
    assertThat(empty.text()).isEmpty();
  }

  @Test void unixAndMacFallbackCommandsContinueUntilUsableContent() {
    var unixCommands = new ArrayList<List<String>>();
    var unix = new SystemClipboardReader(Map.of(), "Linux", desktop(Optional.empty(), Optional.empty()),
        command -> {
          unixCommands.add(command);
          if (command.contains("image/png")) {
            return command.getFirst().equals("xclip") ? Optional.of(PNG)
                : Optional.of("not-image".getBytes());
          }
          return command.getFirst().equals("xclip") ? Optional.of("fallback text".getBytes())
              : Optional.empty();
        });
    assertThat(unix.image()).get().extracting(Attachment::mediaType).isEqualTo("image/png");
    assertThat(unix.text()).contains("fallback text");
    assertThat(unixCommands).contains(
        List.of("wl-paste", "--type", "image/png"),
        List.of("xclip", "-selection", "clipboard", "-t", "image/png", "-o"),
        List.of("wl-paste", "--no-newline"),
        List.of("xclip", "-selection", "clipboard", "-o"));

    var macCommands = new ArrayList<List<String>>();
    var mac = new SystemClipboardReader(Map.of(), "Mac OS X",
        desktop(Optional.empty(), Optional.empty()), command -> {
          macCommands.add(command);
          return command.getFirst().equals("pngpaste") ? Optional.of(PNG)
              : Optional.of("mac text".getBytes());
        });
    assertThat(mac.image()).isPresent();
    assertThat(mac.text()).contains("mac text");
    assertThat(macCommands).containsExactly(List.of("pngpaste", "-"), List.of("pbpaste"));
  }

  @Test void binaryCaptureHandlesSuccessLaunchFailureNonzeroAndTimeout() {
    assertThat(SystemClipboardReader.capture(
        List.of("git", "--version"), Duration.ofSeconds(5)))
        .hasValueSatisfying(bytes -> assertThat(bytes).isNotEmpty());
    assertThat(SystemClipboardReader.capture(
        List.of("ajent-command-that-does-not-exist"), Duration.ofMillis(100))).isEmpty();
    assertThat(SystemClipboardReader.capture(
        List.of("git", "not-an-ajent-command"), Duration.ofSeconds(5))).isEmpty();

    boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    List<String> sleeper = windows
        ? List.of("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 2")
        : List.of("/bin/sh", "-c", "sleep 2");
    assertThat(SystemClipboardReader.capture(sleeper, Duration.ofMillis(50))).isEmpty();
  }

  private static Optional<Attachment> image(String path) {
    return ImagePaste.raw(PNG, path);
  }

  private static SystemClipboardReader.DesktopAccess desktop(
      Optional<Attachment> image, Optional<String> text) {
    return new SystemClipboardReader.DesktopAccess() {
      @Override public Optional<Attachment> image() { return image; }
      @Override public Optional<String> text() { return text; }
    };
  }
}
