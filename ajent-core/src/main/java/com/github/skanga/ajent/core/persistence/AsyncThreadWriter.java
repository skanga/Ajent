package com.github.skanga.ajent.core.persistence;

import com.github.skanga.ajent.core.AjentDebugLog;
import com.github.skanga.ajent.domain.Thread;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Single-worker, per-thread coalescing persistence queue. */
public final class AsyncThreadWriter implements AutoCloseable {
  private final Object monitor = new Object();
  private final Map<String, Thread> pending = new LinkedHashMap<>();
  private final Consumer<Thread> save;
  private java.lang.Thread worker;
  private boolean stopping;

  public AsyncThreadWriter(ThreadStore store) {
    this(thread -> store.save(thread));
  }

  AsyncThreadWriter(Consumer<Thread> save) {
    this.save = Objects.requireNonNull(save, "save");
  }

  public void enqueue(Thread thread) {
    Objects.requireNonNull(thread, "thread");
    if (thread.id().value().isEmpty() || thread.messages().isEmpty()) return;
    synchronized (monitor) {
      if (stopping) return;
      pending.put(thread.id().value(), thread);
      if (worker == null) {
        worker = java.lang.Thread.ofPlatform()
            .daemon()
            .name("ajent-thread-writer")
            .start(this::run);
      }
      monitor.notifyAll();
    }
  }

  public void flushAndStop() {
    java.lang.Thread joining;
    synchronized (monitor) {
      stopping = true;
      joining = worker;
      monitor.notifyAll();
    }
    boolean interrupted = false;
    while (joining != null && joining.isAlive()) {
      try {
        joining.join();
      } catch (InterruptedException exception) {
        interrupted = true;
      }
    }
    if (interrupted) java.lang.Thread.currentThread().interrupt();
  }

  @Override
  public void close() {
    flushAndStop();
  }

  private void run() {
    for (;;) {
      Thread next;
      synchronized (monitor) {
        while (pending.isEmpty() && !stopping) {
          try {
            monitor.wait();
          } catch (InterruptedException exception) {
            // Lifecycle is controlled by flushAndStop; a stray interrupt must not drop saves.
          }
        }
        if (pending.isEmpty()) return;
        var iterator = pending.entrySet().iterator();
        next = iterator.next().getValue();
        iterator.remove();
      }
      try {
        save.accept(next);
      } catch (RuntimeException | LinkageError failure) {
        AjentDebugLog.log("persistence.async_save", failure);
        // Ajent treats background persistence as best effort and keeps draining.
      }
    }
  }
}
