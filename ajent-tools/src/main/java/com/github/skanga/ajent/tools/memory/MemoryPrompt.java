package com.github.skanga.ajent.tools.memory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bounded prompt projection of durable memory records. */
public final class MemoryPrompt {
  public static final int BYTE_BUDGET = 6 * 1024;
  public static final int RECORD_CAP = 400;
  public record Selection(List<JsonlMemoryStore.StoredRecord> records, int dropped) {
    public Selection { records = List.copyOf(records); }
  }

  private MemoryPrompt() {}

  public static Selection select(List<JsonlMemoryStore.StoredRecord> recent, int byteBudget) {
    if (recent.isEmpty()) return new Selection(List.of(), 0);
    var order = new ArrayList<Integer>();
    for (int index = 0; index < recent.size(); index++) order.add(index);
    order.sort(Comparator.<Integer, Boolean>comparing(index -> recent.get(index).pinned()).reversed()
        .thenComparing(index -> recent.get(index).hits(), Comparator.reverseOrder())
        .thenComparing(index -> recent.get(index).timestamp(), Comparator.reverseOrder()));
    boolean[] keep = new boolean[recent.size()];
    int spent = 0;
    for (int index : order) {
      JsonlMemoryStore.StoredRecord record = recent.get(index);
      int cost = Math.min(utf8Length(record.text()), RECORD_CAP) + 16
          + record.tags().stream().mapToInt(tag -> utf8Length(tag) + 2).sum();
      if (record.pinned() || spent + cost <= byteBudget) {
        keep[index] = true;
        spent += cost;
      }
    }
    var selected = new ArrayList<JsonlMemoryStore.StoredRecord>();
    int dropped = 0;
    for (int index = 0; index < recent.size(); index++) {
      if (!keep[index]) { dropped++; continue; }
      JsonlMemoryStore.StoredRecord record = recent.get(index);
      selected.add(new JsonlMemoryStore.StoredRecord(record.id(), record.timestamp(), record.scope(),
          clip(record.text(), RECORD_CAP), record.pinned(), record.tags(), record.hits()));
    }
    return new Selection(selected, dropped);
  }

  public static Selection select(List<JsonlMemoryStore.StoredRecord> recent) {
    return select(recent, BYTE_BUDGET);
  }

  public static String render(JsonlMemoryStore.StoredRecord record) {
    var output = new StringBuilder("[").append(record.id()).append("] ");
    if (record.pinned()) output.append("★ ");
    output.append(record.text());
    if (!record.tags().isEmpty()) output.append("  {").append(String.join(", ", record.tags()))
        .append('}');
    return output.toString();
  }

  public static String block(String scope, List<JsonlMemoryStore.StoredRecord> recent) {
    Selection selection = select(recent);
    if (selection.records().isEmpty() && selection.dropped() == 0) return "";
    var output = new StringBuilder("<learned-memory scope=\"").append(scope).append("\">\n")
        .append("Facts saved by the user or learned in earlier sessions:\n");
    selection.records().forEach(record -> output.append(render(record)).append('\n'));
    if (selection.dropped() > 0) output.append("[+").append(selection.dropped())
        .append(" more stored fact(s) not shown within prompt budget]\n");
    return output.append("</learned-memory>\n").toString();
  }

  private static String clip(String text, int maximumBytes) {
    if (utf8Length(text) <= maximumBytes) return text;
    return JsonlMemoryStore.truncateUtf8(text, maximumBytes) + "…";
  }
  private static int utf8Length(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
}
