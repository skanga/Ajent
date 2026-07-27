package com.github.skanga.ajent.provider.auth;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Ajent's machine-and-user-bound credential key seed. */
public final class MachineSeed {
  private static final String INFO = "ajent-credentials-v1";
  private static final char SEPARATOR = '\u001f';
  private static final Pattern MACHINE_GUID = Pattern.compile(
      "(?im)^\\s*MachineGuid\\s+REG_SZ\\s+(.+?)\\s*$");

  private MachineSeed() {}

  public static String current() {
    Map<String, String> environment = System.getenv();
    if (System.getProperty("os.name", "").startsWith("Windows")) {
      return windows(readMachineGuid(), hostName(environment), environment);
    }
    return unix(readMachineId(), hostName(environment), unixUid());
  }

  static String windows(
      Optional<String> machineGuid,
      Optional<String> hostName,
      Map<String, String> environment) {
    StringBuilder seed = new StringBuilder(machineGuid.or(() -> hostName).orElse(""));
    if (environment.containsKey("USERNAME")) {
      seed.append(SEPARATOR).append(environment.get("USERNAME"));
    }
    if (seed.isEmpty()) seed.append("ajent-fallback-seed");
    return seed.append(SEPARATOR).append(INFO).toString();
  }

  static String unix(Optional<String> machineId, Optional<String> hostName, long uid) {
    String seed = machineId.or(() -> hostName).orElse("");
    return seed + SEPARATOR + Long.toUnsignedString(uid) + SEPARATOR + INFO;
  }

  private static Optional<String> readMachineGuid() {
    Process process = null;
    try {
      process = new ProcessBuilder("reg.exe", "query",
          "HKLM\\SOFTWARE\\Microsoft\\Cryptography",
          "/v", "MachineGuid", "/reg:64").redirectErrorStream(true).start();
      if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return Optional.empty();
      }
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
      var match = MACHINE_GUID.matcher(output);
      return match.find() ? Optional.of(match.group(1).strip()) : Optional.empty();
    } catch (IOException exception) {
      return Optional.empty();
    } catch (InterruptedException exception) {
      java.lang.Thread.currentThread().interrupt();
      return Optional.empty();
    } finally {
      if (process != null) {
        try {
          process.getInputStream().close();
        } catch (IOException ignored) {
          // The process has already exited or been destroyed.
        }
      }
    }
  }

  private static Optional<String> readMachineId() {
    for (Path path : new Path[] {Path.of("/etc/machine-id"), Path.of("/var/lib/dbus/machine-id")}) {
      try {
        String value = Files.readString(path, StandardCharsets.UTF_8).lines()
            .findFirst().orElse("").strip();
        if (!value.isEmpty()) return Optional.of(value);
      } catch (IOException ignored) {
        // Try the next platform identity location.
      }
    }
    return Optional.empty();
  }

  private static Optional<String> hostName(Map<String, String> environment) {
    String computer = environment.getOrDefault("COMPUTERNAME", "");
    if (!computer.isEmpty()) return Optional.of(computer);
    try {
      String name = InetAddress.getLocalHost().getHostName();
      return name.isEmpty() ? Optional.empty() : Optional.of(name);
    } catch (IOException exception) {
      return Optional.empty();
    }
  }

  private static long unixUid() {
    try {
      Object value = Files.getAttribute(Path.of("."), "unix:uid");
      return value instanceof Number number ? number.longValue() : 0;
    } catch (IOException | UnsupportedOperationException exception) {
      return 0;
    }
  }
}
