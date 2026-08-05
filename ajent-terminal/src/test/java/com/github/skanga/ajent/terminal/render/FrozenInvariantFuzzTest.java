package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exact deterministic-walk translation of Ajent's frozen_invariant_fuzz.cpp. */
final class FrozenInvariantFuzzTest {
  @Test void arbitraryProductionCadencePreservesAllSevenFrozenPrefixInvariants() {
    int[] widths = {60, 80, 100, 140};
    for (int widthIndex = 0; widthIndex < widths.length; widthIndex++) {
      for (int walk = 0; walk < 120; walk++) {
        long seed = 0xa11ce5L + widthIndex * 1_000_003L + walk * 2_654_435_761L;
        runWalk(seed, widths[widthIndex]);
      }
    }
  }

  private static void runWalk(long seed, int width) {
    var random = new SplitMix64(seed);
    var model = new FrozenModel();
    int steps = 20 + random.below(40);
    for (int step = 0; step < steps; step++) {
      if (model.streaming && random.chance(60)) {
        model.messages.getLast().streaming = false;
        model.streaming = false;
      }
      int grow = model.streaming ? -1 : random.below(6);
      switch (grow) {
        case 0 -> model.messages.add(new FuzzMessage(1, false));
        case 1 -> model.messages.add(new FuzzMessage(1, false));
        case 2 -> model.messages.add(new FuzzMessage(4 + 2 * (2 + random.below(8)), false));
        case 3 -> model.messages.add(new FuzzMessage(2 + 20 + random.below(120), false));
        case 4 -> model.messages.add(new FuzzMessage(6, false));
        case 5 -> {
          model.messages.add(new FuzzMessage(1, true));
          model.streaming = true;
        }
        default -> { /* An active stream owns messages.back(), so no push is legal. */ }
      }

      FrozenScrollbackTrimPolicy.TrimResult trim = FrozenScrollbackTrimPolicy.TrimResult.none();
      int operation = random.below(7);
      switch (operation) {
        case 0 -> {
          int size = model.messages.size();
          int liveStart = size == 0 ? 0 : random.below(size + 1);
          if (model.streaming && liveStart > size - 1) liveStart = size - 1;
          model.freezeThrough(liveStart);
        }
        case 1, 2 -> { /* Retired carve operations remain no-ops to preserve native seeds. */ }
        case 3, 4 -> {
          for (int index = 0; index < model.ledger.size(); index++) {
            model.ledger.recordPaint(index, Math.toIntExact(model.ledger.blockRows(index)));
          }
          trim = FrozenScrollbackTrimPolicy.trim(model.ledger, 40);
        }
        case 5 -> {
          if (!model.streaming) model.rehydrate();
        }
        default -> { /* no frozen operation */ }
      }

      checkInvariants(model, trim, seed, step);
      if (random.chance(20)) renderSmoke(model, width);
    }
  }

  private static void checkInvariants(FrozenModel model,
      FrozenScrollbackTrimPolicy.TrimResult trim, long seed, int step) {
    String context = "seed=" + Long.toUnsignedString(seed) + " step=" + step;
    long sum = 0;
    for (int index = 0; index < model.ledger.size(); index++) {
      assertThat(model.ledger.blockRows(index)).as("I1/I7 " + context)
          .isGreaterThanOrEqualTo(0);
      assertThat(model.ledger.recordedAt(index) || model.ledger.blockRows(index) >= 1)
          .as("I1 " + context).isTrue();
      sum += model.ledger.blockRows(index);
    }
    assertThat(model.ledger.rowTotal()).as("I2 " + context).isEqualTo(sum);
    assertThat(model.frozenThrough).as("I3 " + context)
        .isLessThanOrEqualTo(model.messages.size());
    if (!model.ledger.isEmpty()) {
      assertThat(model.ledger.separatorAt(0)).as("I4 " + context).isFalse();
    }
    if (model.streaming && !model.messages.isEmpty()) {
      assertThat(model.frozenThrough).as("I5 " + context)
          .isLessThan(model.messages.size());
    }
    assertThat(trim.committedRows()).as("I6 " + context).isBetween(0L, trim.droppedRows());
    if (trim.droppedRows() > 0) {
      assertThat(trim.committedRows()).as("I6 " + context).isPositive();
    } else {
      assertThat(trim.committedRows()).as("I6 " + context).isZero();
    }
  }

  private static void renderSmoke(FrozenModel model, int width) {
    int rows = (int) Math.min(600, Math.max(1, model.ledger.rowTotal()));
    var canvas = new TerminalCanvas(width, rows);
    for (int row = 0; row < rows; row++) canvas.writeText(0, row, "frozen-" + row, 0);
    assertThat(CanvasSerializer.contentRows(canvas).value()).isEqualTo(rows);
  }

  private static final class FrozenModel {
    private final List<FuzzMessage> messages = new ArrayList<>();
    private final ScrollbackLedger<String> ledger = new ScrollbackLedger<>();
    private int frozenThrough;
    private boolean streaming;

    private void freezeThrough(int requested) {
      int limit = Math.clamp(requested, frozenThrough, messages.size());
      if (streaming) limit = Math.min(limit, Math.max(0, messages.size() - 1));
      for (int index = frozenThrough; index < limit; index++) {
        if (!ledger.isEmpty()) ledger.seal("gap-" + index, 1, true);
        ledger.seal("message-" + index, messages.get(index).rows, false);
      }
      frozenThrough = limit;
    }

    private void rehydrate() {
      ledger.clear();
      frozenThrough = 0;
      freezeThrough(messages.size());
      ledger.dropLeadingSeparators();
      ledger.harvest();
    }
  }

  private static final class FuzzMessage {
    private final int rows;
    private boolean streaming;

    private FuzzMessage(int rows, boolean streaming) {
      this.rows = rows;
      this.streaming = streaming;
    }
  }

  private static final class SplitMix64 {
    private long state;

    private SplitMix64(long seed) {
      state = seed;
    }

    private long next() {
      state += 0x9e3779b97f4a7c15L;
      long value = state;
      value = (value ^ value >>> 30) * 0xbf58476d1ce4e5b9L;
      value = (value ^ value >>> 27) * 0x94d049bb133111ebL;
      return value ^ value >>> 31;
    }

    private int below(int bound) {
      return bound <= 0 ? 0 : (int) Long.remainderUnsigned(next(), bound);
    }

    private boolean chance(int percent) {
      return below(100) < percent;
    }
  }
}
