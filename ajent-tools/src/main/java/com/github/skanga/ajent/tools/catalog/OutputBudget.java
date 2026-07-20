package com.github.skanga.ajent.tools.catalog;

import java.nio.charset.StandardCharsets;

/** UTF-8-safe dispatcher output truncation compatible with AgenTTY. */
public final class OutputBudget {
  private OutputBudget() {}

  public static String apply(String text, int budget, TruncationStrategy strategy) {
    if (budget <= 0) return text;
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= budget) return text;

    return switch (strategy) {
      case HEAD -> {
        int cut = safeFloor(bytes, budget);
        yield slice(bytes, 0, cut)
            + "\n\n[... " + (bytes.length - cut)
            + " chars elided — output exceeded tool's budget; refine your request to see more ...]";
      }
      case TAIL -> {
        int cut = safeCeiling(bytes, bytes.length - budget);
        yield "[... " + cut + " chars elided from start — showing tail of output ...]\n\n"
            + slice(bytes, cut, bytes.length);
      }
      case HEAD_TAIL -> {
        int headSize = (budget * 7) / 10;
        int tailSize = budget - headSize;
        int headCut = safeFloor(bytes, headSize);
        int tailStart = safeCeiling(bytes, bytes.length - tailSize);
        if (tailStart <= headCut) {
          yield slice(bytes, 0, headCut) + "\n\n[... "
              + (bytes.length - headCut) + " chars elided ...]";
        }
        yield slice(bytes, 0, headCut) + "\n\n[... "
            + (tailStart - headCut) + " chars elided from middle ...]\n\n"
            + slice(bytes, tailStart, bytes.length);
      }
    };
  }

  /** mcp-cpp provider-layer cap applied before AgenTTY's strategy-aware dispatch cap. */
  public static String applyProvider(String text, int budget) {
    if (budget <= 0) return text;
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= budget) return text;
    int cut = safeFloor(bytes, budget);
    return slice(bytes, 0, cut) + "\n\n[... " + (bytes.length - cut) + " chars elided ...]";
  }

  private static int safeFloor(byte[] value, int index) {
    if (index >= value.length) return value.length;
    for (int back = 0; back < 4 && index > 0; back++, index--) {
      if (!isContinuation(value[index])) return index;
    }
    return index;
  }

  private static int safeCeiling(byte[] value, int index) {
    if (index >= value.length) return value.length;
    for (int forward = 0; forward < 4 && index < value.length; forward++, index++) {
      if (!isContinuation(value[index])) return index;
    }
    return index;
  }

  private static boolean isContinuation(byte value) {
    return (value & 0xc0) == 0x80;
  }

  private static String slice(byte[] value, int start, int end) {
    return new String(value, start, end - start, StandardCharsets.UTF_8);
  }
}
