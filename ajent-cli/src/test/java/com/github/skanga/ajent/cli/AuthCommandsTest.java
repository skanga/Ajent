package com.github.skanga.ajent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.provider.auth.AnthropicOAuthLogin;
import com.github.skanga.ajent.provider.auth.Credential;
import com.github.skanga.ajent.provider.auth.CredentialStore;
import com.github.skanga.ajent.provider.auth.OAuthTokenClient;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AuthCommandsTest {
  @TempDir Path temporary;

  @Test void savesApiKeysAndHandlesEmptyInput() {
    var store = store();
    var commands = commands(store, Map.of(), successFlow(), new AtomicReference<>());
    Execution saved = login(commands, "2\nsk-ant-test  \n");
    assertThat(saved.code()).isZero();
    assertThat(saved.output()).contains("Saved API key to " + store.path());
    assertThat(store.load()).contains(new Credential.ApiKey("sk-ant-test"));
    Execution empty = login(commands, "key\n\n");
    assertThat(empty.code()).isEqualTo(1);
    assertThat(empty.error()).isEqualTo("No key entered.\n");
  }

  @Test void completesOAuthPersistsExpiryAndOpensBrowser() {
    var opened = new AtomicReference<URI>();
    var store = store();
    var commands = commands(store, Map.of(), successFlow(), opened);
    Execution execution = login(commands, "1\ncode#state\n");
    assertThat(execution.code()).isZero();
    assertThat(opened.get()).isEqualTo(URI.create("https://example.test/authorize"));
    assertThat(execution.output()).contains("Opening browser", "✓ Logged in");
    assertThat(store.load()).contains(new Credential.OAuth(
        "access", "refresh", 1_003_600_000L));
  }

  @Test void reportsExchangeFailureAndMissingCode() {
    var failedFlow = new AuthCommands.LoginFlow() {
      @Override public AnthropicOAuthLogin.Attempt newAttempt() { return attempt(); }
      @Override public OAuthTokenClient.Result exchange(String code, String verifier, String state) {
        return new OAuthTokenClient.Result.Failure(new OAuthTokenClient.Error(
            OAuthTokenClient.ErrorKind.API_ERROR, "authorization denied"));
      }
    };
    var commands = commands(store(), Map.of(), failedFlow, new AtomicReference<>());
    assertThat(login(commands, "1\n\n").error()).isEqualTo("No code entered.\n");
    Execution failed = login(commands, "1\ncode\n");
    assertThat(failed.code()).isEqualTo(1);
    assertThat(failed.error()).isEqualTo("Token exchange failed: authorization denied\n");
  }

  @Test void reportsStatusAndLogoutWithoutLeakingSecrets() {
    var store = store();
    store.save(new Credential.OAuth("secret-access", "secret-refresh", 999_000_000L));
    var commands = commands(store, Map.of("ANTHROPIC_API_KEY", "secret-key",
        "CLAUDE_CODE_OAUTH_TOKEN", "secret-env"), successFlow(), new AtomicReference<>());
    var output = new ByteArrayOutputStream();
    assertThat(commands.status(print(output))).isZero();
    String status = output.toString(StandardCharsets.UTF_8);
    assertThat(status).contains("ANTHROPIC_API_KEY: set", "CLAUDE_CODE_OAUTH_TOKEN: set",
        "Saved method: oauth", "Token: expired", "Refresh token: present")
        .doesNotContain("secret-access", "secret-refresh", "secret-key", "secret-env");
    output.reset();
    assertThat(commands.logout(print(output), print(new ByteArrayOutputStream()))).isZero();
    assertThat(output.toString(StandardCharsets.UTF_8)).contains("Removed " + store.path());
    output.reset();
    assertThat(commands.logout(print(output), print(new ByteArrayOutputStream()))).isZero();
    assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("No saved credentials.\n");
  }

  @Test void reportsApiKeyAndAllOAuthExpirationVariants() {
    var store = store();
    var commands = commands(store, Map.of(), successFlow(), new AtomicReference<>());
    store.save(new Credential.ApiKey("secret"));
    assertThat(status(commands)).isEqualTo("Credentials file: " + store.path()
        + "\nSaved method: api_key\n");
    store.save(new Credential.OAuth("access", "", 0));
    assertThat(status(commands)).contains("Token: no expiration info", "Refresh token: (none)");
    store.save(new Credential.OAuth("access", "refresh", 1_000_010_000L));
    assertThat(status(commands)).contains("Token expires in 10s");
    store.clear();
    assertThat(status(commands)).endsWith("Saved credentials: (none)\n");
    assertThat(login(commands, "api\nsk-ant-alias\n").code()).isZero();
  }

  private CredentialStore store() {
    return new CredentialStore(temporary.resolve("credentials.json"), "machine-seed");
  }
  private static AuthCommands commands(CredentialStore store, Map<String, String> environment,
                                       AuthCommands.LoginFlow flow, AtomicReference<URI> opened) {
    return new AuthCommands(store, environment, flow, opened::set, () -> 1_000_000_000L);
  }
  private static AuthCommands.LoginFlow successFlow() {
    return new AuthCommands.LoginFlow() {
      @Override public AnthropicOAuthLogin.Attempt newAttempt() { return attempt(); }
      @Override public OAuthTokenClient.Result exchange(String code, String verifier, String state) {
        assertThat(code).isEqualTo("code#state");
        return new OAuthTokenClient.Result.Success(
            new OAuthTokenClient.Token("access", "refresh", 3600));
      }
    };
  }
  private static AnthropicOAuthLogin.Attempt attempt() {
    return new AnthropicOAuthLogin.Attempt(
        "verifier", "state", URI.create("https://example.test/authorize"));
  }
  private static Execution login(AuthCommands commands, String input) {
    var output = new ByteArrayOutputStream(); var error = new ByteArrayOutputStream();
    int code = commands.login(new BufferedReader(new StringReader(input)), print(output), print(error));
    return new Execution(code, output.toString(StandardCharsets.UTF_8),
        error.toString(StandardCharsets.UTF_8));
  }
  private static PrintStream print(ByteArrayOutputStream output) {
    return new PrintStream(output, true, StandardCharsets.UTF_8);
  }
  private static String status(AuthCommands commands) {
    var output = new ByteArrayOutputStream(); commands.status(print(output));
    return output.toString(StandardCharsets.UTF_8);
  }
  private record Execution(int code, String output, String error) {}
}
