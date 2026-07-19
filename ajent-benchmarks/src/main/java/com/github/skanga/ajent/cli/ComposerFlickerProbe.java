package com.github.skanga.ajent.cli;

import com.github.skanga.ajent.domain.Message;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Production-inline diagnostic port of composer_flicker_probe.cpp. */
public final class ComposerFlickerProbe {
  private static final int WIDTH = 100;
  private static final int HEIGHT = 40;

  private ComposerFlickerProbe() {}

  record WireStats(long mean, long p50, long p95, long max, long total) {}

  record Metrics(int frames, int composerMoves, int composerUpBounces,
                 int composerRewrites, int hiddenReappearRows, double rowsRewrittenMean,
                 int contentHeightShrinks, int worstContentHeightShrink,
                 int outOfOrderAppearances, WireStats wire) {}

  public static void main(String[] args) {
    int paragraphs = args.length > 0 ? Integer.parseInt(args[0]) : 120;
    int bytesPerFrame = args.length > 1 ? Integer.parseInt(args[1]) : 96;
    if (paragraphs <= 0 || bytesPerFrame <= 0) {
      System.err.println("composer_flicker_probe: arguments must be positive");
      System.exit(1);
    }
    run(paragraphs, bytesPerFrame, System.err);
  }

  static Metrics run(int paragraphs, int bytesPerFrame, PrintStream report) {
    if (paragraphs <= 0 || bytesPerFrame <= 0) {
      throw new IllegalArgumentException("arguments must be positive");
    }
    String document = document(paragraphs);
    Message assistant = BenchmarkUiSupport.assistant("", List.of());
    var fixture = BenchmarkUiSupport.fixture(new ArrayList<>(List.of(
        BenchmarkUiSupport.user("stream a long list"), assistant)), WIDTH, HEIGHT, true, true);
    var emulator = new AnsiScreen(WIDTH, HEIGHT);
    fixture.render();
    emulator.feed(fixture.terminal().drain());

    var previous = emulator.screen();
    int previousComposer = -1;
    String previousComposerText = "";
    int composerMoves = 0;
    int composerBounces = 0;
    int composerRewrites = 0;
    int hiddenReappear = 0;
    long rewrittenRows = 0;
    var trajectory = new ArrayList<Integer>();
    var heights = new ArrayList<Integer>();
    var wireBytes = new ArrayList<Long>();
    int[] blankSince = new int[HEIGHT];
    Arrays.fill(blankSince, -1);
    int[] firstSeen = new int[paragraphs + 1];
    Arrays.fill(firstSeen, -1);

    int fed = 0;
    int frames = 0;
    while (fed < document.length()) {
      int end = Math.min(document.length(), fed + bytesPerFrame);
      assistant = BenchmarkUiSupport.withText(assistant, document.substring(0, end));
      BenchmarkUiSupport.replaceLast(fixture, assistant);
      fixture.render();
      String wire = fixture.terminal().drain();
      wireBytes.add((long) wire.length());
      emulator.feed(wire);
      frames++;
      heights.add(fixture.ui().renderedText().lines().toList().size());

      List<String> current = emulator.screen();
      int changed = 0;
      for (int row = 0; row < HEIGHT; row++) {
        if (!current.get(row).equals(previous.get(row))) changed++;
      }
      rewrittenRows += changed;

      int composer = findComposer(current);
      if (composer >= 0) {
        trajectory.add(composer);
        String composerText = current.get(composer);
        if (previousComposer >= 0) {
          if (composer != previousComposer) {
            composerMoves++;
            if (composer < previousComposer) composerBounces++;
          } else if (!composerText.equals(previousComposerText)) {
            composerRewrites++;
          }
        }
        previousComposer = composer;
        previousComposerText = composerText;
      }

      int contentEnd = composer >= 0 ? composer : HEIGHT;
      for (int row = 0; row < contentEnd; row++) {
        boolean wasVisible = !previous.get(row).isBlank();
        boolean nowVisible = !current.get(row).isBlank();
        if (wasVisible && !nowVisible) {
          blankSince[row] = frames;
        } else if (!wasVisible && nowVisible && blankSince[row] >= 0) {
          if (frames - blankSince[row] >= 3) hiddenReappear++;
          blankSince[row] = -1;
        }
      }
      int high = Math.min(paragraphs, fed / 80 + 4);
      for (int item = Math.max(1, high - 12); item <= high; item++) {
        if (firstSeen[item] < 0 && emulator.contains("item " + item + " ")) {
          firstSeen[item] = frames;
        }
      }
      previous = current;
      fed = end;
    }

    int heightShrinks = 0;
    int worstShrink = 0;
    for (int index = 1; index < heights.size(); index++) {
      int delta = heights.get(index) - heights.get(index - 1);
      if (delta < 0) {
        heightShrinks++;
        worstShrink = Math.max(worstShrink, -delta);
      }
    }
    int outOfOrder = 0;
    for (int item = 1; item + 1 < firstSeen.length; item++) {
      if (firstSeen[item] >= 0 && firstSeen[item + 1] >= 0
          && firstSeen[item] > firstSeen[item + 1]) outOfOrder++;
    }
    WireStats wire = wireStats(wireBytes);
    var metrics = new Metrics(frames, composerMoves, composerBounces, composerRewrites,
        hiddenReappear, frames == 0 ? 0 : (double) rewrittenRows / frames,
        heightShrinks, worstShrink, outOfOrder, wire);
    print(metrics, trajectory, report);
    return metrics;
  }

  private static String document(int paragraphs) {
    var result = new StringBuilder("Here is the breakdown of everything that matters:\n\n");
    for (int item = 1; item <= paragraphs; item++) {
      result.append("- item ").append(item)
          .append(" with some **bold** text and `code` tokens that make the row wrap once at a "
              + "hundred columns of width\n");
    }
    return result.toString();
  }

  private static int findComposer(List<String> screen) {
    for (int row = screen.size() - 1; row >= 0; row--) {
      if (screen.get(row).stripLeading().startsWith("> ")) return row;
    }
    return -1;
  }

  private static WireStats wireStats(List<Long> samples) {
    if (samples.isEmpty()) return new WireStats(0, 0, 0, 0, 0);
    List<Long> sorted = samples.stream().sorted(Comparator.naturalOrder()).toList();
    long total = samples.stream().mapToLong(Long::longValue).sum();
    return new WireStats(total / samples.size(), percentile(sorted, 0.5),
        percentile(sorted, 0.95), sorted.getLast(), total);
  }

  private static long percentile(List<Long> values, double percentile) {
    return values.get(Math.min(values.size() - 1,
        (int) (percentile * (values.size() - 1))));
  }

  private static void print(Metrics metrics, List<Integer> trajectory, PrintStream report) {
    report.printf("frames=%d composer: moves=%d (UP-bounces=%d) in-place-rewrites=%d%n"
            + "hidden->reappear rows=%d rows rewritten/frame avg=%.1f%n",
        metrics.frames(), metrics.composerMoves(), metrics.composerUpBounces(),
        metrics.composerRewrites(), metrics.hiddenReappearRows(), metrics.rowsRewrittenMean());
    report.print("composer row trajectory: ");
    for (int index = 0; index < trajectory.size();) {
      int end = index + 1;
      while (end < trajectory.size() && trajectory.get(end).equals(trajectory.get(index))) end++;
      report.print(trajectory.get(index) + "×" + (end - index) + " ");
      index = end;
    }
    report.println();
    report.printf("content-height shrink events=%d worst=%d%n",
        metrics.contentHeightShrinks(), metrics.worstContentHeightShrink());
    report.printf("wire bytes/frame: mean=%d p50=%d p95=%d max=%d total=%d%n",
        metrics.wire().mean(), metrics.wire().p50(), metrics.wire().p95(),
        metrics.wire().max(), metrics.wire().total());
    report.println("out-of-order item appearances=" + metrics.outOfOrderAppearances());
  }

  /** Small wcwidth-aware ANSI emulator for the subset emitted by Ajent's inline renderer. */
  static final class AnsiScreen {
    private final int columns;
    private final int rows;
    private final List<char[]> screen;
    private final List<String> scrollback = new ArrayList<>();
    private int x;
    private int y;
    private boolean autoWrap = true;

    AnsiScreen(int columns, int rows) {
      this.columns = columns;
      this.rows = rows;
      screen = new ArrayList<>(rows);
      for (int row = 0; row < rows; row++) screen.add(blank());
    }

    void feed(String input) {
      for (int index = 0; index < input.length();) {
        int codePoint = input.codePointAt(index);
        index += Character.charCount(codePoint);
        if (codePoint == '\r') { x = 0; continue; }
        if (codePoint == '\n') { newline(); continue; }
        if (codePoint == 0x1b) {
          index = escape(input, index);
          continue;
        }
        if (codePoint < 0x20) continue;
        put(codePoint, wide(codePoint) ? 2 : 1);
      }
    }

    List<String> screen() {
      return screen.stream().map(String::new).toList();
    }

    boolean contains(String text) {
      return screen().stream().anyMatch(row -> row.contains(text))
          || scrollback.stream().anyMatch(row -> row.contains(text));
    }

    private int escape(String input, int index) {
      if (index >= input.length()) return index;
      if (input.charAt(index) == ']') {
        index++;
        while (index < input.length() && input.charAt(index) != '\u0007') {
          if (input.charAt(index) == '\u001b' && index + 1 < input.length()
              && input.charAt(index + 1) == '\\') return index + 2;
          index++;
        }
        return Math.min(input.length(), index + 1);
      }
      if (input.charAt(index) != '[') return index + 1;
      index++;
      boolean privateMode = index < input.length() && input.charAt(index) == '?';
      if (privateMode) index++;
      var parameters = new ArrayList<Integer>();
      int value = 0;
      boolean present = false;
      while (index < input.length()) {
        char current = input.charAt(index);
        if (Character.isDigit(current)) {
          value = value * 10 + current - '0';
          present = true;
          index++;
        } else if (current == ';') {
          parameters.add(present ? value : 0);
          value = 0;
          present = false;
          index++;
        } else break;
      }
      if (present || !parameters.isEmpty()) parameters.add(present ? value : 0);
      if (index >= input.length()) return index;
      char command = input.charAt(index++);
      int first = parameters.isEmpty() ? 0 : parameters.getFirst();
      switch (command) {
        case 'A' -> y = Math.max(0, y - defaultOne(first));
        case 'B' -> { for (int count = defaultOne(first); count > 0; count--) newline(); }
        case 'C' -> x = Math.min(columns, x + defaultOne(first));
        case 'D' -> x = Math.max(0, x - defaultOne(first));
        case 'G' -> x = Math.clamp(defaultOne(first) - 1, 0, columns);
        case 'H', 'f' -> {
          int row = parameters.isEmpty() ? 1 : defaultOne(parameters.getFirst());
          int column = parameters.size() < 2 ? 1 : defaultOne(parameters.get(1));
          y = Math.clamp(row - 1, 0, rows - 1);
          x = Math.clamp(column - 1, 0, columns);
        }
        case 'K' -> eraseLine(first);
        case 'J' -> eraseDisplay(first);
        case 'h' -> { if (privateMode && first == 7) autoWrap = true; }
        case 'l' -> { if (privateMode && first == 7) autoWrap = false; }
        default -> { }
      }
      return index;
    }

    private void put(int codePoint, int width) {
      if (x + width > columns) {
        if (autoWrap) { x = 0; newline(); }
        else x = Math.max(0, columns - width);
      }
      char visible = codePoint < 128 ? (char) codePoint : '#';
      screen.get(y)[x] = visible;
      for (int offset = 1; offset < width && x + offset < columns; offset++) {
        screen.get(y)[x + offset] = codePoint < 128 ? ' ' : '#';
      }
      x += width;
    }

    private void newline() {
      if (y < rows - 1) { y++; return; }
      scrollback.add(new String(screen.removeFirst()));
      screen.add(blank());
    }

    private void eraseLine(int mode) {
      char[] line = screen.get(y);
      if (mode == 2) Arrays.fill(line, ' ');
      else if (mode == 1) Arrays.fill(line, 0, Math.min(columns, x + 1), ' ');
      else Arrays.fill(line, Math.min(columns, x), columns, ' ');
    }

    private void eraseDisplay(int mode) {
      if (mode == 2 || mode == 3) {
        if (mode == 3) scrollback.clear();
        screen.forEach(row -> Arrays.fill(row, ' '));
        return;
      }
      eraseLine(0);
      for (int row = y + 1; row < rows; row++) Arrays.fill(screen.get(row), ' ');
    }

    private char[] blank() {
      char[] row = new char[columns];
      Arrays.fill(row, ' ');
      return row;
    }

    private static int defaultOne(int value) { return value == 0 ? 1 : value; }

    private static boolean wide(int codePoint) {
      return codePoint >= 0x1100 && (codePoint <= 0x115f || codePoint == 0x2329
          || codePoint == 0x232a || codePoint >= 0x2e80 && codePoint <= 0xa4cf
          || codePoint >= 0xac00 && codePoint <= 0xd7a3
          || codePoint >= 0xf900 && codePoint <= 0xfaff
          || codePoint >= 0x1f300 && codePoint <= 0x1faff);
    }
  }
}
