package com.github.skanga.ajent.core.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ThreadId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AsyncThreadWriterTest {

  @Test
  void coalescesQueuedSnapshotsByThreadIdAndFlushesLatest() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var saved = Collections.synchronizedList(new ArrayList<Thread>());
    try (var writer = new AsyncThreadWriter(thread -> {
      if (saved.isEmpty()) {
        started.countDown();
        await(release);
      }
      saved.add(thread);
    })) {
      writer.enqueue(thread("same", "first"));
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

      writer.enqueue(thread("same", "superseded"));
      writer.enqueue(thread("same", "latest"));
      writer.enqueue(thread("other", "independent"));
      release.countDown();
      writer.flushAndStop();
    }

    assertThat(saved).extracting(Thread::title)
        .containsExactlyInAnyOrder("first", "latest", "independent")
        .doesNotContain("superseded");
  }

  @Test
  void ignoresUnsavableThreadsAndContinuesAfterWriteFailure() {
    var saved = Collections.synchronizedList(new ArrayList<String>());
    try (var writer = new AsyncThreadWriter(thread -> {
      if (thread.title().equals("bad")) throw new IllegalStateException("disk failure");
      saved.add(thread.title());
    })) {
      writer.enqueue(new Thread(new ThreadId("empty"), "empty", List.of()));
      writer.enqueue(thread("bad", "bad"));
      writer.enqueue(thread("good", "good"));
      writer.flushAndStop();
    }

    assertThat(saved).containsExactly("good");
  }

  @Test
  void filesystemWriterReturnsOnlyAfterSnapshotIsDurable(@TempDir java.nio.file.Path directory) {
    var store = new ThreadStore(directory);
    try (var writer = new AsyncThreadWriter(store)) {
      writer.enqueue(thread("thread", "persisted"));
      writer.flushAndStop();
    }

    assertThat(store.loadAllMetadata()).extracting(Thread::title).containsExactly("persisted");
  }

  private static Thread thread(String id, String title) {
    Instant timestamp = Instant.ofEpochSecond(10);
    return new Thread(new ThreadId(id), title,
        List.of(new Message(Role.USER, title, List.of(), List.of())),
        timestamp, timestamp, List.of());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
    } catch (InterruptedException exception) {
      java.lang.Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", exception);
    }
  }
}
