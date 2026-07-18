package com.github.skanga.ajent.terminal.render;

/** Deterministic ANSI serialization and packed-row diff primitives. */
public final class CanvasSerializer {
  /** Typed witness that a row count was derived from this exact canvas. */
  public static final class ContentRows {
    private final int value;
    private final TerminalCanvas source;

    private ContentRows(TerminalCanvas source) {
      this.source = source;
      value = contentHeight(source);
    }

    public int value() { return value; }

    public boolean belongsTo(TerminalCanvas canvas) { return source == canvas; }
  }

  private CanvasSerializer() {}

  public static int contentHeight(TerminalCanvas canvas) {
    return java.util.Objects.requireNonNull(canvas, "canvas").maxContentRow() + 1;
  }

  public static ContentRows contentRows(TerminalCanvas canvas) {
    return new ContentRows(java.util.Objects.requireNonNull(canvas, "canvas"));
  }

  public static String serialize(TerminalCanvas canvas, TerminalStylePool styles) {
    return serialize(canvas, styles, 0, 0);
  }

  public static String serialize(TerminalCanvas canvas, TerminalStylePool styles,
      int rows, int startRow) {
    java.util.Objects.requireNonNull(canvas, "canvas");
    java.util.Objects.requireNonNull(styles, "styles");
    int width = canvas.width();
    int totalRows = rows > 0 ? Math.min(rows, canvas.height()) : canvas.height();
    int begin = Math.clamp(startRow, 0, totalRows);
    if (width <= 0 || begin >= totalRows) return "";

    var output = new StringBuilder();
    int currentStyle = TerminalStylePool.UNKNOWN_STYLE;
    output.append("\u001b[?7l");
    for (int row = begin; row < totalRows; row++) {
      if (row > begin) output.append("\r\n");
      int lastColumn = canvas.lastContentColumn(row);
      if (lastColumn >= 0) {
        currentStyle = emitCellRun(canvas, styles, row, 0, lastColumn + 1,
            currentStyle, output);
      }
      if (currentStyle != 0) {
        output.append(styles.sgr(0));
        currentStyle = 0;
      }
      if (lastColumn < width - 1) output.append("\u001b[K");
    }
    return output.append("\u001b[?7h\u001b[0m").toString();
  }

  public static int firstDifference(long[] current, long[] previous) {
    requireSameLength(current, previous);
    for (int index = 0; index < current.length; index++) {
      if (current[index] != previous[index]) return index;
    }
    return current.length;
  }

  public static int lastDifference(long[] current, long[] previous) {
    requireSameLength(current, previous);
    for (int index = current.length - 1; index >= 0; index--) {
      if (current[index] != previous[index]) return index;
    }
    return -1;
  }

  public static int snapFirstDifferenceLeft(int column, long[] current, long[] previous) {
    requireSameLength(current, previous);
    if (column <= 0 || column >= current.length) return column;
    return width(current[column]) == 2 || width(previous[column]) == 2 ? column - 1 : column;
  }

  public static int snapLastDifferenceRight(int column, long[] current, long[] previous) {
    requireSameLength(current, previous);
    if (column < 0 || column >= current.length - 1) return column;
    return width(current[column]) == 1 || width(previous[column]) == 1 ? column + 1 : column;
  }

  static int emitCellRun(TerminalCanvas canvas, TerminalStylePool styles, int row,
      int begin, int end, int currentStyle, StringBuilder output) {
    for (int column = begin; column < end; column++) {
      PackedCell cell = PackedCell.unpack(canvas.getPacked(column, row));
      if (cell.isWideTrail()) continue;
      if (cell.styleId() != currentStyle) {
        styles.appendTransition(currentStyle, cell.styleId(), output);
        currentStyle = cell.styleId();
      }
      output.appendCodePoint(cell.character());
    }
    return currentStyle;
  }

  private static int width(long packed) { return (int) ((packed >>> 56) & 0xff); }

  private static void requireSameLength(long[] current, long[] previous) {
    java.util.Objects.requireNonNull(current, "current");
    java.util.Objects.requireNonNull(previous, "previous");
    if (current.length != previous.length) {
      throw new IllegalArgumentException("row buffers must have equal lengths");
    }
  }
}
