package com.github.skanga.ajent.runtime;

import com.github.skanga.ajent.domain.Thread;

@FunctionalInterface
public interface PersistencePort extends AutoCloseable {
  void save(Thread thread);

  @Override
  default void close() {}
}
