package com.github.skanga.ajent.runtime;

import com.github.skanga.ajent.domain.CheckpointId;

/** Workspace snapshot side effect used at the start of a real (not queued) turn. */
public interface CheckpointPort {
  boolean enabled();
  boolean create(CheckpointId id);

  static CheckpointPort disabled() {
    return new CheckpointPort() {
      @Override public boolean enabled() { return false; }
      @Override public boolean create(CheckpointId id) { return false; }
    };
  }
}
