package com.github.skanga.ajent.parity;

import com.github.skanga.ajent.terminal.render.UnicodeWidth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic ANSI cell emulator used by executable terminal differentials. */
final class AnsiViewport {
  private static final String CONTINUATION = "\u0000";

  private final int columns;
  private final int rows;
  private final List<String[]> screen = new ArrayList<>();
  private final List<String> scrollback = new ArrayList<>();
  private int column;
  private int row;
  private int savedColumn;
  private int savedRow;
  private boolean autoWrap = true;

  AnsiViewport(int columns, int rows) {
    if (columns <= 0 || rows <= 0) throw new IllegalArgumentException("positive geometry required");
    this.columns = columns;
    this.rows = rows;
    for (int index = 0; index < rows; index++) screen.add(blankLine());
  }

  void feed(String input) {
    for (int offset = 0; offset < input.length();) {
      int codePoint = input.codePointAt(offset);
      offset += Character.charCount(codePoint);
      if (codePoint == '\r') { column = 0; continue; }
      if (codePoint == '\n') { newline(); continue; }
      if (codePoint == '\b') { column = Math.max(0, column - 1); continue; }
      if (codePoint == '\t') { column = Math.min(columns, (column / 8 + 1) * 8); continue; }
      if (codePoint == 0x1b) {
        offset = parseEscape(input, offset);
        continue;
      }
      if (codePoint < 0x20 || codePoint == 0x7f) continue;
      put(codePoint);
    }
  }

  List<String> lines() {
    return screen.stream().map(AnsiViewport::render).toList();
  }

  List<String> scrollback() { return List.copyOf(scrollback); }

  private int parseEscape(String input, int offset) {
    if (offset >= input.length()) return offset;
    char introducer = input.charAt(offset);
    if (introducer == '[') return parseCsi(input, offset + 1);
    if (introducer == ']') return skipOsc(input, offset + 1);
    if (introducer == '7') { saveCursor(); return offset + 1; }
    if (introducer == '8') { restoreCursor(); return offset + 1; }
    return offset + 1;
  }

  private int parseCsi(String input, int offset) {
    int cursor = offset;
    while (cursor < input.length()) {
      char value = input.charAt(cursor);
      if (value >= 0x40 && value <= 0x7e) {
        applyCsi(value, input.substring(offset, cursor));
        return cursor + 1;
      }
      cursor++;
    }
    return cursor;
  }

  private int skipOsc(String input, int offset) {
    int cursor = offset;
    while (cursor < input.length()) {
      char value = input.charAt(cursor++);
      if (value == 0x07) return cursor;
      if (value == 0x1b && cursor < input.length() && input.charAt(cursor) == '\\') {
        return cursor + 1;
      }
    }
    return cursor;
  }

  private void applyCsi(char command, String rawParameters) {
    boolean privateMode = rawParameters.startsWith("?");
    List<Integer> parameters = parameters(rawParameters);
    int first = first(parameters, 1);
    switch (command) {
      case 'A' -> row = Math.max(0, row - first);
      case 'B' -> row = Math.min(rows - 1, row + first);
      case 'C' -> column = Math.min(columns, column + first);
      case 'D' -> column = Math.max(0, column - first);
      case 'E' -> { row = Math.min(rows - 1, row + first); column = 0; }
      case 'F' -> { row = Math.max(0, row - first); column = 0; }
      case 'G' -> column = Math.clamp(first - 1, 0, columns);
      case 'H', 'f' -> {
        int targetRow = parameters.isEmpty() ? 1 : Math.max(1, parameters.getFirst());
        int targetColumn = parameters.size() < 2 ? 1 : Math.max(1, parameters.get(1));
        row = Math.clamp(targetRow - 1, 0, rows - 1);
        column = Math.clamp(targetColumn - 1, 0, columns);
      }
      case 'J' -> eraseDisplay(parameters.isEmpty() ? 0 : parameters.getFirst());
      case 'K' -> eraseLine(parameters.isEmpty() ? 0 : parameters.getFirst());
      case 'S' -> scrollUp(first);
      case 'T' -> scrollDown(first);
      case 's' -> saveCursor();
      case 'u' -> { if (!rawParameters.startsWith(">") && rawParameters.isEmpty()) restoreCursor(); }
      case 'h' -> { if (privateMode && parameters.contains(7)) autoWrap = true; }
      case 'l' -> { if (privateMode && parameters.contains(7)) autoWrap = false; }
      default -> { /* SGR, synchronized output, and cursor visibility do not alter cells. */ }
    }
  }

  private static List<Integer> parameters(String raw) {
    String numeric = raw.replaceFirst("^[?<>=!]+", "");
    if (numeric.isEmpty()) return List.of();
    var values = new ArrayList<Integer>();
    for (String item : numeric.split(";", -1)) {
      int colon = item.indexOf(':');
      if (colon >= 0) item = item.substring(0, colon);
      try { values.add(item.isEmpty() ? 0 : Integer.parseInt(item)); }
      catch (NumberFormatException ignored) { values.add(0); }
    }
    return List.copyOf(values);
  }

  private static int first(List<Integer> parameters, int fallback) {
    return parameters.isEmpty() || parameters.getFirst() == 0 ? fallback : parameters.getFirst();
  }

  private void put(int codePoint) {
    int width = UnicodeWidth.of(codePoint);
    if (width <= 0) {
      int target = Math.min(columns - 1, Math.max(0, column - 1));
      String current = screen.get(row)[target];
      if (!current.equals(CONTINUATION)) screen.get(row)[target] = current
          + new String(Character.toChars(codePoint));
      return;
    }
    if (column + width > columns) {
      if (autoWrap) { column = 0; newline(); }
      else column = Math.max(0, columns - width);
    }
    String[] line = screen.get(row);
    line[column] = new String(Character.toChars(codePoint));
    for (int index = 1; index < width && column + index < columns; index++) {
      line[column + index] = CONTINUATION;
    }
    column += width;
  }

  private void newline() {
    if (row < rows - 1) { row++; return; }
    scrollUp(1);
  }

  private void scrollUp(int count) {
    for (int index = 0; index < count; index++) {
      scrollback.add(render(screen.removeFirst()));
      screen.add(blankLine());
    }
  }

  private void scrollDown(int count) {
    for (int index = 0; index < count; index++) {
      screen.removeLast();
      screen.addFirst(blankLine());
    }
  }

  private void eraseLine(int mode) {
    String[] line = screen.get(row);
    if (mode == 2) Arrays.fill(line, " ");
    else if (mode == 1) Arrays.fill(line, 0, Math.min(columns, column + 1), " ");
    else Arrays.fill(line, Math.min(column, columns), columns, " ");
  }

  private void eraseDisplay(int mode) {
    if (mode == 3) scrollback.clear();
    else if (mode == 2) for (String[] line : screen) Arrays.fill(line, " ");
    else {
      eraseLine(0);
      for (int index = row + 1; index < rows; index++) Arrays.fill(screen.get(index), " ");
    }
  }

  private void saveCursor() { savedColumn = column; savedRow = row; }

  private void restoreCursor() { column = savedColumn; row = savedRow; }

  private String[] blankLine() {
    String[] line = new String[columns];
    Arrays.fill(line, " ");
    return line;
  }

  private static String render(String[] line) {
    int end = line.length;
    while (end > 0 && (line[end - 1].equals(" ") || line[end - 1].equals(CONTINUATION))) end--;
    var result = new StringBuilder();
    for (int index = 0; index < end; index++) {
      if (!line[index].equals(CONTINUATION)) result.append(line[index]);
    }
    return result.toString();
  }
}
