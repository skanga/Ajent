package com.github.skanga.ajent.terminal.render;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Height- and width-stable port of Maya's streaming text-reveal decorator. */
public final class TextRevealEffect {
  private static final List<String> SCRAMBLE = List.of(
      "#", "$", "%", "&", "*", "+", "=", "?", "@", "!", "~", "^", "<", ">",
      "|", "/", "\\", "░", "▒", "▓", "■", "□", "◆", "◇", "●", "○", "✦",
      "α", "β", "γ", "δ", "λ", "π", "σ", "φ", "0", "1", "7", "8", "X", "Z");

  private TextRevealEffect() {}

  public record Parameters(
      long totalMillis,
      long edgeAgeMillis,
      int revealedCodePoints,
      int totalCodePoints,
      int clippedUnrevealedCodePoints,
      boolean clipActive,
      int trailLength,
      int scrambleLength,
      long scrambleMillis,
      long characterStepMillis,
      int ghostExtra,
      boolean scramble,
      boolean gradient,
      boolean ghost,
      boolean sweep,
      boolean caret,
      boolean protectStructure,
      double revealFraction,
      boolean lineBounded,
      boolean ghostBlank) {

    public Parameters {
      if (revealedCodePoints < 0 || totalCodePoints < 0 || clippedUnrevealedCodePoints < 0
          || trailLength < 0 || scrambleLength < 0 || scrambleMillis < 0
          || characterStepMillis < 0 || ghostExtra < 0) {
        throw new IllegalArgumentException("negative reveal parameter");
      }
    }

    public static Parameters defaults(
        long totalMillis, long edgeAgeMillis, int revealedCodePoints, int totalCodePoints) {
      return new Parameters(totalMillis, edgeAgeMillis, revealedCodePoints, totalCodePoints,
          0, false, 36, 6, 220, 26, 96, true, true, true, true, true,
          false, -1, false, true);
    }

    public Parameters withScramble(boolean enabled) {
      return new Parameters(totalMillis, edgeAgeMillis, revealedCodePoints, totalCodePoints,
          clippedUnrevealedCodePoints, clipActive, trailLength, scrambleLength,
          scrambleMillis, characterStepMillis, ghostExtra, enabled, gradient, ghost, sweep,
          caret, protectStructure, revealFraction, lineBounded, ghostBlank);
    }

    public Parameters withClip(int unrevealedCodePoints) {
      if (unrevealedCodePoints < 0) throw new IllegalArgumentException("negative clip");
      return new Parameters(totalMillis, edgeAgeMillis, revealedCodePoints, totalCodePoints,
          unrevealedCodePoints, true, trailLength, scrambleLength, scrambleMillis,
          characterStepMillis, ghostExtra, scramble, gradient, ghost, sweep, caret,
          protectStructure, revealFraction, lineBounded, ghostBlank);
    }

    public Parameters withRevealFraction(double fraction) {
      return new Parameters(totalMillis, edgeAgeMillis, revealedCodePoints, totalCodePoints,
          clippedUnrevealedCodePoints, clipActive, trailLength, scrambleLength,
          scrambleMillis, characterStepMillis, ghostExtra, scramble, gradient, ghost, sweep,
          caret, protectStructure, fraction, lineBounded, ghostBlank);
    }

    public Parameters withStructureProtection(boolean protect, boolean lastLineOnly) {
      return new Parameters(totalMillis, edgeAgeMillis, revealedCodePoints, totalCodePoints,
          clippedUnrevealedCodePoints, clipActive, trailLength, scrambleLength,
          scrambleMillis, characterStepMillis, ghostExtra, scramble, gradient, ghost, sweep,
          caret, protect, revealFraction, lastLineOnly, ghostBlank);
    }

    public Parameters withGhostBlank(boolean blank) {
      return new Parameters(totalMillis, edgeAgeMillis, revealedCodePoints, totalCodePoints,
          clippedUnrevealedCodePoints, clipActive, trailLength, scrambleLength,
          scrambleMillis, characterStepMillis, ghostExtra, scramble, gradient, ghost, sweep,
          caret, protectStructure, revealFraction, lineBounded, blank);
    }
  }

  public record Glyph(String text, TerminalStyle style, int sourceCodePoint) {
    public Glyph {
      text = Objects.requireNonNull(text, "text");
      style = Objects.requireNonNull(style, "style");
      if (!Character.isValidCodePoint(sourceCodePoint)) {
        throw new IllegalArgumentException("invalid source code point");
      }
    }
  }

  public record Decoration(List<Glyph> glyphs, boolean bytesChanged) {
    public Decoration { glyphs = List.copyOf(glyphs); }
    public String text() {
      var result = new StringBuilder();
      glyphs.forEach(glyph -> result.append(glyph.text()));
      return result.toString();
    }
  }

  public static Decoration decorate(
      String content, TerminalStyle base, Parameters parameters) {
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(parameters, "parameters");
    if (content.isEmpty()) return new Decoration(List.of(), false);

    int[] codePoints = content.codePoints().toArray();
    int lineStart = 0;
    if (parameters.lineBounded()) {
      for (int index = codePoints.length - 1; index >= 0; index--) {
        if (codePoints[index] == '\n') {
          lineStart = index + 1;
          break;
        }
      }
    }
    int derivedTotal = codePoints.length - lineStart;
    int total = parameters.totalCodePoints() == 0
        ? derivedTotal : parameters.totalCodePoints();
    int revealed;
    if (parameters.revealFraction() >= 0) {
      double fraction = Math.min(1, parameters.revealFraction());
      revealed = (int) (fraction * total + 0.5);
    } else {
      revealed = parameters.revealedCodePoints() == 0
          ? total : parameters.revealedCodePoints();
    }
    int unrevealed = parameters.clipActive()
        ? parameters.clippedUnrevealedCodePoints() : Math.max(0, total - revealed);
    int target = Math.max(parameters.trailLength(),
        parameters.ghost() ? unrevealed + parameters.ghostExtra() : 0);
    int trailStart = Math.max(lineStart, codePoints.length - target);
    int trailCount = codePoints.length - trailStart;
    if (trailCount == 0) return glyphs(content, base, false);
    int scrambleCount = parameters.scramble()
        ? Math.min(trailCount, parameters.scrambleLength()) : 0;

    int[] byteOffsets = new int[codePoints.length + 1];
    for (int index = 0; index < codePoints.length; index++) {
      byteOffsets[index + 1] = byteOffsets[index]
          + new String(Character.toChars(codePoints[index]))
              .getBytes(StandardCharsets.UTF_8).length;
    }
    var result = new ArrayList<Glyph>(codePoints.length);
    for (int index = 0; index < trailStart; index++) {
      result.add(new Glyph(new String(Character.toChars(codePoints[index])), base,
          codePoints[index]));
    }
    for (int index = trailStart; index < codePoints.length; index++) {
      int fromTail = codePoints.length - 1 - index;
      long distance = Math.max(0L, (long) fromTail - unrevealed);
      long age = parameters.edgeAgeMillis() + distance * parameters.characterStepMillis();
      int codePoint = codePoints[index];
      String real = new String(Character.toChars(codePoint));
      boolean inScramble = scrambleCount > 0 && fromTail >= unrevealed
          && fromTail < unrevealed + scrambleCount;
      boolean structural = parameters.protectStructure()
          && (real.getBytes(StandardCharsets.UTF_8).length != 1
              || codePoint < 0x21 || codePoint > 0x7e);
      boolean scrambling = inScramble && !structural && age < parameters.scrambleMillis();
      boolean ghost = parameters.ghost() && fromTail < unrevealed && !structural;
      boolean sweepHead = ghost && parameters.sweep() && fromTail == unrevealed - 1;

      String emitted;
      if (scrambling) {
        emitted = scramblePick(byteOffsets[index], age, parameters.totalMillis());
      } else if (ghost && parameters.ghostBlank() && !sweepHead) {
        emitted = " ".repeat(Math.max(1, UnicodeWidth.of(codePoint)));
      } else {
        emitted = real;
      }

      TerminalStyle style;
      if (scrambling) {
        boolean flick = ((parameters.totalMillis() / 60 + (index - trailStart)) & 1) == 0;
        style = TerminalStyle.EMPTY.withForeground(flick
            ? TerminalColor.rgb(255, 80, 180) : TerminalColor.rgb(255, 160, 60)).withBold();
      } else if (ghost) {
        style = TerminalStyle.EMPTY.withForeground(TerminalColor.terminalDefault()).withDim();
        if (sweepHead) {
          double pulse = pulse01(parameters.totalMillis(), 280);
          style = TerminalStyle.EMPTY
              .withForeground(lerp(TerminalColor.rgb(255, 220, 140),
                  TerminalColor.rgb(180, 255, 220), pulse))
              .withBackground(lerp(TerminalColor.rgb(60, 50, 20),
                  TerminalColor.rgb(90, 80, 40), pulse)).withBold();
        }
      } else if (fromTail < unrevealed) {
        style = base;
      } else {
        style = parameters.gradient() ? trailStyle(age).orElse(base) : base;
      }
      result.add(new Glyph(emitted, style, codePoint));
    }
    boolean active = scrambleCount > 0 && parameters.edgeAgeMillis()
        < parameters.scrambleMillis() + scrambleCount * parameters.characterStepMillis();
    return new Decoration(result, active);
  }

  public static Decoration decorateEndCaret(
      String content, TerminalStyle base, long totalMillis, long periodMillis) {
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(base, "base");
    double pulse = pulse01(totalMillis, periodMillis);
    TerminalColor foreground = lerp(
        TerminalColor.rgb(220, 80, 200), TerminalColor.rgb(100, 230, 255), pulse);
    TerminalStyle caret = TerminalStyle.EMPTY.withForeground(foreground)
        .withBackground(TerminalColor.rgb(
            foreground.r() / 4, foreground.g() / 4, foreground.b() / 4)).withBold();
    return decorateEndCaret(glyphs(content, base, false), caret);
  }

  public static Decoration decorateEndCaret(
      Decoration decoration, long totalMillis, long periodMillis) {
    Objects.requireNonNull(decoration, "decoration");
    double pulse = pulse01(totalMillis, periodMillis);
    TerminalColor foreground = lerp(
        TerminalColor.rgb(220, 80, 200), TerminalColor.rgb(100, 230, 255), pulse);
    TerminalStyle caret = TerminalStyle.EMPTY.withForeground(foreground)
        .withBackground(TerminalColor.rgb(
            foreground.r() / 4, foreground.g() / 4, foreground.b() / 4)).withBold();
    return decorateEndCaret(decoration, caret);
  }

  private static Decoration decorateEndCaret(Decoration decoration, TerminalStyle caret) {
    if (decoration.glyphs().isEmpty()) {
      return new Decoration(List.of(new Glyph("▊", caret, '▊')), true);
    }
    var glyphs = new ArrayList<>(decoration.glyphs());
    Glyph last = glyphs.getLast();
    glyphs.set(glyphs.size() - 1, new Glyph(last.text(), caret, last.sourceCodePoint()));
    return new Decoration(glyphs, decoration.bytesChanged());
  }

  public static String clipToCursor(String content, int revealedCodePoints) {
    Objects.requireNonNull(content, "content");
    if (revealedCodePoints < 0) throw new IllegalArgumentException("negative reveal cursor");
    int count = content.codePointCount(0, content.length());
    if (revealedCodePoints >= count) return content;
    return content.substring(0, content.offsetByCodePoints(0, revealedCodePoints));
  }

  static boolean isGhost(TerminalStyle style) {
    return style.foreground() != null
        && style.foreground().kind() == TerminalColor.Kind.DEFAULT && style.dim();
  }

  static List<String> scrambleGlyphs() { return SCRAMBLE; }

  static String scramblePick(long codePointByteOffset, long ageMillis, long totalMillis) {
    long timeBucket = totalMillis / 45;
    long hash = 0x9e3779b97f4a7c15L;
    hash ^= codePointByteOffset + 0x9e3779b9L + (hash << 6) + (hash >>> 2);
    hash ^= timeBucket + 0x9e3779b9L + (hash << 6) + (hash >>> 2);
    hash ^= ageMillis + 0x9e3779b9L + (hash << 6) + (hash >>> 2);
    return SCRAMBLE.get((int) Long.remainderUnsigned(hash, SCRAMBLE.size()));
  }

  static Optional<TerminalStyle> trailStyle(long ageMillis) {
    if (ageMillis >= 700) return Optional.empty();
    TerminalColor color;
    boolean bold = false;
    boolean dim = false;
    if (ageMillis < 120) {
      double t = smoothstep(ageMillis / 120.0);
      color = lerp(TerminalColor.rgb(255, 90, 200), TerminalColor.rgb(120, 230, 255), t);
      bold = true;
    } else if (ageMillis < 320) {
      double t = smoothstep((ageMillis - 120) / 200.0);
      color = lerp(TerminalColor.rgb(120, 230, 255), TerminalColor.rgb(140, 180, 220), t);
      bold = t < 0.5;
    } else {
      double t = smoothstep((ageMillis - 320) / 380.0);
      color = lerp(TerminalColor.rgb(140, 180, 220), TerminalColor.rgb(200, 200, 200), t);
      dim = true;
    }
    TerminalStyle style = TerminalStyle.EMPTY.withForeground(color);
    if (bold) style = style.withBold();
    if (dim) style = style.withDim();
    return Optional.of(style);
  }

  static double pulse01(long totalMillis, long periodMillis) {
    if (periodMillis <= 0) return 0;
    double phase = Math.floorMod(totalMillis, periodMillis) / (double) periodMillis;
    double triangle = phase < 0.5 ? phase * 2 : 2 - phase * 2;
    return smoothstep(triangle);
  }

  private static Decoration glyphs(String content, TerminalStyle style, boolean changed) {
    return new Decoration(content.codePoints()
        .mapToObj(codePoint -> new Glyph(
            new String(Character.toChars(codePoint)), style, codePoint)).toList(), changed);
  }

  private static double smoothstep(double value) {
    double bounded = Math.max(0, Math.min(1, value));
    return bounded * bounded * (3 - 2 * bounded);
  }

  private static TerminalColor lerp(TerminalColor from, TerminalColor to, double fraction) {
    return TerminalColor.rgb(channel(from.r(), to.r(), fraction),
        channel(from.g(), to.g(), fraction), channel(from.b(), to.b(), fraction));
  }

  private static int channel(int from, int to, double fraction) {
    return (int) Math.max(0, Math.min(255, from + (to - from) * fraction + 0.5));
  }
}
