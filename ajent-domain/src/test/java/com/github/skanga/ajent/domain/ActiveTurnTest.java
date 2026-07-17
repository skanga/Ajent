package com.github.skanga.ajent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

final class ActiveTurnTest {
  @Test void startsWithTheNativeFreshRetryAndRateCounters() {
    var signal = new CancellationSignal();
    ActiveTurn turn = ActiveTurn.start(signal, 42);
    assertThat(turn.cancellation()).isSameAs(signal);
    assertThat(turn.startedNanos()).isEqualTo(42);
    assertThat(turn.lastEventNanos()).isEqualTo(42);
    assertThat(turn.truncationRetries()).isZero();
    assertThat(turn.transientRetries()).isZero();
    assertThat(turn.midStreamFailures()).isZero();
    assertThat(turn.retryState()).isInstanceOf(RetryState.Fresh.class);
  }

  @Test void immutableUpdatesRetainUnchangedTurnState() {
    ActiveTurn original = ActiveTurn.start(new CancellationSignal(), 10);
    ActiveTurn updated = original.withLastEventNanos(20).withTransientRetries(3)
        .withTruncationRetries(1).withMidStreamFailures(2).withLastFailureNanos(19)
        .withRetryState(new RetryState.StallFired()).withLiveDelta(12, 15, 18, 7);
    assertThat(original.lastEventNanos()).isEqualTo(10);
    assertThat(updated.lastEventNanos()).isEqualTo(20);
    assertThat(updated.transientRetries()).isEqualTo(3);
    assertThat(updated.truncationRetries()).isOne();
    assertThat(updated.midStreamFailures()).isEqualTo(2);
    assertThat(updated.lastFailureNanos()).isEqualTo(19);
    assertThat(updated.retryState()).isInstanceOf(RetryState.StallFired.class);
    assertThat(updated.liveDeltaBytes()).isEqualTo(12);
    assertThat(updated.firstDeltaNanos()).isEqualTo(15);
    assertThat(updated.rateLastSampleNanos()).isEqualTo(18);
    assertThat(updated.rateLastSampleBytes()).isEqualTo(7);
  }

  @Test void cancellationIsIdempotentAndCountersCannotBeNegative() {
    var signal = new CancellationSignal();
    assertThat(signal.cancel()).isTrue();
    assertThat(signal.cancel()).isFalse();
    assertThat(signal.isCancelled()).isTrue();
    assertThatIllegalArgumentException().isThrownBy(
        () -> ActiveTurn.start(new CancellationSignal(), 1).withTransientRetries(-1));
  }
}
