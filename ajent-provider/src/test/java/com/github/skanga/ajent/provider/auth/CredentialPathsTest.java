package com.github.skanga.ajent.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CredentialPathsTest {

  @Test
  void xdgPathTakesPriority() {
    assertThat(CredentialPaths.credentialsPath(
        Map.of("XDG_CONFIG_HOME", "C:/config", "HOME", "C:/home"), Path.of("C:/cwd")))
        .isEqualTo(Path.of("C:/config/agentty/credentials.json"));
  }

  @Test
  void homeThenUserProfileThenWorkingDirectoryFollowAgenTTY() {
    assertThat(CredentialPaths.credentialsPath(Map.of("HOME", "C:/home"), Path.of("C:/cwd")))
        .isEqualTo(Path.of("C:/home/.config/agentty/credentials.json"));
    assertThat(CredentialPaths.credentialsPath(
        Map.of("USERPROFILE", "C:/users/alice"), Path.of("C:/cwd")))
        .isEqualTo(Path.of("C:/users/alice/.config/agentty/credentials.json"));
    assertThat(CredentialPaths.credentialsPath(Map.of(), Path.of("C:/cwd")))
        .isEqualTo(Path.of("C:/cwd/.config/agentty/credentials.json"));
    assertThat(CredentialPaths.systemCredentialsPath().toString())
        .endsWith(Path.of("agentty", "credentials.json").toString());
  }
}
