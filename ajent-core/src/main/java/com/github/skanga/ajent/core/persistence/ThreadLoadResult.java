package com.github.skanga.ajent.core.persistence;

import com.github.skanga.ajent.domain.Thread;
import java.util.Objects;

public sealed interface ThreadLoadResult {
  record Success(Thread thread) implements ThreadLoadResult {
    public Success { thread = Objects.requireNonNull(thread, "thread"); }
  }

  record Failure(DeserializeError error) implements ThreadLoadResult {
    public Failure { error = Objects.requireNonNull(error, "error"); }
  }
}
