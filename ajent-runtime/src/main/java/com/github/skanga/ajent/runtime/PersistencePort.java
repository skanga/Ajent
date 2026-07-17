package com.github.skanga.ajent.runtime;

import com.github.skanga.ajent.domain.Thread;

@FunctionalInterface
public interface PersistencePort {
  void save(Thread thread);
}
