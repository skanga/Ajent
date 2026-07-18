package com.github.skanga.ajent.terminal.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Hard-wraps text by terminal display columns without splitting code points. */
public final class ColumnTextWrapper {
  private ColumnTextWrapper() {}

  public static List<String> wrap(String text, int columns) {
    Objects.requireNonNull(text, "text");
    int width = Math.max(1, columns);
    var lines = new ArrayList<String>();
    for (String logical : text.split("\\R", -1)) {
      if (logical.isEmpty()) {
        lines.add("");
        continue;
      }
      int start = 0;
      while (start < logical.length()) {
        int end = start;
        int occupied = 0;
        while (end < logical.length()) {
          int codePoint = logical.codePointAt(end);
          int cellWidth = UnicodeWidth.of(codePoint);
          if (occupied > 0 && occupied + cellWidth > width) break;
          end += Character.charCount(codePoint);
          occupied += cellWidth;
          if (occupied >= width) break;
        }
        // A leading zero-width run still makes byte progress, and a glyph wider than a
        // one-column viewport is handed to the clipping canvas as one indivisible unit.
        if (end == start) end += Character.charCount(logical.codePointAt(start));
        lines.add(logical.substring(start, end));
        start = end;
      }
    }
    return List.copyOf(lines);
  }
}
