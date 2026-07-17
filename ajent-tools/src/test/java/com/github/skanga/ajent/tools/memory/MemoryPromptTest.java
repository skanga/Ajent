package com.github.skanga.ajent.tools.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryPromptTest {
  @Test
  void keepsPinnedThenHitsThenRecencyButEmitsChronologically() {
    var records = List.of(record("old", 1, false, 0, "old fact"),
        record("hits", 2, false, 5, "important repeated fact"),
        record("new", 3, false, 0, "recent fact"),
        record("pin", 4, true, 0, "pinned fact"));
    MemoryPrompt.Selection selected = MemoryPrompt.select(records, 75);
    assertThat(selected.records()).extracting(JsonlMemoryStore.StoredRecord::id)
        .containsExactly("hits", "pin");
    assertThat(selected.dropped()).isEqualTo(2);
  }

  @Test
  void clipsUtf8AndLetsPinnedRecordsExceedBudget() {
    var records = List.of(record("p1", 1, true, 0, "é".repeat(300)),
        record("p2", 2, true, 0, "pinned two"));
    MemoryPrompt.Selection selected = MemoryPrompt.select(records, 1);
    assertThat(selected.records()).hasSize(2);
    assertThat(selected.records().getFirst().text()).endsWith("…");
    assertThat(selected.records().getFirst().text().getBytes(StandardCharsets.UTF_8))
        .hasSizeLessThanOrEqualTo(403);
  }

  @Test
  void rendersExactRecordAndBoundedBlock() {
    var record = new JsonlMemoryStore.StoredRecord("abc", 1, "user", "fact", true,
        List.of("build", "java"), 2);
    assertThat(MemoryPrompt.render(record)).isEqualTo("[abc] ★ fact  {build, java}");
    assertThat(MemoryPrompt.block("user", List.of(record))).isEqualTo("""
        <learned-memory scope="user">
        Facts saved by the user or learned in earlier sessions:
        [abc] ★ fact  {build, java}
        </learned-memory>
        """);
    assertThat(MemoryPrompt.block("user", List.of())).isEmpty();
  }

  @Test
  void recentLoaderReturnsTailFifty(@TempDir Path root) {
    var ids = new ArrayDeque<String>();
    for (int index = 0; index < 60; index++) ids.add("%08x".formatted(index));
    var store = new JsonlMemoryStore(root.resolve("home"), root.resolve("work"),
        Clock.fixed(Instant.ofEpochSecond(1), ZoneOffset.UTC), ids::remove);
    for (int index = 0; index < 60; index++) store.append(new MemoryStore.AppendRequest(
        "x" + index, "project", false, List.of(), ""));
    assertThat(store.loadRecent("project")).hasSize(50)
        .extracting(JsonlMemoryStore.StoredRecord::id).startsWith("0000000a").endsWith("0000003b");
  }

  private static JsonlMemoryStore.StoredRecord record(String id, long timestamp, boolean pinned,
      int hits, String text) {
    return new JsonlMemoryStore.StoredRecord(id, timestamp, "user", text, pinned, List.of(), hits);
  }
}
