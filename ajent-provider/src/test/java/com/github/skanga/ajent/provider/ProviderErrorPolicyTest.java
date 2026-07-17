package com.github.skanga.ajent.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

final class ProviderErrorPolicyTest {
  @Test void classifiesEveryTypedHttpFailureShapeLikeAgentty() {
    assertThat(ProviderErrorPolicy.classify(HttpErrorKind.CANCELLED, 0))
        .isEqualTo(ErrorClass.CANCELLED);
    assertThat(new HttpErrorKind[] {HttpErrorKind.RESOLVE, HttpErrorKind.CONNECT,
        HttpErrorKind.TLS, HttpErrorKind.PROTOCOL, HttpErrorKind.SOCKET_HANGUP,
        HttpErrorKind.TIMEOUT, HttpErrorKind.PEER_CLOSED})
        .allSatisfy(kind -> assertThat(ProviderErrorPolicy.classify(kind, 0))
            .isEqualTo(ErrorClass.TRANSIENT));
    assertThat(ProviderErrorPolicy.classify(HttpErrorKind.BODY, 0))
        .isEqualTo(ErrorClass.TERMINAL);
    assertThat(ProviderErrorPolicy.classify(HttpErrorKind.UNKNOWN, 0))
        .isEqualTo(ErrorClass.TERMINAL);
    assertThat(ProviderErrorPolicy.classify(HttpErrorKind.STATUS, 401))
        .isEqualTo(ErrorClass.AUTH);
    assertThat(ProviderErrorPolicy.classify(HttpErrorKind.STATUS, 403))
        .isEqualTo(ErrorClass.AUTH);
    assertThat(ProviderErrorPolicy.classify(HttpErrorKind.STATUS, 429))
        .isEqualTo(ErrorClass.RATE_LIMIT);
    assertThat(java.util.Arrays.stream(new int[] {408, 502, 503, 504, 529}).boxed()).allSatisfy(status ->
        assertThat(ProviderErrorPolicy.classify(HttpErrorKind.STATUS, status))
            .isEqualTo(ErrorClass.TRANSIENT));
    assertThat(java.util.Arrays.stream(new int[] {200, 400, 404, 422}).boxed()).allSatisfy(status ->
        assertThat(ProviderErrorPolicy.classify(HttpErrorKind.STATUS, status))
            .isEqualTo(ErrorClass.TERMINAL));
  }

  @Test void classifiesWireOnlyMessagesCaseInsensitively() {
    assertThat(ProviderErrorPolicy.classify("request CANCELLED")).isEqualTo(ErrorClass.CANCELLED);
    assertThat(ProviderErrorPolicy.classify("authentication_error: invalid API key"))
        .isEqualTo(ErrorClass.AUTH);
    assertThat(ProviderErrorPolicy.classify("HTTP 429 rate_limit_error"))
        .isEqualTo(ErrorClass.RATE_LIMIT);
    assertThat(ProviderErrorPolicy.classify("Overloaded 529")).isEqualTo(ErrorClass.TRANSIENT);
    assertThat(ProviderErrorPolicy.classify("broken pipe / EOF"))
        .isEqualTo(ErrorClass.TRANSIENT);
    assertThat(ProviderErrorPolicy.classify("model not found")).isEqualTo(ErrorClass.TERMINAL);
  }

  @Test void exposesTheExactBackoffLaddersCapsAndJitterWindow() {
    assertThat(java.util.stream.IntStream.range(0, 6)
        .mapToObj(i -> ProviderErrorPolicy.backoff(ErrorClass.TRANSIENT, i)))
        .containsExactly(Duration.ofMillis(500), Duration.ofSeconds(2), Duration.ofSeconds(5),
            Duration.ofSeconds(12), Duration.ofSeconds(25), Duration.ofSeconds(45));
    assertThat(java.util.stream.IntStream.range(0, 6)
        .mapToObj(i -> ProviderErrorPolicy.backoff(ErrorClass.RATE_LIMIT, i)))
        .containsExactly(Duration.ofSeconds(3), Duration.ofSeconds(8), Duration.ofSeconds(20),
            Duration.ofSeconds(40), Duration.ofSeconds(60), Duration.ofSeconds(90));
    assertThat(ProviderErrorPolicy.backoff(ErrorClass.TRANSIENT, -9))
        .isEqualTo(Duration.ofMillis(500));
    assertThat(ProviderErrorPolicy.backoff(ErrorClass.TRANSIENT, 99))
        .isEqualTo(Duration.ofSeconds(45));
    assertThat(ProviderErrorPolicy.backoffWithJitter(ErrorClass.TRANSIENT, 0, 0.80))
        .isEqualTo(Duration.ofMillis(400));
    assertThat(ProviderErrorPolicy.backoffWithJitter(ErrorClass.TRANSIENT, 0, 1.20))
        .isEqualTo(Duration.ofMillis(600));
    assertThat(ProviderErrorPolicy.maxRetries(ErrorClass.TRANSIENT, false)).isEqualTo(6);
    assertThat(ProviderErrorPolicy.maxRetries(ErrorClass.TRANSIENT, true)).isEqualTo(4);
    assertThat(ProviderErrorPolicy.maxRetries(ErrorClass.RATE_LIMIT, true)).isEqualTo(6);
    assertThat(ProviderErrorPolicy.maxRetries(ErrorClass.TERMINAL, false)).isZero();
    assertThat(ProviderErrorPolicy.RETRY_DECAY).isEqualTo(Duration.ofSeconds(90));
  }
}
