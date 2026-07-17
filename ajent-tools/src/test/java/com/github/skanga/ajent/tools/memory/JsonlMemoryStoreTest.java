package com.github.skanga.ajent.tools.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonlMemoryStoreTest {
  @Test
  void writesLegacyCompatibleJsonlAndSkipsCorruptLines(@TempDir Path root) throws Exception {
    var store = store(root, "a1b2c3d4");
    MemoryStore.AppendResult result = store.append(new MemoryStore.AppendRequest("prefer fish",
        "user", false, List.of(), ""));
    assertThat(result.id()).isEqualTo("a1b2c3d4");
    Path path = root.resolve("home/.agentty/memory.jsonl");
    assertThat(Files.readString(path)).isEqualTo(
        "{\"id\":\"a1b2c3d4\",\"ts\":1731860000,\"scope\":\"user\",\"text\":\"prefer fish\"}\n");
    Files.writeString(path, "not-json\n{\"id\":\"bad\",\"scope\":\"wat\",\"text\":\"x\"}\n",
        StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
    assertThat(store.loadAll("user")).hasSize(1).first().satisfies(record -> {
      assertThat(record.id()).isEqualTo("a1b2c3d4");
      assertThat(record.pinned()).isFalse();
      assertThat(record.hits()).isZero();
    });
  }

  @Test
  void deduplicatesMergesMetadataAndSupersedesAcrossScopes(@TempDir Path root) {
    var store = store(root, "11111111", "22222222", "33333333");
    store.append(new MemoryStore.AppendRequest("Build with CMake", "project", false,
        List.of("Build"), ""));
    MemoryStore.AppendResult dedup = store.append(new MemoryStore.AppendRequest(
        " build   with cmake ", "project", true, List.of("build", "FAST"), ""));
    assertThat(dedup.deduped()).isTrue();
    assertThat(dedup.id()).isEqualTo("11111111");
    assertThat(dedup.note()).contains("hits=1");
    assertThat(store.loadAll("project")).singleElement().satisfies(record -> {
      assertThat(record.pinned()).isTrue();
      assertThat(record.tags()).containsExactly("build", "fast");
      assertThat(record.hits()).isEqualTo(1);
    });
    store.append(new MemoryStore.AppendRequest("global fact", "user", false, List.of(), ""));
    MemoryStore.AppendResult replaced = store.append(new MemoryStore.AppendRequest("replacement",
        "project", false, List.of(), "22222222"));
    assertThat(replaced.note()).contains("superseded 22222222");
    assertThat(store.loadAll("user")).isEmpty();
    assertThat(store.loadAll("project")).extracting(JsonlMemoryStore.StoredRecord::text)
        .contains("replacement");
  }

  @Test
  void truncatesUtf8RollsOldestUnpinnedAndSupportsForgetAndWipe(@TempDir Path root) {
    var ids = new ArrayDeque<String>();
    for (int index = 0; index < 205; index++) ids.add("%08x".formatted(index));
    var store = new JsonlMemoryStore(root.resolve("home"), root.resolve("work"),
        Clock.fixed(Instant.ofEpochSecond(10), ZoneOffset.UTC), ids::remove);
    MemoryStore.AppendResult clipped = store.append(new MemoryStore.AppendRequest("é".repeat(1100),
        "user", true, List.of(), ""));
    assertThat(clipped.note()).contains("text truncated");
    assertThat(store.loadAll("user").getFirst().text().getBytes(StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(JsonlMemoryStore.MAX_TEXT_BYTES);
    for (int index = 0; index < 200; index++) store.append(new MemoryStore.AppendRequest(
        "x" + index, "user", false, List.of(), ""));
    assertThat(store.loadAll("user")).hasSize(200);
    assertThat(store.loadAll("user").getFirst().pinned()).isTrue();
    assertThat(store.previewForget("x199")).singleElement().extracting(MemoryStore.Record::text)
        .isEqualTo("x199");
    assertThat(store.forgetBySubstring("x199")).isEqualTo(1);
    String pinnedId = store.loadAll("user").getFirst().id();
    assertThat(store.forgetById(pinnedId)).isEqualTo(1);
    assertThat(store.wipe("user")).hasValue(198);
    assertThat(store.loadAll("user")).isEmpty();
    assertThat(store.wipe("bad")).isEmpty();
  }

  @Test
  void helpersMatchPinnedNormalizationAndSimilarity() {
    assertThat(JsonlMemoryStore.normalizeTags(List.of(" Z ", "a", "A", "")))
        .containsExactly("a", "z");
    assertThat(JsonlMemoryStore.normalizeText(" Build\tWITH  CMake ")).isEqualTo("build with cmake");
    assertThat(JsonlMemoryStore.jaro("martha", "marhta")).isGreaterThan(.9);
    assertThat(JsonlMemoryStore.jaro("", "x")).isZero();
    assertThat(JsonlMemoryStore.jaro("abc", "xyz")).isZero();
    assertThat(JsonlMemoryStore.truncateUtf8("abcé", 4)).isEqualTo("abc");
    assertThat(JsonlMemoryStore.truncateUtf8("abc", 4)).isEqualTo("abc");
  }

  @Test
  void coversInvalidRequestsMissingSupersedeAndDefensiveOptionalFields(@TempDir Path root)
      throws Exception {
    var store = store(root, "aaaaaaaa");
    assertThat(store.append(new MemoryStore.AppendRequest("  ", "user", false, List.of(), ""))
        .error()).contains("empty after trim");
    assertThat(store.append(new MemoryStore.AppendRequest("x", "bad", false, List.of(), ""))
        .error()).contains("memory path");
    MemoryStore.AppendResult result = store.append(new MemoryStore.AppendRequest("new fact", "user",
        false, List.of(), "missing"));
    assertThat(result.note()).contains("not found", "still written");
    assertThat(store.forgetById(" ")).isZero();
    assertThat(store.forgetBySubstring(" ")).isZero();
    assertThat(store.previewForget(" ")).isEmpty();
    assertThat(store.loadAll("bad")).isEmpty();

    Path path = store.pathFor("user");
    Files.writeString(path, "{\"id\":\"legacy\",\"ts\":1,\"scope\":\"user\","
        + "\"text\":\"legacy\",\"pinned\":\"wrong\",\"hits\":\"wrong\","
        + "\"tags\":[1,\"ok\",\"\"]}\n", StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);
    assertThat(store.loadAll("user")).filteredOn(record -> record.id().equals("legacy"))
        .singleElement().satisfies(record -> {
          assertThat(record.pinned()).isFalse();
          assertThat(record.hits()).isZero();
          assertThat(record.tags()).containsExactly("ok");
        });

    assertThat(new JsonlMemoryStore(root.resolve("h"), root.resolve("w")).scopes())
        .containsExactly("project", "user");
  }

  private static JsonlMemoryStore store(Path root, String... identifiers) {
    var ids = new ArrayDeque<>(List.of(identifiers));
    return new JsonlMemoryStore(root.resolve("home"), root.resolve("work"),
        Clock.fixed(Instant.ofEpochSecond(1_731_860_000L), ZoneOffset.UTC), ids::remove);
  }
}
