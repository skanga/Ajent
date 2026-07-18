package com.github.skanga.ajent.terminal.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Paint-measured accounting for an append-only sealed terminal prefix.
 * Estimates drive retention policy; only paint-recorded rows can mint scrollback debt.
 */
public final class ScrollbackLedger<T> {
  private final List<T> elements = new ArrayList<>();
  private final List<Metadata> metadata = new ArrayList<>();
  private long recordedTotal;
  private long estimatedTotal;
  private long debtRows;
  private int paintWidth;

  /** Typed row count whose construction remains private to this ledger. */
  public static final class ScrollbackDebt {
    private final int rows;
    private boolean consumed;

    private ScrollbackDebt(int rows) {
      this.rows = rows;
    }

    public int rows() {
      return rows;
    }

    public boolean empty() {
      return rows <= 0;
    }

    synchronized int consumeRows() {
      if (consumed) throw new IllegalStateException("scrollback debt already consumed");
      consumed = true;
      return rows;
    }
  }

  public void seal(T element, int estimatedRows, boolean separator) {
    elements.add(Objects.requireNonNull(element, "element"));
    int rows = Math.max(1, estimatedRows);
    metadata.add(new Metadata(-1, rows, separator));
    estimatedTotal += rows;
  }

  public void replace(int index, T element, int estimatedRows) {
    if (index < 0 || index >= elements.size()) return;
    elements.set(index, Objects.requireNonNull(element, "element"));
    Metadata previous = metadata.get(index);
    if (previous.recordedRows() >= 0) recordedTotal -= previous.recordedRows();
    estimatedTotal -= previous.estimatedRows();
    int rows = Math.max(1, estimatedRows);
    metadata.set(index, new Metadata(-1, rows, previous.separator()));
    estimatedTotal += rows;
  }

  /** Drops a paint-provable prefix at a separator-safe boundary. */
  public int dropFront(int requested) {
    int count = Math.clamp(requested, 0, elements.size());
    while (count < elements.size() && metadata.get(count).separator()) count++;
    int recordedPrefix = 0;
    while (recordedPrefix < elements.size()
        && metadata.get(recordedPrefix).recordedRows() >= 0) {
      recordedPrefix++;
    }
    count = Math.min(count, recordedPrefix);
    while (count > 0 && count < elements.size() && metadata.get(count).separator()) count--;
    if (count == 0) return 0;
    for (int index = 0; index < count; index++) {
      Metadata block = metadata.get(index);
      debtRows += block.recordedRows();
      recordedTotal -= block.recordedRows();
      estimatedTotal -= block.estimatedRows();
    }
    elements.subList(0, count).clear();
    metadata.subList(0, count).clear();
    return count;
  }

  /** Removes leading rehydrate separators; recorded rows still accrue as debt. */
  public int dropLeadingSeparators() {
    int count = 0;
    while (count < metadata.size() && metadata.get(count).separator()) count++;
    if (count == 0) return 0;
    for (int index = 0; index < count; index++) {
      Metadata block = metadata.get(index);
      if (block.recordedRows() >= 0) {
        debtRows += block.recordedRows();
        recordedTotal -= block.recordedRows();
      }
      estimatedTotal -= block.estimatedRows();
    }
    elements.subList(0, count).clear();
    metadata.subList(0, count).clear();
    return count;
  }

  public ScrollbackDebt harvest() {
    long rows = debtRows;
    debtRows = 0;
    return new ScrollbackDebt((int) Math.min(Integer.MAX_VALUE, Math.max(0, rows)));
  }

  public boolean hasDebt() {
    return debtRows > 0;
  }

  /** Clears content and debt while preserving the surface's last observed width. */
  public void clear() {
    elements.clear();
    metadata.clear();
    recordedTotal = 0;
    estimatedTotal = 0;
    debtRows = 0;
  }

  public int size() {
    return elements.size();
  }

  public boolean isEmpty() {
    return elements.isEmpty();
  }

  public long blockRows(int index) {
    if (index < 0 || index >= metadata.size()) return 0;
    Metadata block = metadata.get(index);
    return block.recordedRows() >= 0 ? block.recordedRows() : block.estimatedRows();
  }

  public long rowTotal() {
    long total = recordedTotal;
    for (Metadata block : metadata) {
      if (block.recordedRows() < 0) total += block.estimatedRows();
    }
    return Math.max(0, total);
  }

  public boolean separatorAt(int index) {
    return index >= 0 && index < metadata.size() && metadata.get(index).separator();
  }

  public boolean recordedAt(int index) {
    return index >= 0 && index < metadata.size() && metadata.get(index).recordedRows() >= 0;
  }

  public int paintWidth() {
    return paintWidth;
  }

  public List<T> elements() {
    return List.copyOf(elements);
  }

  public void recordPaint(int index, int rows) {
    if (index < 0 || index >= metadata.size() || rows < 0) return;
    Metadata previous = metadata.get(index);
    if (previous.recordedRows() >= 0) recordedTotal -= previous.recordedRows();
    metadata.set(index, new Metadata(rows, previous.estimatedRows(), previous.separator()));
    recordedTotal += rows;
  }

  public void recordPaintWidth(int columns) {
    if (columns > 0) paintWidth = columns;
  }

  private record Metadata(int recordedRows, int estimatedRows, boolean separator) {}
}
