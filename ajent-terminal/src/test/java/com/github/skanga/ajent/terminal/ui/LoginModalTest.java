package com.github.skanga.ajent.terminal.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

final class LoginModalTest {
  private static final LoginModal.OAuthAttempt ATTEMPT = new LoginModal.OAuthAttempt(
      "verifier", "state", URI.create("https://example.test/authorize"));

  @Test void opensClosesAndPicksBothAuthenticationMethods() {
    LoginModal.State state = LoginModal.open();
    assertThat(state).isEqualTo(new LoginModal.Picking());
    var oauth = LoginModal.pick(state, '1', () -> ATTEMPT);
    assertThat(oauth.state()).isEqualTo(new LoginModal.OAuthCode(
        "verifier", "state", ATTEMPT.authorizeUri(), new Utf8Editor()));
    assertThat(oauth.action()).contains(new LoginModal.OpenBrowser(ATTEMPT.authorizeUri()));
    assertThat(LoginModal.pick(new LoginModal.Failed("retry"), '2', () -> ATTEMPT).state())
        .isEqualTo(new LoginModal.ApiKeyInput());
    assertThat(LoginModal.close(oauth.state())).isEqualTo(new LoginModal.Closed());
  }

  @Test void preservesNativeUtf8ByteCursorEditing() {
    LoginModal.State state = new LoginModal.ApiKeyInput();
    state = LoginModal.input(state, 'a');
    state = LoginModal.input(state, 0x1f642);
    state = LoginModal.input(state, 'b');
    assertThat(((LoginModal.ApiKeyInput) state).key()).isEqualTo(new Utf8Editor("a🙂b", 6));
    state = LoginModal.left(state);
    state = LoginModal.left(state);
    assertThat(((LoginModal.ApiKeyInput) state).key().cursor()).isEqualTo(1);
    state = LoginModal.backspace(state);
    state = LoginModal.paste(state, "é");
    assertThat(((LoginModal.ApiKeyInput) state).key()).isEqualTo(new Utf8Editor("é🙂b", 2));
    assertThat(LoginModal.right(state)).isEqualTo(
        new LoginModal.ApiKeyInput(new Utf8Editor("é🙂b", 6), "", ""));
  }

  @Test void ignoresTextEditingOutsideInputStates() {
    LoginModal.State state = new LoginModal.Picking();
    assertThat(LoginModal.input(state, 'x')).isSameAs(state);
    assertThat(LoginModal.paste(state, "x")).isSameAs(state);
    assertThat(LoginModal.backspace(state)).isSameAs(state);
  }

  @Test void trimsAndNormalizesCustomHostLikeTheNativeReducer() {
    LoginModal.State state = new LoginModal.CustomHostInput(
        new Utf8Editor().insert("https://box.example:8443/v1/  \r\n"));
    var transition = LoginModal.submit(state);
    assertThat(transition.state()).isEqualTo(new LoginModal.Closed());
    assertThat(transition.action()).contains(
        new LoginModal.SwitchCustomHost("box.example:8443"));
    assertThat(LoginModal.submit(new LoginModal.CustomHostInput()).state())
        .isEqualTo(new LoginModal.Failed("no host entered"));
  }

  @Test void routesAnthropicAndProviderKeysThroughDistinctTypedActions() {
    var anthropic = LoginModal.submit(new LoginModal.ApiKeyInput(
        new Utf8Editor().insert("sk-ant-test \n"), "", ""));
    assertThat(anthropic.action()).contains(new LoginModal.InstallAnthropicKey("sk-ant-test"));
    var provider = LoginModal.submit(new LoginModal.ApiKeyInput(
        new Utf8Editor().insert("secret\t"), "groq", "Groq"));
    assertThat(provider.action()).contains(
        new LoginModal.InstallProviderKey("groq", "Groq", "secret"));
    assertThat(LoginModal.submit(new LoginModal.ApiKeyInput()).state())
        .isEqualTo(new LoginModal.Failed("no key entered"));
  }

  @Test void submitsOAuthWithoutDestroyingVerifierOnEmptyInput() {
    var empty = new LoginModal.OAuthCode("v", "s", ATTEMPT.authorizeUri(),
        new Utf8Editor(" \r\n", 3));
    assertThat(LoginModal.submit(empty).state()).isEqualTo(
        new LoginModal.OAuthCode("v", "s", ATTEMPT.authorizeUri(), new Utf8Editor()));
    var code = new LoginModal.OAuthCode("v", "s", ATTEMPT.authorizeUri(),
        new Utf8Editor("auth-code\n", 10));
    var transition = LoginModal.submit(code);
    assertThat(transition.state()).isEqualTo(new LoginModal.OAuthExchanging());
    assertThat(transition.action()).contains(new LoginModal.ExchangeOAuth("auth-code", "v", "s"));
  }
}
