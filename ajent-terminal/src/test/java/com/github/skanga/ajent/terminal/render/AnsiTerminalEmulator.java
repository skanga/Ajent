package com.github.skanga.ajent.terminal.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Small byte-faithful ANSI viewport/scrollback oracle translated from AgenTTY's tests. */
final class AnsiTerminalEmulator {
  private final int columns;
  private final int rows;
  private final List<char[]> screen = new ArrayList<>();
  private final List<String> scrollback = new ArrayList<>();
  private int column;
  private int row;
  private boolean autoWrap = true;

  AnsiTerminalEmulator(int columns, int rows) {
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
      if (codePoint == 0x1b) {
        offset = parseEscape(input, offset);
        continue;
      }
      if (codePoint < 0x20) continue;
      put(codePoint, UnicodeWidth.of(codePoint));
    }
  }

  List<String> transcript() {
    var result = new ArrayList<>(scrollback);
    for (char[] line : screen) result.add(trim(line));
    return result;
  }

  long countContaining(String marker) {
    return transcript().stream().filter(line -> line.contains(marker)).count();
  }

  private int parseEscape(String input, int offset) {
    if (offset >= input.length()) return offset;
    if (input.charAt(offset) != '[') return offset + 1;
    int cursor = offset + 1;
    boolean privateMode = cursor < input.length() && input.charAt(cursor) == '?';
    if (privateMode) cursor++;
    var parameters = new ArrayList<Integer>();
    int value = 0;
    boolean haveValue = false;
    while (cursor < input.length()) {
      char character = input.charAt(cursor);
      if (Character.isDigit(character)) {
        value = value * 10 + character - '0';
        haveValue = true;
        cursor++;
      } else if (character == ';') {
        parameters.add(haveValue ? value : 0);
        value = 0;
        haveValue = false;
        cursor++;
      } else {
        if (haveValue || !parameters.isEmpty()) parameters.add(haveValue ? value : 0);
        applyCsi(character, privateMode, parameters);
        return cursor + 1;
      }
    }
    return cursor;
  }

  private void applyCsi(char command, boolean privateMode, List<Integer> parameters) {
    int first = parameters.isEmpty() || parameters.getFirst() == 0 ? 1 : parameters.getFirst();
    switch (command) {
      case 'A' -> row = Math.max(0, row - first);
      case 'B' -> { for (int count = 0; count < first; count++) newline(); }
      case 'C' -> column = Math.min(columns, column + first);
      case 'D' -> column = Math.max(0, column - first);
      case 'G' -> column = Math.clamp(first - 1, 0, columns);
      case 'H', 'f' -> {
        int targetRow = parameters.isEmpty() ? 1 : Math.max(1, parameters.getFirst());
        int targetColumn = parameters.size() < 2 ? 1 : Math.max(1, parameters.get(1));
        row = Math.clamp(targetRow - 1, 0, rows - 1);
        column = Math.clamp(targetColumn - 1, 0, columns);
      }
      case 'K' -> eraseLine(parameters.isEmpty() ? 0 : parameters.getFirst());
      case 'J' -> eraseDisplay(parameters.isEmpty() ? 0 : parameters.getFirst());
      case 'h' -> { if (privateMode && parameters.contains(7)) autoWrap = true; }
      case 'l' -> { if (privateMode && parameters.contains(7)) autoWrap = false; }
      default -> { /* SGR, synchronized output, and cursor visibility do not alter cells. */ }
    }
  }

  private void put(int codePoint, int cellWidth) {
    if (cellWidth <= 0) return;
    if (column + cellWidth > columns) {
      if (autoWrap) {
        column = 0;
        newline();
      } else {
        column = Math.max(0, columns - cellWidth);
      }
    }
    char visible = codePoint < 128 ? (char) codePoint : '?';
    screen.get(row)[column] = visible;
    for (int index = 1; index < cellWidth && column + index < columns; index++) {
      screen.get(row)[column + index] = codePoint < 128 ? ' ' : '?';
    }
    column += cellWidth;
  }

  private void newline() {
    if (row < rows - 1) {
      row++;
      return;
    }
    scrollback.add(trim(screen.removeFirst()));
    screen.add(blankLine());
  }

  private void eraseLine(int mode) {
    char[] line = screen.get(row);
    if (mode == 2) Arrays.fill(line, ' ');
    else if (mode == 1) Arrays.fill(line, 0, Math.min(columns, column + 1), ' ');
    else Arrays.fill(line, Math.min(column, columns), columns, ' ');
  }

  private void eraseDisplay(int mode) {
    if (mode == 3) {
      scrollback.clear();
    } else if (mode == 2) {
      for (char[] line : screen) Arrays.fill(line, ' ');
    } else {
      eraseLine(0);
      for (int index = row + 1; index < rows; index++) Arrays.fill(screen.get(index), ' ');
    }
  }

  private char[] blankLine() {
    char[] line = new char[columns];
    Arrays.fill(line, ' ');
    return line;
  }

  private static String trim(char[] line) {
    int end = line.length;
    while (end > 0 && line[end - 1] == ' ') end--;
    return new String(line, 0, end);
  }
}
