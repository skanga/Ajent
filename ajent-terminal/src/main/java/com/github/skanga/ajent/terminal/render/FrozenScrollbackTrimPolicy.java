package com.github.skanga.ajent.terminal.render;

import java.util.Objects;
import java.util.Optional;

/** Native frozen-prefix retention policy; accounting remains owned by {@link ScrollbackLedger}. */
public final class FrozenScrollbackTrimPolicy {
  private static final int MAX_ENTRIES = 120;
  private static final int MIN_KEEP_ENTRIES = 8;

  public record TrimResult(int droppedBlocks, long droppedRows,
                           Optional<ScrollbackLedger.ScrollbackDebt> debt) {
    public TrimResult {
      if (droppedBlocks < 0 || droppedRows < 0) {
        throw new IllegalArgumentException("trim counts cannot be negative");
      }
      debt = Objects.requireNonNull(debt, "debt");
    }

    public static TrimResult none() {
      return new TrimResult(0, 0, Optional.empty());
    }

    public long committedRows() {
      return debt.map(ScrollbackLedger.ScrollbackDebt::rows).orElse(0);
    }
  }

  private FrozenScrollbackTrimPolicy() {}

  public static <T> TrimResult trim(ScrollbackLedger<T> ledger, int terminalRows) {
    Objects.requireNonNull(ledger, "ledger");
    if (terminalRows <= 0) throw new IllegalArgumentException("terminal rows must be positive");
    long maxRows = Math.max(48L, terminalRows * 3L);
    if (ledger.rowTotal() <= maxRows && ledger.size() <= MAX_ENTRIES) return TrimResult.none();

    int budgetEntries = 0;
    long keepRows = 0;
    for (int index = ledger.size() - 1; index >= 0; index--) {
      budgetEntries++;
      keepRows += ledger.blockRows(index);
      if (keepRows >= maxRows) break;
    }
    int keepEntries = keepRows >= maxRows
        ? budgetEntries : Math.max(budgetEntries, MIN_KEEP_ENTRIES);
    keepEntries = Math.min(ledger.size(), Math.max(2, keepEntries));

    int drop = 0;
    int maxDrop = ledger.size() - keepEntries;
    long rowsAfter = ledger.rowTotal();
    int entriesAfter = ledger.size();
    while (drop < maxDrop && (rowsAfter > maxRows || entriesAfter > MAX_ENTRIES)) {
      rowsAfter -= ledger.blockRows(drop);
      entriesAfter--;
      drop++;
    }
    if (drop == 0) return TrimResult.none();

    long before = ledger.rowTotal();
    int droppedBlocks = ledger.dropFront(drop);
    long droppedRows = before - ledger.rowTotal();
    if (droppedBlocks == 0) return TrimResult.none();
    ScrollbackLedger.ScrollbackDebt debt = ledger.harvest();
    return new TrimResult(droppedBlocks, droppedRows,
        debt.empty() ? Optional.empty() : Optional.of(debt));
  }
}
