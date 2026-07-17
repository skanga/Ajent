package com.github.skanga.ajent.tools.edit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Line-oriented dynamic-programming matcher ported from AgenTTY's edit tool. */
public final class FuzzyMatcher {
  private static final int REPLACEMENT_COST = 1;
  private static final int INSERTION_COST = 3;
  private static final int DELETION_COST = 10;
  private static final double FUZZY_EQUAL_THRESHOLD = 0.8;
  private static final double MATCH_RATIO = 0.8;
  private static final int LINE_HINT_TOLERANCE = 200;
  private static final long MAX_DP_CELLS = 2_000_000L;
  private static final int NO_HINT = Integer.MAX_VALUE;

  private FuzzyMatcher() {}

  public static FuzzyMatch find(String file, String needle) {
    return find(file, needle, "", NO_HINT);
  }

  public static FuzzyMatch find(String file, String needle, String newText) {
    return find(file, needle, newText, NO_HINT);
  }

  public static FuzzyMatch find(String file, String needle, String newText, int lineHint) {
    if (needle.isEmpty()) return failed(0);
    int exactCount = countOccurrences(file, needle);
    if (exactCount == 1) {
      int position = file.indexOf(needle);
      return new FuzzyMatch(true, position, needle.length(), 1, "", 1);
    }
    List<Line> fileLines = scanLines(file);
    List<Line> needleLines = scanLines(needle);
    List<DpMatch> matches = runLineDp(file, needle, fileLines, needleLines);
    if (matches.isEmpty()) return failed(exactCount >= 2 ? exactCount : 0);
    DpMatch selected = null;
    if (matches.size() == 1) {
      selected = matches.getFirst();
    } else if (lineHint != NO_HINT) {
      int bestDistance = Integer.MAX_VALUE;
      for (DpMatch match : matches) {
        int distance = Math.abs(match.rowStart - lineHint);
        if (distance <= LINE_HINT_TOLERANCE && distance < bestDistance) {
          bestDistance = distance;
          selected = match;
        }
      }
    }
    if (selected == null) return failed(matches.size());
    Line startLine = fileLines.get(selected.rowStart);
    Line endLine = fileLines.get(selected.rowEnd);
    int position = startLine.start;
    int end = endLine.end;
    if (!needle.endsWith("\n") && end > position && file.charAt(end - 1) == '\n') end--;
    String adjusted = "";
    if (!newText.isEmpty()) {
      IndentDelta delta = detectIndentDelta(
          file, needle, fileLines, selected.rowStart, selected.rowEnd + 1, needleLines);
      if (delta.have && !delta.needleBase.equals(delta.fileBase)) {
        adjusted = applyIndentDelta(newText, delta);
      }
    }
    return new FuzzyMatch(true, position, end - position, 1, adjusted,
        selected.cost == 0 ? 1 : 2);
  }

  private static List<DpMatch> runLineDp(
      String file, String needle, List<Line> fileLines, List<Line> needleLines) {
    int queryRows = needleLines.size();
    int bufferRows = fileLines.size();
    int columns = bufferRows + 1;
    int rows = queryRows + 1;
    if (queryRows == 0 || bufferRows == 0 || (long) rows * columns > MAX_DP_CELLS) return List.of();
    List<String> trimmedNeedle = needleLines.stream().map(line -> trimmed(needle, line)).toList();
    int[] costs = new int[rows * columns];
    byte[] directions = new byte[rows * columns];
    for (int row = 1; row <= queryRows; row++) {
      costs[row * columns] = row * DELETION_COST;
      directions[row * columns] = Direction.UP;
    }
    for (int row = 1; row <= queryRows; row++) {
      String queryLine = trimmedNeedle.get(row - 1);
      for (int column = 1; column <= bufferRows; column++) {
        String bufferLine = trimmed(file, fileLines.get(column - 1));
        int up = saturatedAdd(costs[(row - 1) * columns + column], DELETION_COST);
        int left = saturatedAdd(costs[row * columns + column - 1], INSERTION_COST);
        int diagonalBase = costs[(row - 1) * columns + column - 1];
        int diagonal = queryLine.equals(bufferLine) ? diagonalBase
            : fuzzyEqual(queryLine, bufferLine) ? saturatedAdd(diagonalBase, REPLACEMENT_COST)
            : saturatedAdd(diagonalBase, DELETION_COST + INSERTION_COST);
        int index = row * columns + column;
        costs[index] = up;
        directions[index] = Direction.UP;
        if (left < costs[index]) { costs[index] = left; directions[index] = Direction.LEFT; }
        if (diagonal < costs[index]) { costs[index] = diagonal; directions[index] = Direction.DIAGONAL; }
      }
    }
    int bestCost = Integer.MAX_VALUE;
    List<Integer> bestColumns = new ArrayList<>();
    for (int column = 1; column <= bufferRows; column++) {
      int cost = costs[queryRows * columns + column];
      if (cost < bestCost) { bestCost = cost; bestColumns.clear(); bestColumns.add(column); }
      else if (cost == bestCost) bestColumns.add(column);
    }
    List<DpMatch> matches = new ArrayList<>();
    for (int endColumn : bestColumns) {
      int row = queryRows;
      int column = endColumn;
      int matched = 0;
      while (row > 0 && column > 0) {
        byte direction = directions[row * columns + column];
        if (direction == Direction.DIAGONAL) {
          String queryLine = trimmedNeedle.get(row - 1);
          String bufferLine = trimmed(file, fileLines.get(column - 1));
          if (queryLine.equals(bufferLine) || fuzzyEqual(queryLine, bufferLine)) matched++;
          row--; column--;
        } else if (direction == Direction.UP) row--;
        else column--;
      }
      int rowStart = column;
      int rowEnd = endColumn - 1;
      int bufferSpan = endColumn - rowStart;
      double ratio = (double) matched / Math.max(bufferSpan, queryRows);
      if (ratio >= MATCH_RATIO) matches.add(new DpMatch(rowStart, rowEnd, bestCost));
    }
    return matches;
  }

  private static boolean fuzzyEqual(String left, String right) {
    left = normalizeSmartCharacters(left);
    right = normalizeSmartCharacters(right);
    int maximumLength = Math.max(left.length(), right.length());
    if (maximumLength == 0) return true;
    double lengthBound = 1.0 - (double) Math.abs(left.length() - right.length()) / maximumLength;
    if (lengthBound < FUZZY_EQUAL_THRESHOLD) return false;
    return 1.0 - (double) levenshtein(left, right) / maximumLength >= FUZZY_EQUAL_THRESHOLD;
  }

  private static int levenshtein(String first, String second) {
    if (first.length() < second.length()) { String swap = first; first = second; second = swap; }
    int[] previous = new int[second.length() + 1];
    int[] current = new int[second.length() + 1];
    for (int index = 0; index <= second.length(); index++) previous[index] = index;
    for (int row = 1; row <= first.length(); row++) {
      current[0] = row;
      for (int column = 1; column <= second.length(); column++) {
        int substitution = previous[column - 1]
            + (first.charAt(row - 1) == second.charAt(column - 1) ? 0 : 1);
        current[column] = Math.min(substitution,
            Math.min(current[column - 1] + 1, previous[column] + 1));
      }
      int[] swap = previous; previous = current; current = swap;
    }
    return previous[second.length()];
  }

  private static String normalizeSmartCharacters(String value) {
    return value.replace("‘", "'").replace("’", "'").replace("“", "\"")
        .replace("”", "\"").replace("—", "--").replace("–", "-");
  }

  private static List<Line> scanLines(String value) {
    List<Line> lines = new ArrayList<>();
    int start = 0;
    for (int index = 0; index < value.length(); index++) {
      if (value.charAt(index) == '\n') { lines.add(line(value, start, index + 1)); start = index + 1; }
    }
    if (start < value.length()) lines.add(line(value, start, value.length()));
    return lines;
  }

  private static Line line(String value, int start, int end) {
    int trimmedEnd = end;
    if (trimmedEnd > start && value.charAt(trimmedEnd - 1) == '\n') trimmedEnd--;
    while (trimmedEnd > start && isWhitespace(value.charAt(trimmedEnd - 1))) trimmedEnd--;
    int indentEnd = start;
    while (indentEnd < trimmedEnd && (value.charAt(indentEnd) == ' ' || value.charAt(indentEnd) == '\t')) indentEnd++;
    return new Line(start, end, indentEnd, trimmedEnd);
  }

  private static String trimmed(String value, Line line) {
    return value.substring(line.indentEnd, line.trimmedEnd);
  }

  private static IndentDelta detectIndentDelta(
      String file, String needle, List<Line> fileLines, int low, int high, List<Line> needleLines) {
    String needleBase = commonIndent(needle, needleLines, 0, needleLines.size());
    String fileBase = commonIndent(file, fileLines, low, high);
    return new IndentDelta(needleBase != null && fileBase != null,
        needleBase == null ? "" : needleBase, fileBase == null ? "" : fileBase);
  }

  private static String commonIndent(String value, List<Line> lines, int low, int high) {
    String common = null;
    for (int index = low; index < high; index++) {
      Line line = lines.get(index);
      if (line.indentEnd == line.trimmedEnd) continue;
      String indent = value.substring(line.start, line.indentEnd);
      if (common == null) common = indent;
      else common = common.substring(0, commonPrefixLength(common, indent));
    }
    return common;
  }

  private static String applyIndentDelta(String text, IndentDelta delta) {
    StringBuilder output = new StringBuilder(text.length() + text.length() / 8);
    int index = 0;
    while (index < text.length()) {
      int lineStart = index;
      while (index < text.length() && text.charAt(index) != '\n') index++;
      int lineEnd = index;
      if (index < text.length()) index++;
      String line = text.substring(lineStart, lineEnd);
      boolean blank = line.chars().allMatch(character -> isWhitespace((char) character));
      if (blank) output.append(line);
      else {
        int strip = !delta.needleBase.isEmpty() && line.startsWith(delta.needleBase)
            ? delta.needleBase.length() : 0;
        output.append(delta.fileBase).append(line, strip, line.length());
      }
      if (lineEnd < text.length()) output.append('\n');
    }
    return output.toString();
  }

  private static int countOccurrences(String file, String needle) {
    if (needle.isEmpty() || needle.length() > file.length()) return 0;
    int count = 0;
    int position = 0;
    while ((position = file.indexOf(needle, position)) >= 0) { count++; position += needle.length(); }
    return count;
  }

  private static int commonPrefixLength(String first, String second) {
    int length = Math.min(first.length(), second.length());
    int index = 0;
    while (index < length && first.charAt(index) == second.charAt(index)) index++;
    return index;
  }

  private static boolean isWhitespace(char value) { return value == ' ' || value == '\t' || value == '\r'; }
  private static int saturatedAdd(int value, int addition) { return value > Integer.MAX_VALUE - addition ? Integer.MAX_VALUE : value + addition; }
  private static FuzzyMatch failed(int count) { return new FuzzyMatch(false, 0, 0, count, "", 0); }

  private record Line(int start, int end, int indentEnd, int trimmedEnd) {}
  private record DpMatch(int rowStart, int rowEnd, int cost) {}
  private record IndentDelta(boolean have, String needleBase, String fileBase) {}
  private static final class Direction { static final byte UP = 0; static final byte LEFT = 1; static final byte DIAGONAL = 2; private Direction() {} }
}
