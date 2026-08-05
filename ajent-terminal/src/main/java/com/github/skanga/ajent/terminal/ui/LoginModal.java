package com.github.skanga.ajent.terminal.ui;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/** Immutable port of Ajent's in-app authentication modal reducer. */
public final class LoginModal {
  private LoginModal() {}

  public sealed interface State permits Closed, Picking, OAuthCode, OAuthExchanging,
      ApiKeyInput, CustomHostInput, Failed {}

  public record Closed() implements State {}
  public record Picking() implements State {}
  public record OAuthExchanging() implements State {}

  public record OAuthCode(
      String verifier, String oauthState, URI authorizeUri, Utf8Editor code) implements State {
    public OAuthCode {
      Objects.requireNonNull(verifier, "verifier");
      Objects.requireNonNull(oauthState, "oauthState");
      Objects.requireNonNull(authorizeUri, "authorizeUri");
      Objects.requireNonNull(code, "code");
    }
  }

  public record ApiKeyInput(Utf8Editor key, String provider, String providerLabel)
      implements State {
    public ApiKeyInput {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(provider, "provider");
      Objects.requireNonNull(providerLabel, "providerLabel");
    }

    public ApiKeyInput() { this(new Utf8Editor(), "", ""); }
  }

  public record CustomHostInput(Utf8Editor host) implements State {
    public CustomHostInput { Objects.requireNonNull(host, "host"); }
    public CustomHostInput() { this(new Utf8Editor()); }
  }

  public record Failed(String message) implements State {
    public Failed { Objects.requireNonNull(message, "message"); }
  }

  public record OAuthAttempt(String verifier, String state, URI authorizeUri) {
    public OAuthAttempt {
      Objects.requireNonNull(verifier, "verifier");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(authorizeUri, "authorizeUri");
    }
  }

  @FunctionalInterface
  public interface OAuthAttemptFactory {
    OAuthAttempt create();
  }

  public sealed interface Action permits OpenBrowser, ExchangeOAuth, InstallAnthropicKey,
      InstallProviderKey, SwitchCustomHost {}

  public record OpenBrowser(URI uri) implements Action {
    public OpenBrowser { Objects.requireNonNull(uri, "uri"); }
  }

  public record ExchangeOAuth(String code, String verifier, String state) implements Action {
    public ExchangeOAuth {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(verifier, "verifier");
      Objects.requireNonNull(state, "state");
    }
  }

  public record InstallAnthropicKey(String key) implements Action {
    public InstallAnthropicKey { Objects.requireNonNull(key, "key"); }
  }

  public record InstallProviderKey(String provider, String providerLabel, String key)
      implements Action {
    public InstallProviderKey {
      Objects.requireNonNull(provider, "provider");
      Objects.requireNonNull(providerLabel, "providerLabel");
      Objects.requireNonNull(key, "key");
    }
  }

  public record SwitchCustomHost(String specification) implements Action {
    public SwitchCustomHost { Objects.requireNonNull(specification, "specification"); }
  }

  public record Transition(State state, Optional<Action> action) {
    public Transition {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(action, "action");
    }

    public Transition(State state) { this(state, Optional.empty()); }
    public Transition(State state, Action action) { this(state, Optional.of(action)); }
  }

  public static boolean isOpen(State state) {
    return !(Objects.requireNonNull(state, "state") instanceof Closed);
  }

  public static boolean isInputState(State state) {
    Objects.requireNonNull(state, "state");
    return state instanceof OAuthCode || state instanceof ApiKeyInput
        || state instanceof CustomHostInput;
  }

  public static State open() { return new Picking(); }
  public static State close(State ignored) { return new Closed(); }

  public static Transition pick(State state, int codePoint, OAuthAttemptFactory factory) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(factory, "factory");
    if (!(state instanceof Picking || state instanceof Failed)) return new Transition(state);
    if (codePoint == '1') {
      OAuthAttempt attempt = Objects.requireNonNull(factory.create(), "attempt");
      var next = new OAuthCode(attempt.verifier(), attempt.state(), attempt.authorizeUri(),
          new Utf8Editor());
      return new Transition(next, new OpenBrowser(attempt.authorizeUri()));
    }
    if (codePoint == '2') return new Transition(new ApiKeyInput());
    return new Transition(state);
  }

  public static State input(State state, int codePoint) {
    return edit(state, editor -> editor.insertCodePoint(codePoint));
  }

  public static State paste(State state, String text) {
    Objects.requireNonNull(text, "text");
    return edit(state, editor -> editor.insert(text));
  }

  public static State backspace(State state) { return edit(state, Utf8Editor::backspace); }
  public static State left(State state) { return edit(state, Utf8Editor::left); }
  public static State right(State state) { return edit(state, Utf8Editor::right); }

  public static Transition submit(State state) {
    Objects.requireNonNull(state, "state");
    if (state instanceof CustomHostInput custom) {
      String specification = trimTrailingAsciiWhitespace(custom.host().text());
      if (specification.startsWith("http://")) specification = specification.substring(7);
      else if (specification.startsWith("https://")) specification = specification.substring(8);
      int slash = specification.indexOf('/');
      if (slash >= 0) specification = specification.substring(0, slash);
      return specification.isEmpty() ? new Transition(new Failed("no host entered"))
          : new Transition(new Closed(), new SwitchCustomHost(specification));
    }
    if (state instanceof ApiKeyInput apiKey) {
      String key = trimTrailingAsciiWhitespace(apiKey.key().text());
      if (key.isEmpty()) return new Transition(new Failed("no key entered"));
      Action action = apiKey.provider().isEmpty() ? new InstallAnthropicKey(key)
          : new InstallProviderKey(apiKey.provider(), apiKey.providerLabel(), key);
      return new Transition(new Closed(), action);
    }
    if (state instanceof OAuthCode oauth) {
      String code = trimTrailingAsciiWhitespace(oauth.code().text());
      if (code.isEmpty()) {
        return new Transition(new OAuthCode(oauth.verifier(), oauth.oauthState(),
            oauth.authorizeUri(), new Utf8Editor()));
      }
      return new Transition(new OAuthExchanging(),
          new ExchangeOAuth(code, oauth.verifier(), oauth.oauthState()));
    }
    return new Transition(state);
  }

  private static State edit(State state, java.util.function.UnaryOperator<Utf8Editor> change) {
    Objects.requireNonNull(state, "state");
    if (state instanceof OAuthCode oauth) return new OAuthCode(oauth.verifier(), oauth.oauthState(),
        oauth.authorizeUri(), change.apply(oauth.code()));
    if (state instanceof ApiKeyInput apiKey) return new ApiKeyInput(change.apply(apiKey.key()),
        apiKey.provider(), apiKey.providerLabel());
    if (state instanceof CustomHostInput custom) return new CustomHostInput(
        change.apply(custom.host()));
    return state;
  }

  private static String trimTrailingAsciiWhitespace(String value) {
    int end = value.length();
    while (end > 0) {
      char character = value.charAt(end - 1);
      if (character != '\r' && character != '\n' && character != ' '
          && character != '\t') break;
      end--;
    }
    return value.substring(0, end);
  }
}
