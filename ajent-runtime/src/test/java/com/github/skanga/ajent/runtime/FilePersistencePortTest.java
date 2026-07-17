package com.github.skanga.ajent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.core.persistence.ThreadStore;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ThreadId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FilePersistencePortTest {
  @Test void closeReturnsOnlyAfterTheLastQueuedSnapshotIsDurable(
      @TempDir java.nio.file.Path directory) {
    var persistence = new FilePersistencePort(directory);
    persistence.save(thread("first"));
    persistence.save(thread("latest"));

    persistence.close();
    persistence.close();

    var result = new ThreadStore(directory).load(directory.resolve("threads/thread.json"));
    assertThat(result).isInstanceOfSatisfying(
        com.github.skanga.ajent.core.persistence.ThreadLoadResult.Success.class,
        success -> assertThat(success.thread().title()).isEqualTo("latest"));
  }

  private static Thread thread(String title) {
    Instant timestamp = Instant.ofEpochSecond(10);
    return new Thread(new ThreadId("thread"), title,
        List.of(new Message(Role.USER, title, List.of(), List.of())),
        timestamp, timestamp, List.of());
  }
}
