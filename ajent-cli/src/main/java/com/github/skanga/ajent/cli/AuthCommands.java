package com.github.skanga.ajent.cli;

import com.github.skanga.ajent.provider.auth.AnthropicOAuthLogin;
import com.github.skanga.ajent.provider.EnvironmentHttpClient;
import com.github.skanga.ajent.provider.auth.Credential;
import com.github.skanga.ajent.provider.auth.CredentialStore;
import com.github.skanga.ajent.provider.auth.OAuthTokenClient;
import com.github.skanga.ajent.provider.codex.CodexAuthImporter;
import com.github.skanga.ajent.provider.codex.CodexCredentialStore;
import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Blocking native-compatible login, logout, and credential-status commands. */
public final class AuthCommands {
  private static final System.Logger LOGGER = System.getLogger(AuthCommands.class.getName());

  interface LoginFlow {
    AnthropicOAuthLogin.Attempt newAttempt();
    OAuthTokenClient.Result exchange(String code, String verifier, String state);
  }

  private final CredentialStore store;
  private final Map<String, String> environment;
  private final LoginFlow loginFlow;
  private final Consumer<URI> browser;
  private final LongSupplier clock;
  private final CodexCredentialStore codexStore;

  public static AuthCommands systemDefault() {
    var oauth = new AnthropicOAuthLogin(EnvironmentHttpClient.createOAuth(System.getenv()));
    return new AuthCommands(CredentialStore.systemDefault(), System.getenv(), new LoginFlow() {
      @Override public AnthropicOAuthLogin.Attempt newAttempt() { return oauth.newAttempt(); }
      @Override public OAuthTokenClient.Result exchange(String code, String verifier, String state) {
        return oauth.exchange(code, verifier, state);
      }
    }, AuthCommands::openBrowser, System::currentTimeMillis,
        CodexCredentialStore.systemDefault());
  }

  AuthCommands(CredentialStore store, Map<String, String> environment, LoginFlow loginFlow,
               Consumer<URI> browser, LongSupplier clock) {
    this(store, environment, loginFlow, browser, clock, CodexCredentialStore.systemDefault());
  }

  AuthCommands(CredentialStore store, Map<String, String> environment, LoginFlow loginFlow,
               Consumer<URI> browser, LongSupplier clock, CodexCredentialStore codexStore) {
    this.store = Objects.requireNonNull(store, "store");
    this.environment = Map.copyOf(environment);
    this.loginFlow = Objects.requireNonNull(loginFlow, "loginFlow");
    this.browser = Objects.requireNonNull(browser, "browser");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.codexStore = Objects.requireNonNull(codexStore, "codexStore");
  }

  public int loginCodex(PrintStream output, PrintStream error) {
    Path home = Path.of(System.getProperty("user.home"));
    return switch (CodexAuthImporter.inspect(environment, home)) {
      case CodexAuthImporter.Result.Available available -> {
        if (!codexStore.save(available.credentials())) {
          error.print("Failed to save imported Codex credentials.\n");
          yield 1;
        }
        output.print("Imported Codex CLI login from " + available.source()
            + "\nSaved an encrypted Ajent copy to " + codexStore.path() + "\n");
        yield 0;
      }
      case CodexAuthImporter.Result.Keyring keyring -> {
        error.print("Codex stores this login in the OS keyring (" + keyring.config()
            + "). Ajent cannot explicitly import keyring secrets. Configure "
            + "cli_auth_credentials_store = \"file\", run `codex login`, then retry.\n");
        yield 1;
      }
      case CodexAuthImporter.Result.Missing missing -> {
        error.print("No Codex CLI login found at " + missing.expected()
            + ". Run `codex login`, then retry.\n");
        yield 1;
      }
      case CodexAuthImporter.Result.Invalid invalid -> {
        error.print("Cannot import " + invalid.source() + ": " + invalid.reason() + ".\n");
        yield 1;
      }
    };
  }

  public int logoutCodex(PrintStream output, PrintStream error) {
    if (codexStore.load().isEmpty()) {
      output.print("No saved Codex credentials.\n");
      return 0;
    }
    if (!codexStore.clear()) {
      error.print("Failed to remove " + codexStore.path() + "\n");
      return 1;
    }
    output.print("Removed " + codexStore.path() + "\n");
    return 0;
  }

  public int statusCodex(PrintStream output) {
    output.print("Codex credentials file: " + codexStore.path() + "\n");
    var saved = codexStore.load();
    if (saved.isEmpty()) {
      output.print("Saved Codex credentials: (none)\n");
      return 0;
    }
    var credentials = saved.orElseThrow();
    output.print("Saved method: chatgpt_subscription\n");
    if (credentials.expiresAtMillis() == 0) {
      output.print("Token: no expiration info\n");
    } else {
      long remaining = (credentials.expiresAtMillis() - clock.getAsLong()) / 1000;
      output.print(remaining <= 0 ? "Token: expired\n"
          : "Token expires in " + remaining + "s\n");
    }
    output.print(credentials.refreshToken().isEmpty()
        ? "Refresh token: (none)\n" : "Refresh token: present\n");
    return 0;
  }

  public int login(BufferedReader input, PrintStream output, PrintStream error) {
    output.print("ajent — authenticate with Claude\n\n"
        + "  1) OAuth via claude.ai (Pro/Max subscription)\n"
        + "  2) Paste an Anthropic API key (sk-ant-...)\n\nChoice [1/2]: ");
    String choice = read(input).toLowerCase(Locale.ROOT);
    if (choice.equals("2") || choice.equals("api") || choice.equals("key")) {
      output.print("\nPaste API key: ");
      String key = stripTrailing(read(input));
      if (key.isEmpty()) { error.print("No key entered.\n"); return 1; }
      if (!store.save(new Credential.ApiKey(key))) {
        error.print("Failed to save credentials.\n"); return 1;
      }
      output.print("Saved API key to " + store.path() + "\n");
      return 0;
    }

    AnthropicOAuthLogin.Attempt attempt = loginFlow.newAttempt();
    output.print("\nOpening browser to authorize ajent...\n" + attempt.authorizationUri() + "\n\n");
    try { browser.accept(attempt.authorizationUri()); } catch (RuntimeException ignored) {}
    output.print("After logging in, paste the code shown on the callback page: ");
    String code = stripTrailing(read(input));
    if (code.isEmpty()) { error.print("No code entered.\n"); return 1; }
    OAuthTokenClient.Result exchanged = loginFlow.exchange(code, attempt.verifier(), attempt.state());
    if (exchanged instanceof OAuthTokenClient.Result.Failure failure) {
      error.print("Token exchange failed: " + failure.error().detail() + "\n"); return 1;
    }
    OAuthTokenClient.Token token = ((OAuthTokenClient.Result.Success) exchanged).token();
    long expiresAt = token.expiresInSeconds() == 0
        ? 0 : clock.getAsLong() + token.expiresInSeconds() * 1000;
    if (!store.save(new Credential.OAuth(
        token.accessToken(), token.refreshToken(), expiresAt))) {
      error.print("Failed to save credentials.\n"); return 1;
    }
    output.print("\n✓ Logged in. Saved to " + store.path() + "\n");
    return 0;
  }

  public int logout(PrintStream output, PrintStream error) {
    if (store.load().isEmpty()) { output.print("No saved credentials.\n"); return 0; }
    if (!store.clear()) { error.print("Failed to remove " + store.path() + "\n"); return 1; }
    output.print("Removed " + store.path() + "\n"); return 0;
  }

  public int status(PrintStream output) {
    output.print("Credentials file: " + store.path() + "\n");
    if (!environment.getOrDefault("ANTHROPIC_API_KEY", "").isEmpty())
      output.print("ANTHROPIC_API_KEY: set (will be used, overrides file)\n");
    if (!environment.getOrDefault("CLAUDE_CODE_OAUTH_TOKEN", "").isEmpty())
      output.print("CLAUDE_CODE_OAUTH_TOKEN: set (OAuth via env)\n");
    var saved = store.load();
    if (saved.isEmpty()) { output.print("Saved credentials: (none)\n"); return 0; }
    switch (saved.orElseThrow()) {
      case Credential.ApiKey ignored -> output.print("Saved method: api_key\n");
      case Credential.OAuth oauth -> {
        output.print("Saved method: oauth\n");
        if (oauth.expiresAtMillis() == 0) output.print("Token: no expiration info\n");
        else {
          long remaining = (oauth.expiresAtMillis() - clock.getAsLong()) / 1000;
          output.print(remaining <= 0 ? "Token: expired\n" : "Token expires in " + remaining + "s\n");
        }
        output.print(oauth.refreshToken().isEmpty()
            ? "Refresh token: (none)\n" : "Refresh token: present\n");
      }
      case Credential.None ignored -> output.print("Saved credentials: (none)\n");
    }
    return 0;
  }

  private static String read(BufferedReader input) {
    try { String line = input.readLine(); return line == null ? "" : line; }
    catch (IOException exception) { return ""; }
  }
  private static String stripTrailing(String value) { return value.stripTrailing(); }
  private static void openBrowser(URI uri) {
    if (!Desktop.isDesktopSupported()) return;
    try {
      Desktop.getDesktop().browse(uri);
    } catch (IOException | UnsupportedOperationException exception) {
      LOGGER.log(System.Logger.Level.DEBUG, "Could not open OAuth URL", exception);
    }
  }
}
