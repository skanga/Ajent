package com.github.skanga.ajent.runtime;

import java.util.Objects;

/** Refreshes, persists, and installs an OAuth bearer for subsequent provider requests. */
@FunctionalInterface
public interface OAuthRefreshPort {
  Result refreshAndInstall(String refreshToken);

  sealed interface Result {
    record Success() implements Result {}

    record Failure(String error) implements Result {
      public Failure { error = Objects.requireNonNull(error, "error"); }
    }
  }
}
