package com.github.skanga.ajent.provider.auth;

import java.util.Objects;

@FunctionalInterface
public interface OAuthTokenClient {
  Result refresh(String refreshToken);

  record Token(String accessToken, String refreshToken, long expiresInSeconds) {
    public Token {
      accessToken = Objects.requireNonNull(accessToken, "accessToken");
      refreshToken = Objects.requireNonNull(refreshToken, "refreshToken");
    }
  }

  enum ErrorKind {
    NETWORK("network"),
    BAD_RESPONSE("bad response"),
    API_ERROR("api error"),
    MISSING_TOKEN("missing token");

    private final String label;

    ErrorKind(String label) { this.label = label; }
  }

  record Error(ErrorKind kind, String detail) {
    public Error {
      kind = Objects.requireNonNull(kind, "kind");
      detail = Objects.requireNonNull(detail, "detail");
    }

    public String render() { return "[" + kind.label + "] " + detail; }
  }

  sealed interface Result {
    record Success(Token token) implements Result {
      public Success { token = Objects.requireNonNull(token, "token"); }
    }

    record Failure(Error error) implements Result {
      public Failure { error = Objects.requireNonNull(error, "error"); }
    }
  }
}
