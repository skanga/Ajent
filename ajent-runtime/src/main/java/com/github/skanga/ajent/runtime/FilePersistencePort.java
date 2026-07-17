package com.github.skanga.ajent.runtime;

import com.github.skanga.ajent.core.persistence.AsyncThreadWriter;
import com.github.skanga.ajent.core.persistence.ThreadStore;
import com.github.skanga.ajent.domain.Thread;
import java.nio.file.Path;
import java.util.Objects;

/** Filesystem persistence adapter with AgenTTY's coalescing, drain-on-close lifecycle. */
public final class FilePersistencePort implements PersistencePort {
  private final AsyncThreadWriter writer;

  public FilePersistencePort(Path dataDirectory) {
    this(new ThreadStore(Objects.requireNonNull(dataDirectory, "dataDirectory")));
  }

  public FilePersistencePort(ThreadStore store) {
    writer = new AsyncThreadWriter(Objects.requireNonNull(store, "store"));
  }

  @Override
  public void save(Thread thread) {
    writer.enqueue(thread);
  }

  @Override
  public void close() {
    writer.flushAndStop();
  }
}
