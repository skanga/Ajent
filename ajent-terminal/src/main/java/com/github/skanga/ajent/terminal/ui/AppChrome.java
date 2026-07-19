package com.github.skanga.ajent.terminal.ui;

import com.github.skanga.ajent.domain.Profile;
import com.github.skanga.ajent.terminal.ModelLabels;
import com.github.skanga.ajent.terminal.render.UnicodeWidth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Responsive top-level chrome derived from AgenTTY's Maya welcome/status/change widgets. */
public final class AppChrome {
  public enum Tone { NORMAL, MUTED, BRAND, ACCENT, SUCCESS, WARNING, DANGER }
  public enum Phase {
    IDLE, STREAMING, EXECUTING_TOOL, AWAITING_PERMISSION, COMPACTING, RETRYING,
    STALLED, AUTHENTICATING, LOADING
  }

  public record Row(String text, Tone tone) {
    public Row {
      text = Objects.requireNonNull(text, "text");
      tone = Objects.requireNonNull(tone, "tone");
    }
  }

  public record Welcome(String modelId, Profile profile, boolean firstRun, int width, int maxRows) {
    public Welcome {
      modelId = Objects.requireNonNull(modelId, "modelId");
      profile = Objects.requireNonNull(profile, "profile");
      if (width < 1 || maxRows < 1) throw new IllegalArgumentException("invalid welcome bounds");
    }
  }

  public record Status(String title, String provider, Phase phase, String detail,
                       int tokensIn, int contextMax, int queued, String banner, int width) {
    public Status {
      title = Objects.requireNonNull(title, "title");
      provider = Objects.requireNonNull(provider, "provider");
      phase = Objects.requireNonNull(phase, "phase");
      detail = Objects.requireNonNull(detail, "detail");
      banner = Objects.requireNonNull(banner, "banner");
      if (tokensIn < 0 || contextMax < 0 || queued < 0 || width < 1) {
        throw new IllegalArgumentException("negative status value or invalid width");
      }
    }
  }

  public record Change(String path, boolean created, int added, int removed) {
    public Change {
      path = Objects.requireNonNull(path, "path");
      if (added < 0 || removed < 0) throw new IllegalArgumentException("negative line count");
    }
  }

  private record Hint(String key, String label) {}

  private static final List<Hint> HINTS = List.of(
      new Hint("^K", "palette"), new Hint("^J", "threads"),
      new Hint("^←→", "cycle"), new Hint("^T", "todo"),
      new Hint("S-Tab", "profile"), new Hint("^/", "models"),
      new Hint("^P", "provider"), new Hint("^N", "new"), new Hint("^C", "quit"));
  private static final String TAGLINE = "a calm middleware between you and the model";
  private static final int FONT_WIDTH = 6;
  private static final int FONT_HEIGHT = 7;
  private static final String WORDMARK = ">AGENTTY";

  private AppChrome() {}

  public static List<Row> welcome(Welcome config) {
    Objects.requireNonNull(config, "config");
    List<String> hints = hintLines(config.width() - 2);
    int essential = 2 + hints.size();
    boolean pixels = config.maxRows() >= essential + 5 && config.width() >= pixelWidth();
    int sigilRows = pixels ? 5 : 1;
    int spare = Math.clamp(config.maxRows() - essential - sigilRows, 0, 6);
    boolean b0 = spare > 0, b1 = spare > 1, b2 = spare > 2;
    boolean b3 = spare > 3, b4 = spare > 4, b5 = spare > 5;
    var rows = new ArrayList<Row>();
    if (b3) rows.add(row("", Tone.NORMAL));
    if (pixels) {
      for (String line : pixelWordmark()) rows.add(row(center(line, config.width()), Tone.BRAND));
    } else {
      rows.add(row(center("» A G E N T T Y", config.width()), Tone.BRAND));
    }
    if (b0) rows.add(row("", Tone.NORMAL));
    rows.add(row(center(TAGLINE, config.width()), Tone.MUTED));
    if (b1) rows.add(row("", Tone.NORMAL));
    if (b4) rows.add(row("", Tone.NORMAL));
    String chips = ModelLabels.pretty(config.modelId()) + "    ▌ "
        + config.profile().name() + " ▐";
    rows.add(row(center(chips, config.width()), Tone.ACCENT));
    if (b2) rows.add(row("", Tone.NORMAL));
    if (b5) rows.add(row("", Tone.NORMAL));
    if (config.firstRun() && spare >= 6) {
      rows.add(row(center("NEW HERE? TRY ONE OF THESE", config.width()), Tone.MUTED));
      rows.add(row(center("• Explain what this project does and how it's structured",
          config.width()), Tone.NORMAL));
      rows.add(row(center("• Find and fix the bug in <file> — it <symptom>",
          config.width()), Tone.NORMAL));
      rows.add(row(center("• Add a <feature> and run the tests", config.width()), Tone.NORMAL));
      rows.add(row("", Tone.NORMAL));
      rows.add(row("", Tone.NORMAL));
    }
    for (String line : hints) rows.add(row(center(line, config.width()), Tone.MUTED));
    return List.copyOf(rows);
  }

  public static List<Row> status(Status config) {
    Objects.requireNonNull(config, "config");
    String left = phase(config);
    String right = config.provider();
    if (config.contextMax() > 0) right += "  " + contextGauge(config.tokensIn(), config.contextMax());
    String activity = joinMeasured(left, config.title(), right, config.width());
    var rows = new ArrayList<Row>();
    rows.add(row(activity, phaseTone(config.phase())));
    if (!config.banner().isBlank() && !config.banner().equals("ready")) {
      Tone tone = bannerTone(config.banner());
      String glyph = tone == Tone.DANGER ? "✗" : tone == Tone.WARNING ? "⚠" : "▶";
      rows.add(row(fit(glyph + "  " + config.banner(), config.width()), tone));
    }
    return List.copyOf(rows);
  }

  public static List<Row> changes(List<Change> changes, int width) {
    List<Change> values = List.copyOf(Objects.requireNonNull(changes, "changes"));
    if (values.isEmpty()) return List.of();
    if (width < 4) throw new IllegalArgumentException("width must be at least four");
    int inside = width - 2;
    int content = Math.max(0, width - 4);
    String border = "─".repeat(inside);
    var rows = new ArrayList<Row>();
    rows.add(row("╭" + border + "╮", Tone.WARNING));
    String label = "Changes (" + values.size() + " files)";
    String hints = changeHints(label, content);
    String header = label;
    if (!hints.isEmpty()) {
      header += " ".repeat(content - columns(label) - columns(hints)) + hints;
    }
    rows.add(row("│ " + fit(header, content) + " │", Tone.WARNING));
    for (Change change : values) {
      String fact = (change.created() ? "A " : "M ") + change.path();
      if (change.added() > 0) fact += "  +" + change.added();
      if (change.removed() > 0) fact += " -" + change.removed();
      rows.add(row("│ " + fit(fact, content) + " │", Tone.NORMAL));
    }
    rows.add(row("╰" + border + "╯", Tone.WARNING));
    return List.copyOf(rows);
  }

  private static String changeHints(String label, int width) {
    String[] ladder = {
        "Ctrl+R review  A accept  X reject",
        "A accept  X reject",
        "A accept  ",
        ""
    };
    for (String candidate : ladder) {
      if (columns(label) + columns(candidate) <= width) return candidate;
    }
    return "";
  }

  private static String phase(Status config) {
    return switch (config.phase()) {
      case AWAITING_PERMISSION -> "⚠ " + (config.detail().isBlank()
          ? "approve?" : "approve " + config.detail());
      case COMPACTING -> "◐ compacting";
      case RETRYING -> "◐ retrying";
      case STALLED -> "◐ stalled";
      case EXECUTING_TOOL -> "◐ " + (config.detail().isBlank() ? "running" : config.detail());
      case STREAMING -> "◐ Streaming";
      case AUTHENTICATING -> "◐ auth…";
      case LOADING -> "◐ loading…";
      case IDLE -> config.queued() > 0 ? "▸ +" + config.queued() + " queued" : "● Ready";
    };
  }

  private static String contextGauge(int used, int maximum) {
    double fraction = maximum == 0 ? 0 : Math.min(1.0, (double) used / maximum);
    int cells = (int) Math.round(fraction * 10);
    int percent = (int) Math.round(fraction * 100);
    return "ctx " + "█".repeat(cells) + "░".repeat(10 - cells) + " " + percent + "%";
  }

  private static String joinMeasured(String left, String title, String right, int width) {
    String separator = "  ·  ";
    String withoutTitle = left + separator + right;
    if (!title.isBlank() && columns(withoutTitle) + columns(title) + columns(separator) <= width) {
      return fit(left + separator + truncate(title,
          width - columns(withoutTitle) - columns(separator)) + separator + right, width).stripTrailing();
    }
    if (columns(withoutTitle) <= width) return withoutTitle;
    if (columns(left) + columns(separator) + columns(right) <= width) return withoutTitle;
    return truncate(left, Math.max(1, width - columns(right) - columns(separator)))
        + separator + truncate(right, Math.max(1, width - columns(left) - columns(separator)));
  }

  private static Tone phaseTone(Phase phase) {
    return switch (phase) {
      case AWAITING_PERMISSION, RETRYING -> Tone.WARNING;
      case STALLED -> Tone.DANGER;
      case EXECUTING_TOOL -> Tone.SUCCESS;
      case STREAMING, COMPACTING, AUTHENTICATING -> Tone.ACCENT;
      case IDLE, LOADING -> Tone.MUTED;
    };
  }

  private static Tone bannerTone(String banner) {
    String value = banner.toLowerCase(Locale.ROOT);
    if (value.startsWith("error:")) return Tone.DANGER;
    if (value.startsWith("retrying") || value.startsWith("transient")
        || value.startsWith("rate limit") || value.startsWith("awaiting")) return Tone.WARNING;
    return Tone.ACCENT;
  }

  private static List<String> hintLines(int available) {
    boolean keysOnly = available > 0 && available < 40;
    var chips = new ArrayList<String>();
    chips.add("type to begin");
    for (Hint hint : HINTS) chips.add(keysOnly ? hint.key() : hint.key() + " " + hint.label());
    int maximum = available > 0 ? available : Integer.MAX_VALUE;
    var rows = new ArrayList<String>();
    var line = new StringBuilder();
    for (String chip : chips) {
      String addition = line.isEmpty() ? chip : "  ·  " + chip;
      if (!line.isEmpty() && columns(line + addition) > maximum) {
        rows.add(line.toString());
        line.setLength(0);
        addition = chip;
      }
      line.append(addition);
    }
    rows.add(line.toString());
    return List.copyOf(rows);
  }

  private static List<String> pixelWordmark() {
    int width = pixelWidth();
    boolean[][] pixels = new boolean[10][width];
    for (int glyphIndex = 0; glyphIndex < WORDMARK.length(); glyphIndex++) {
      String[] glyph = glyph(WORDMARK.charAt(glyphIndex));
      int x = glyphIndex * (FONT_WIDTH + 1);
      for (int row = 0; row < FONT_HEIGHT; row++) {
        for (int column = 0; column < FONT_WIDTH; column++) {
          if (glyph[row].charAt(column) == '#') pixels[row + 1][x + column] = true;
        }
      }
    }
    var rows = new ArrayList<String>(5);
    for (int row = 0; row < 10; row += 2) {
      var line = new StringBuilder(width);
      for (int column = 0; column < width; column++) {
        boolean top = pixels[row][column], bottom = pixels[row + 1][column];
        line.append(top && bottom ? '█' : top ? '▀' : bottom ? '▄' : ' ');
      }
      rows.add(line.toString().stripTrailing());
    }
    return List.copyOf(rows);
  }

  private static String[] glyph(char value) {
    return switch (value) {
      case '>' -> new String[]{"      ", "#  #  ", "## ## ", " ## ##", "## ## ", "#  #  ", "      "};
      case 'A' -> new String[]{"  ##  ", " #  # ", "#    #", "######", "#    #", "#    #", "#    #"};
      case 'G' -> new String[]{" #### ", "#    #", "#     ", "#  ###", "#    #", "#    #", " #### "};
      case 'E' -> new String[]{"######", "#     ", "#     ", "##### ", "#     ", "#     ", "######"};
      case 'N' -> new String[]{"#    #", "##   #", "# #  #", "#  # #", "#   ##", "#    #", "#    #"};
      case 'T' -> new String[]{"######", "  ##  ", "  ##  ", "  ##  ", "  ##  ", "  ##  ", "  ##  "};
      case 'Y' -> new String[]{"#    #", "#    #", " #  # ", "  ##  ", "  ##  ", "  ##  ", "  ##  "};
      default -> new String[]{"      ", "      ", "      ", "      ", "      ", "      ", "      "};
    };
  }

  private static int pixelWidth() { return WORDMARK.length() * FONT_WIDTH + WORDMARK.length() - 1; }
  private static Row row(String text, Tone tone) { return new Row(text, tone); }

  private static String center(String value, int width) {
    int padding = Math.max(0, width - columns(value)) / 2;
    return " ".repeat(padding) + value;
  }

  private static String fit(String value, int width) {
    String clipped = truncate(value, width);
    return clipped + " ".repeat(Math.max(0, width - columns(clipped)));
  }

  private static String truncate(String value, int width) {
    if (width <= 0) return "";
    if (columns(value) <= width) return value;
    if (width == 1) return "…";
    var output = new StringBuilder();
    int used = 0;
    for (int offset = 0; offset < value.length();) {
      int codePoint = value.codePointAt(offset);
      int cellWidth = UnicodeWidth.of(codePoint);
      if (used + cellWidth > width - 1) break;
      output.appendCodePoint(codePoint);
      used += cellWidth;
      offset += Character.charCount(codePoint);
    }
    return output.append('…').toString();
  }

  private static int columns(CharSequence value) {
    return UnicodeWidth.stringWidth(value.toString(), UnicodeWidth.Mode.MODERN);
  }
}
