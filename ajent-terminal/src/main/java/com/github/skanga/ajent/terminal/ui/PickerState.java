package com.github.skanga.ajent.terminal.ui;

import java.util.Objects;

/** Sum-typed picker state and Ajent's ASCII-only incremental filter. */
public final class PickerState {
  private PickerState() {}

  public sealed interface OneAxis permits Closed, OpenAt {}

  public record Closed() implements OneAxis {}

  public record OpenAt(int index, String query) implements OneAxis {
    public OpenAt {
      Objects.requireNonNull(query, "query");
    }

    public OpenAt() {
      this(0, "");
    }
  }

  public sealed interface TwoAxis permits CellClosed, OpenAtCell {}

  public record CellClosed() implements TwoAxis {}

  public record OpenAtCell(int fileIndex, int hunkIndex) implements TwoAxis {}

  public sealed interface Modal permits ModalClosed, OpenModal {}

  public record ModalClosed() implements Modal {}

  public record OpenModal() implements Modal {}

  public static boolean fuzzyContains(String haystack, String needle) {
    Objects.requireNonNull(haystack, "haystack");
    Objects.requireNonNull(needle, "needle");
    if (needle.isEmpty()) return true;
    if (needle.length() > haystack.length()) return false;
    int last = haystack.length() - needle.length();
    for (int start = 0; start <= last; start++) {
      int offset = 0;
      while (offset < needle.length()
          && asciiLower(haystack.charAt(start + offset)) == asciiLower(needle.charAt(offset))) {
        offset++;
      }
      if (offset == needle.length()) return true;
    }
    return false;
  }

  private static char asciiLower(char value) {
    return value >= 'A' && value <= 'Z' ? (char) (value + ('a' - 'A')) : value;
  }
}
