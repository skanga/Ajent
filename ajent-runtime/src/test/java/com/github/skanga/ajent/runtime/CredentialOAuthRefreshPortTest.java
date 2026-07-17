package com.github.skanga.ajent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.provider.auth.Credential;
import com.github.skanga.ajent.provider.auth.CredentialStore;
import com.github.skanga.ajent.provider.auth.OAuthTokenClient;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CredentialOAuthRefreshPortTest {
  @TempDir java.nio.file.Path temp;

  @Test void refreshesPersistsAndInstallsWhileRetainingAnOmittedRefreshToken() {
    var store = new CredentialStore(temp.resolve("credentials.json"), "machine-seed");
    var installed = new AtomicReference<Credential>();
    OAuthTokenClient client = token -> new OAuthTokenClient.Result.Success(
        new OAuthTokenClient.Token("new-access", "", 3600));
    var port = new CredentialOAuthRefreshPort(client, store, installed::set, () -> 10_000L);

    assertThat(port.refreshAndInstall("old-refresh"))
        .isEqualTo(new OAuthRefreshPort.Result.Success());
    var expected = new Credential.OAuth("new-access", "old-refresh", 3_610_000L);
    assertThat(installed).hasValue(expected);
    assertThat(store.load()).contains(expected);
  }

  @Test void typedRefreshFailureDoesNotOverwriteCredentialsOrInstallAuth() {
    var store = new CredentialStore(temp.resolve("credentials.json"), "machine-seed");
    var original = new Credential.OAuth("old-access", "old-refresh", 1L);
    assertThat(store.save(original)).isTrue();
    var installed = new AtomicReference<Credential>();
    OAuthTokenClient client = token -> new OAuthTokenClient.Result.Failure(
        new OAuthTokenClient.Error(OAuthTokenClient.ErrorKind.NETWORK, "offline"));
    var port = new CredentialOAuthRefreshPort(client, store, installed::set, () -> 10_000L);

    assertThat(port.refreshAndInstall("old-refresh"))
        .isEqualTo(new OAuthRefreshPort.Result.Failure("[network] offline"));
    assertThat(installed).hasNullValue();
    assertThat(store.load()).contains(original);
  }
}
