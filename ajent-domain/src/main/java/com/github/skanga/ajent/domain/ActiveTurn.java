package com.github.skanga.ajent.domain;

import java.util.Objects;

/** Context that exists throughout every non-idle request/tool phase. */
public record ActiveTurn(
    CancellationSignal cancellation,
    long startedNanos,
    long lastEventNanos,
    int truncationRetries,
    int transientRetries,
    int midStreamFailures,
    long lastFailureNanos,
    long liveDeltaBytes,
    long firstDeltaNanos,
    long rateLastSampleNanos,
    long rateLastSampleBytes,
    RetryState retryState) {

  public ActiveTurn {
    cancellation = Objects.requireNonNull(cancellation, "cancellation");
    retryState = Objects.requireNonNull(retryState, "retryState");
    if (truncationRetries < 0 || transientRetries < 0 || midStreamFailures < 0
        || liveDeltaBytes < 0 || rateLastSampleBytes < 0)
      throw new IllegalArgumentException("turn counters cannot be negative");
  }

  public static ActiveTurn start(CancellationSignal cancellation, long nowNanos) {
    return new ActiveTurn(cancellation, nowNanos, nowNanos, 0, 0, 0, 0,
        0, 0, 0, 0, new RetryState.Fresh());
  }

  public ActiveTurn withLastEventNanos(long value) {
    return copy(value, truncationRetries, transientRetries, midStreamFailures, lastFailureNanos,
        liveDeltaBytes, firstDeltaNanos, rateLastSampleNanos, rateLastSampleBytes, retryState);
  }

  public ActiveTurn withTruncationRetries(int value) {
    return copy(lastEventNanos, value, transientRetries, midStreamFailures, lastFailureNanos,
        liveDeltaBytes, firstDeltaNanos, rateLastSampleNanos, rateLastSampleBytes, retryState);
  }

  public ActiveTurn withTransientRetries(int value) {
    return copy(lastEventNanos, truncationRetries, value, midStreamFailures, lastFailureNanos,
        liveDeltaBytes, firstDeltaNanos, rateLastSampleNanos, rateLastSampleBytes, retryState);
  }

  public ActiveTurn withMidStreamFailures(int value) {
    return copy(lastEventNanos, truncationRetries, transientRetries, value, lastFailureNanos,
        liveDeltaBytes, firstDeltaNanos, rateLastSampleNanos, rateLastSampleBytes, retryState);
  }

  public ActiveTurn withLastFailureNanos(long value) {
    return copy(lastEventNanos, truncationRetries, transientRetries, midStreamFailures, value,
        liveDeltaBytes, firstDeltaNanos, rateLastSampleNanos, rateLastSampleBytes, retryState);
  }

  public ActiveTurn withRetryState(RetryState value) {
    return copy(lastEventNanos, truncationRetries, transientRetries, midStreamFailures,
        lastFailureNanos, liveDeltaBytes, firstDeltaNanos, rateLastSampleNanos,
        rateLastSampleBytes, value);
  }

  public ActiveTurn withLiveDelta(long bytes, long firstNanos, long sampleNanos,
                                  long sampleBytes) {
    return copy(lastEventNanos, truncationRetries, transientRetries, midStreamFailures,
        lastFailureNanos, bytes, firstNanos, sampleNanos, sampleBytes, retryState);
  }

  private ActiveTurn copy(long eventNanos, int truncation, int transientCount, int midStream,
                          long failureNanos, long deltaBytes, long deltaStart, long sampleNanos,
                          long sampleBytes, RetryState retry) {
    return new ActiveTurn(cancellation, startedNanos, eventNanos, truncation, transientCount,
        midStream, failureNanos, deltaBytes, deltaStart, sampleNanos, sampleBytes, retry);
  }
}
