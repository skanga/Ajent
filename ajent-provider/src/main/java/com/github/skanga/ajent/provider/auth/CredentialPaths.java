package com.github.skanga.ajent.provider.auth;

import java.nio.file.Path;
import java.util.Map;

/** AgenTTY credential location resolution. */
public final class CredentialPaths {
  private CredentialPaths() {}

  public static Path credentialsPath(Map<String, String> environment, Path workingDirectory) {
    String xdg = environment.getOrDefault("XDG_CONFIG_HOME", "");
    Path base;
    if (!xdg.isEmpty()) {
      base = Path.of(xdg);
    } else {
      String home = environment.getOrDefault("HOME", "");
      if (home.isEmpty()) home = environment.getOrDefault("USERPROFILE", "");
      base = home.isEmpty() ? workingDirectory.resolve(".config") : Path.of(home).resolve(".config");
    }
    return base.resolve("agentty").resolve("credentials.json");
  }

  public static Path systemCredentialsPath() {
    return credentialsPath(System.getenv(), Path.of("").toAbsolutePath());
  }
}
