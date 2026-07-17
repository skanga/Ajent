package com.github.skanga.ajent.domain;

import java.util.concurrent.atomic.AtomicBoolean;

/** Shared, idempotent cancellation signal carried throughout one active turn. */
public final class CancellationSignal {
  private final AtomicBoolean cancelled = new AtomicBoolean();

  public boolean cancel() {
    return cancelled.compareAndSet(false, true);
  }

  public boolean isCancelled() {
    return cancelled.get();
  }
}
