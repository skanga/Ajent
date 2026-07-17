package com.github.skanga.ajent.tools.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.function.Supplier;

/** Durable AgenTTY-compatible user/project JSONL memory store. */
public final class JsonlMemoryStore implements MemoryStore {
  static final int MAX_TEXT_BYTES = 2 * 1024;
  static final int MAX_FILE_BYTES = 256 * 1024;
  static final int MAX_RECORDS = 200;
  private static final ObjectMapper JSON = new ObjectMapper();

  public record StoredRecord(String id, long timestamp, String scope, String text,
                             boolean pinned, List<String> tags, int hits) {
    public StoredRecord { tags = List.copyOf(tags); }
  }

  private final Path home;
  private final Path workspace;
  private final Clock clock;
  private final Supplier<String> ids;

  public JsonlMemoryStore(Path home, Path workspace) {
    this(home, workspace, Clock.systemUTC(), () -> "%08x".formatted(
        java.util.concurrent.ThreadLocalRandom.current().nextInt()));
  }

  JsonlMemoryStore(Path home, Path workspace, Clock clock, Supplier<String> ids) {
    this.home = home == null ? null : home.toAbsolutePath().normalize();
    this.workspace = workspace == null ? null : workspace.toAbsolutePath().normalize();
    this.clock = clock;
    this.ids = ids;
  }

  @Override public List<String> scopes() { return List.of("project", "user"); }

  @Override public synchronized AppendResult append(AppendRequest request) {
    String text = request.text().strip();
    if (text.isEmpty()) return error("remember: text is empty after trim");
    String note = "";
    int originalBytes = text.getBytes(StandardCharsets.UTF_8).length;
    if (originalBytes > MAX_TEXT_BYTES) {
      text = truncateUtf8(text, MAX_TEXT_BYTES);
      note = "text truncated to " + text.getBytes(StandardCharsets.UTF_8).length
          + " bytes (was " + originalBytes + ")";
    }
    Path path = pathFor(request.scope());
    if (path == null) return error("remember: can't resolve a writable " + request.scope()
        + " memory path");
    try {
      List<StoredRecord> records = load(path);
      List<String> tags = normalizeTags(request.tags());
      String needle = normalizeText(text);
      for (int index = 0; index < records.size(); index++) {
        StoredRecord current = records.get(index);
        String haystack = normalizeText(current.text());
        if (current.scope().equals(request.scope())
            && (haystack.equals(needle) || jaro(haystack, needle) >= .92)) {
          var merged = new ArrayList<>(current.tags());
          merged.addAll(tags);
          StoredRecord refreshed = new StoredRecord(current.id(), clock.instant().getEpochSecond(),
              current.scope(), current.text(), current.pinned() || request.pinned(),
              normalizeTags(merged), current.hits() == Integer.MAX_VALUE ? Integer.MAX_VALUE
                  : current.hits() + 1);
          records.set(index, refreshed);
          String supersede = request.supersedesId().strip();
          int removed = supersede.isEmpty() ? 0 : removeById(records, supersede);
          write(path, records);
          String dedup = "deduped: refreshed existing record (hits=" + refreshed.hits() + ")";
          if (!supersede.isEmpty()) dedup += removed > 0 ? "; superseded " + supersede
              : "; supersede id " + supersede + " not found";
          return new AppendResult(current.id(), true, joinNotes(note, dedup), 0, "");
        }
      }

      String supersede = request.supersedesId().strip();
      boolean superseded = false;
      if (!supersede.isEmpty()) {
        superseded = removeById(records, supersede) > 0;
        if (!superseded) superseded = removeFromOtherScope(request.scope(), supersede);
        note = joinNotes(note, superseded ? "superseded " + supersede
            : "supersede id " + supersede + " not found (new record still written)");
      }
      StoredRecord created = new StoredRecord(ids.get(), clock.instant().getEpochSecond(),
          request.scope(), text, request.pinned(), tags, 0);
      int rolled = 0;
      while (records.size() + 1 > MAX_RECORDS) { dropOldest(records); rolled++; }
      records.add(created);
      while (records.size() > 1 && serializedBytes(records) > MAX_FILE_BYTES) {
        dropOldestExceptLast(records);
        rolled++;
      }
      write(path, records);
      return new AppendResult(created.id(), false, note, rolled, "");
    } catch (IOException exception) {
      return error("remember: " + message(exception));
    }
  }

  @Override public synchronized int forgetById(String id) {
    if (id == null || id.isBlank()) return 0;
    return removeAcross(record -> record.id().equals(id));
  }

  @Override public synchronized int forgetBySubstring(String substring) {
    if (substring == null || substring.isBlank()) return 0;
    return removeAcross(record -> record.text().contains(substring.strip()));
  }

  @Override public synchronized List<Record> previewForget(String substring) {
    if (substring == null || substring.isBlank()) return List.of();
    var result = new ArrayList<Record>();
    for (String scope : List.of("user", "project")) {
      Path path = pathFor(scope);
      if (path == null) continue;
      try {
        for (StoredRecord record : load(path)) if (record.text().contains(substring.strip()))
          result.add(new Record(record.id(), record.text()));
      } catch (IOException ignored) { }
    }
    return result;
  }

  @Override public synchronized OptionalInt wipe(String scope) {
    Path path = pathFor(scope);
    if (path == null) return OptionalInt.empty();
    try {
      int count = load(path).size();
      write(path, List.of());
      return OptionalInt.of(count);
    } catch (IOException exception) {
      return OptionalInt.empty();
    }
  }

  public synchronized List<StoredRecord> loadAll(String scope) {
    Path path = pathFor(scope);
    if (path == null) return List.of();
    try { return List.copyOf(load(path)); } catch (IOException exception) { return List.of(); }
  }

  public synchronized List<StoredRecord> loadRecent(String scope) {
    List<StoredRecord> all = loadAll(scope);
    return all.size() <= 50 ? all : List.copyOf(all.subList(all.size() - 50, all.size()));
  }

  public Path pathFor(String scope) {
    return switch (scope) {
      case "user" -> home == null ? null : home.resolve(".agentty/memory.jsonl");
      case "project" -> workspace == null ? null : workspace.resolve(".agentty/memory.jsonl");
      default -> null;
    };
  }

  private boolean removeFromOtherScope(String scope, String id) throws IOException {
    Path other = pathFor(scope.equals("user") ? "project" : "user");
    if (other == null) return false;
    List<StoredRecord> records = load(other);
    if (removeById(records, id) == 0) return false;
    write(other, records);
    return true;
  }

  private int removeAcross(java.util.function.Predicate<StoredRecord> predicate) {
    int removed = 0;
    for (String scope : List.of("user", "project")) {
      Path path = pathFor(scope);
      if (path == null) continue;
      try {
        List<StoredRecord> records = load(path);
        int before = records.size();
        records.removeIf(predicate);
        if (records.size() != before) write(path, records);
        removed += before - records.size();
      } catch (IOException ignored) { }
    }
    return removed;
  }

  private static List<StoredRecord> load(Path path) throws IOException {
    if (!Files.isRegularFile(path)) return new ArrayList<>();
    var records = new ArrayList<StoredRecord>();
    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      try {
        JsonNode node = JSON.readTree(line);
        String id = node.path("id").asText("");
        String scope = node.path("scope").asText("");
        String text = node.path("text").asText("");
        if (id.isEmpty() || text.isEmpty() || !(scope.equals("user") || scope.equals("project"))) continue;
        var tags = new ArrayList<String>();
        if (node.path("tags").isArray()) node.path("tags").forEach(tag -> {
          if (tag.isTextual() && !tag.textValue().isEmpty()) tags.add(tag.textValue());
        });
        records.add(new StoredRecord(id, node.path("ts").asLong(0), scope, text,
            node.path("pinned").isBoolean() && node.path("pinned").booleanValue(), tags,
            node.path("hits").isIntegralNumber() ? node.path("hits").intValue() : 0));
      } catch (RuntimeException | IOException ignored) { }
    }
    return records;
  }

  private static void write(Path path, List<StoredRecord> records) throws IOException {
    Files.createDirectories(path.getParent());
    Path temporary = Files.createTempFile(path.getParent(), "memory-", ".tmp");
    try {
      var output = new StringBuilder();
      for (StoredRecord record : records) output.append(serialize(record)).append('\n');
      Files.writeString(temporary, output, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
      try {
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static String serialize(StoredRecord record) throws IOException {
    ObjectNode node = JSON.createObjectNode().put("id", record.id()).put("ts", record.timestamp())
        .put("scope", record.scope()).put("text", record.text());
    if (record.pinned()) node.put("pinned", true);
    if (record.hits() > 0) node.put("hits", record.hits());
    if (!record.tags().isEmpty()) node.putArray("tags").addAll(record.tags().stream()
        .map(JSON.getNodeFactory()::textNode).toList());
    return JSON.writeValueAsString(node);
  }

  private static int serializedBytes(List<StoredRecord> records) throws IOException {
    int bytes = 0;
    for (StoredRecord record : records) bytes += serialize(record).getBytes(StandardCharsets.UTF_8).length + 1;
    return bytes;
  }

  private static int removeById(List<StoredRecord> records, String id) {
    int before = records.size();
    records.removeIf(record -> record.id().equals(id));
    return before - records.size();
  }

  private static void dropOldest(List<StoredRecord> records) {
    int index = -1;
    for (int candidate = 0; candidate < records.size(); candidate++)
      if (!records.get(candidate).pinned()) { index = candidate; break; }
    records.remove(index < 0 ? 0 : index);
  }
  private static void dropOldestExceptLast(List<StoredRecord> records) {
    int index = -1;
    for (int candidate = 0; candidate < records.size() - 1; candidate++)
      if (!records.get(candidate).pinned()) { index = candidate; break; }
    records.remove(index < 0 ? 0 : index);
  }

  static List<String> normalizeTags(List<String> tags) {
    var normalized = new LinkedHashSet<String>();
    tags.stream().map(String::strip).filter(tag -> !tag.isEmpty())
        .map(tag -> tag.toLowerCase(Locale.ROOT)).sorted().forEach(normalized::add);
    return List.copyOf(normalized);
  }
  static String normalizeText(String text) {
    return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
  }
  static double jaro(String left, String right) {
    if (left.equals(right)) return 1;
    if (left.isEmpty() || right.isEmpty()) return 0;
    int window = Math.max(left.length(), right.length()) / 2;
    boolean[] leftMatched = new boolean[left.length()];
    boolean[] rightMatched = new boolean[right.length()];
    int matches = 0;
    for (int i = 0; i < left.length(); i++) {
      for (int j = Math.max(0, i - window); j < Math.min(i + window + 1, right.length()); j++) {
        if (!rightMatched[j] && left.charAt(i) == right.charAt(j)) {
          leftMatched[i] = true; rightMatched[j] = true; matches++; break;
        }
      }
    }
    if (matches == 0) return 0;
    int target = 0;
    int transpositions = 0;
    for (int i = 0; i < left.length(); i++) if (leftMatched[i]) {
      while (!rightMatched[target]) target++;
      if (left.charAt(i) != right.charAt(target)) transpositions++;
      target++;
    }
    double count = matches;
    return (count / left.length() + count / right.length()
        + (count - transpositions / 2.0) / count) / 3.0;
  }
  static String truncateUtf8(String text, int maximumBytes) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= maximumBytes) return text;
    int end = maximumBytes;
    while (end > 0 && (bytes[end] & 0xC0) == 0x80) end--;
    return new String(bytes, 0, end, StandardCharsets.UTF_8);
  }

  private static AppendResult error(String error) { return new AppendResult("", false, "", 0, error); }
  private static String joinNotes(String left, String right) {
    return left.isEmpty() ? right : right.isEmpty() ? left : left + "; " + right;
  }
  private static String message(Exception exception) {
    return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
  }
}
