package com.github.skanga.ajent.terminal.render;

/** ANSI 16, xterm-256, RGB, and terminal-default color value. */
public record TerminalColor(Kind kind, int r, int g, int b) {
  public enum Kind { NAMED, INDEXED, RGB, DEFAULT }

  private static final int[] CUBE = {0x00, 0x5f, 0x87, 0xaf, 0xd7, 0xff};
  private static final int[][] ANSI16 = {
      {0, 0, 0}, {128, 0, 0}, {0, 128, 0}, {128, 128, 0},
      {0, 0, 128}, {128, 0, 128}, {0, 128, 128}, {192, 192, 192},
      {128, 128, 128}, {255, 0, 0}, {0, 255, 0}, {255, 255, 0},
      {0, 0, 255}, {255, 0, 255}, {0, 255, 255}, {255, 255, 255}
  };

  public TerminalColor {
    java.util.Objects.requireNonNull(kind, "kind");
    requireByte(r);
    requireByte(g);
    requireByte(b);
    if (kind == Kind.NAMED && r > 15) {
      throw new IllegalArgumentException("named color index must be 0..15");
    }
  }

  public static TerminalColor named(int index) { return new TerminalColor(Kind.NAMED, index, 0, 0); }
  public static TerminalColor indexed(int index) { return new TerminalColor(Kind.INDEXED, index, 0, 0); }
  public static TerminalColor rgb(int red, int green, int blue) {
    return new TerminalColor(Kind.RGB, red, green, blue);
  }
  public static TerminalColor terminalDefault() { return new TerminalColor(Kind.DEFAULT, 0, 0, 0); }

  public static TerminalColor black() { return named(0); }
  public static TerminalColor red() { return named(1); }
  public static TerminalColor green() { return named(2); }
  public static TerminalColor yellow() { return named(3); }
  public static TerminalColor blue() { return named(4); }
  public static TerminalColor magenta() { return named(5); }
  public static TerminalColor cyan() { return named(6); }
  public static TerminalColor white() { return named(7); }
  public static TerminalColor brightBlack() { return named(8); }

  public int index() { return r; }

  public String foregroundSgr() { return sgr(true); }

  public String backgroundSgr() { return sgr(false); }

  public TerminalColor degrade(int level) {
    if (level >= 3 || kind == Kind.DEFAULT || kind == Kind.NAMED) return this;
    if (kind == Kind.INDEXED) {
      if (level >= 2) return this;
      int[] rgb = xterm256ToRgb(r);
      return named(rgbToAnsi16(rgb[0], rgb[1], rgb[2]));
    }
    if (level >= 2) return indexed(rgbToXterm256(r, g, b));
    return named(rgbToAnsi16(r, g, b));
  }

  private String sgr(boolean foreground) {
    return switch (kind) {
      case NAMED -> Integer.toString(r < 8
          ? (foreground ? 30 : 40) + r
          : (foreground ? 90 : 100) + r - 8);
      case INDEXED -> (foreground ? "38;5;" : "48;5;") + r;
      case RGB -> (foreground ? "38;2;" : "48;2;") + r + ';' + g + ';' + b;
      case DEFAULT -> foreground ? "39" : "49";
    };
  }

  private static int rgbToXterm256(int red, int green, int blue) {
    int ri = cubeIndex(red), gi = cubeIndex(green), bi = cubeIndex(blue);
    int cr = CUBE[ri], cg = CUBE[gi], cb = CUBE[bi];
    int cube = 16 + 36 * ri + 6 * gi + bi;
    int gray = (red * 299 + green * 587 + blue * 114) / 1000;
    int grayIndex = gray < 8 ? 0 : gray > 238 ? 23 : (gray - 3) / 10;
    int grayValue = 8 + 10 * grayIndex;
    return distance(cr, cg, cb, red, green, blue)
        <= distance(grayValue, grayValue, grayValue, red, green, blue)
        ? cube : 232 + grayIndex;
  }

  private static int rgbToAnsi16(int red, int green, int blue) {
    int best = 0, bestDistance = Integer.MAX_VALUE;
    for (int index = 0; index < ANSI16.length; index++) {
      int distance = distance(ANSI16[index][0], ANSI16[index][1], ANSI16[index][2],
          red, green, blue);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = index;
      }
    }
    return best;
  }

  private static int[] xterm256ToRgb(int index) {
    if (index < 16) return ANSI16[index];
    if (index < 232) {
      int color = index - 16;
      return new int[] {CUBE[(color / 36) % 6], CUBE[(color / 6) % 6], CUBE[color % 6]};
    }
    int value = 8 + 10 * (index - 232);
    return new int[] {value, value, value};
  }

  private static int cubeIndex(int value) {
    if (value < 48) return 0;
    if (value < 115) return 1;
    return (value - 35) / 40;
  }

  private static int distance(int r1, int g1, int b1, int r2, int g2, int b2) {
    int dr = r1 - r2, dg = g1 - g2, db = b1 - b2;
    return dr * dr + dg * dg + db * db;
  }

  private static void requireByte(int value) {
    if (value < 0 || value > 255) throw new IllegalArgumentException("color channel must be 0..255");
  }
}
