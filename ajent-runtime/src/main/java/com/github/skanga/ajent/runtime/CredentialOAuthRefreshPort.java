package com.github.skanga.ajent.runtime;

import com.github.skanga.ajent.provider.auth.Credential;
import com.github.skanga.ajent.provider.auth.CredentialStore;
import com.github.skanga.ajent.provider.auth.OAuthTokenClient;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Serial OAuth refresh adapter that persists and installs the new bearer. */
public final class CredentialOAuthRefreshPort implements OAuthRefreshPort {
  private final OAuthTokenClient client;
  private final CredentialStore store;
  private final Consumer<Credential> installer;
  private final LongSupplier epochMillis;

  public CredentialOAuthRefreshPort(OAuthTokenClient client, CredentialStore store,
                                    Consumer<Credential> installer, LongSupplier epochMillis) {
    this.client = Objects.requireNonNull(client, "client");
    this.store = Objects.requireNonNull(store, "store");
    this.installer = Objects.requireNonNull(installer, "installer");
    this.epochMillis = Objects.requireNonNull(epochMillis, "epochMillis");
  }

  @Override public synchronized Result refreshAndInstall(String refreshToken) {
    Objects.requireNonNull(refreshToken, "refreshToken");
    return switch (client.refresh(refreshToken)) {
      case OAuthTokenClient.Result.Failure failure ->
          new Result.Failure(failure.error().render());
      case OAuthTokenClient.Result.Success success -> {
        OAuthTokenClient.Token token = success.token();
        String nextRefresh = token.refreshToken().isEmpty()
            ? refreshToken : token.refreshToken();
        long expiresAt = token.expiresInSeconds() == 0 ? 0
            : epochMillis.getAsLong() + token.expiresInSeconds() * 1_000;
        var credential = new Credential.OAuth(token.accessToken(), nextRefresh, expiresAt);
        store.save(credential); // best effort; the installed in-memory credential is authoritative.
        installer.accept(credential);
        yield new Result.Success();
      }
    };
  }
}
