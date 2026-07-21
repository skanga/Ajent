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
                       long elapsedMillis, int tokensIn, int contextMax, int queued,
                       String banner, int width) {
    public Status(String title, String provider, Phase phase, String detail,
                  int tokensIn, int contextMax, int queued, String banner, int width) {
      this(title, provider, phase, detail, -1, tokensIn, contextMax, queued, banner, width);
    }

    public Status {
      title = Objects.requireNonNull(title, "title");
      provider = Objects.requireNonNull(provider, "provider");
      phase = Objects.requireNonNull(phase, "phase");
      detail = Objects.requireNonNull(detail, "detail");
      banner = Objects.requireNonNull(banner, "banner");
      if (elapsedMillis < -1 || tokensIn < 0 || contextMax < 0 || queued < 0 || width < 1) {
        throw new IllegalArgumentException("negative status value or invalid width");
      }
    }
  }

  public record Composer(String text, int cursor, Profile profile, Phase phase,
                         int queued, boolean expanded, int width) {
    public Composer {
      text = Objects.requireNonNull(text, "text");
      profile = Objects.requireNonNull(profile, "profile");
      phase = Objects.requireNonNull(phase, "phase");
      if (cursor < 0 || cursor > text.length() || queued < 0 || width < 8) {
        throw new IllegalArgumentException("invalid composer state or width");
      }
    }
  }

  public record Permission(String toolName, String description, boolean showAlwaysAllow,
                           int width) {
    public Permission {
      toolName = Objects.requireNonNull(toolName, "toolName");
      description = Objects.requireNonNull(description, "description");
      if (width < 8) throw new IllegalArgumentException("permission width must be at least eight");
    }
  }

  public record PickerRow(String leading, String trailing, boolean selected, boolean active) {
    public PickerRow {
      leading = Objects.requireNonNull(leading, "leading");
      trailing = Objects.requireNonNull(trailing, "trailing");
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
    String chips = compactModelBadge(config.modelId()) + "    ▌ "
        + titleCase(config.profile().name()) + " ▐";
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

  /** Maya's natural-height six-row composer box, including its divider and hint rail. */
  public static List<Row> composer(Composer config) {
    Objects.requireNonNull(config, "config");
    int inside = config.width() - 2;
    String border = "─".repeat(inside);
    var rows = new ArrayList<Row>();
    rows.add(row("╭" + border + "╮", Tone.ACCENT));

    String placeholder = config.text().isEmpty() ? composerPlaceholder(config) : config.text();
    List<String> bodyLines = config.text().isEmpty()
        ? List.of("❯ █" + placeholder)
        : composerBodyLines(config.text(), config.cursor(), Math.max(1, inside - 2));
    int logicalRows = config.text().isEmpty() ? 1 : config.text().split("\\n", -1).length;
    int bodyRows = bodyLines.size() + Math.max(0, 2 - logicalRows);
    for (int index = 0; index < bodyRows; index++) {
      String value = index < bodyLines.size() ? bodyLines.get(index) : "";
      rows.add(row("│ " + fit(value, inside - 2) + " │", Tone.NORMAL));
    }

    rows.add(row("│   " + "─".repeat(Math.max(0, inside - 4)) + " │", Tone.ACCENT));
    String left = "↵ send  ·  ⇧↵ / ⌥↵ newline  ·  ^E expand";
    String right = "▎ " + letterSpaced(config.profile().name());
    int content = inside;
    int fixed = 3 + columns(left) + columns(right) + 2;
    String hints = "   " + left + " ".repeat(Math.max(1, content - fixed)) + right + "  ";
    rows.add(row("│" + fit(hints, content) + "│", Tone.MUTED));
    int lineCount = config.text().split("\\n", -1).length;
    String caption = lineCount > 1 ? " " + lineCount + " lines " : "";
    rows.add(row(caption.isEmpty()
        ? "╰" + border + "╯"
        : "╰" + "─".repeat(Math.max(0, inside - columns(caption))) + caption + "╯",
        Tone.ACCENT));
    return List.copyOf(rows);
  }

  /** Stable three-row status panel used by AgenTTY after ShortcutRow was retired. */
  public static List<Row> statusPanel(Status config) {
    Objects.requireNonNull(config, "config");
    String rule = "─".repeat(config.width());
    List<Row> activity = status(config);
    String middle = activity.getFirst().text();
    if (activity.size() > 1) middle = activity.getLast().text();
    return List.of(row(rule, phaseTone(config.phase())),
        row(fitStatusActivity(config, middle), phaseTone(config.phase())),
        row(rule, phaseTone(config.phase())));
  }

  /** Maya's six-row bordered permission card. */
  public static List<Row> permission(Permission config) {
    Objects.requireNonNull(config, "config");
    String title = " ⚠ Permission Required ";
    String top = "╭" + title
        + "─".repeat(Math.max(0, config.width() - columns(title) - 2)) + "╮";
    String verb = switch (config.toolName().toLowerCase(Locale.ROOT)) {
      case "read" -> "wants to read:";
      case "edit", "write" -> "wants to edit:";
      default -> "wants to execute:";
    };
    String hints = "[y] allow  [n] deny"
        + (config.showAlwaysAllow() ? "  [a] always" : "") + "  ";
    return List.of(row(fit(top, config.width()), Tone.WARNING),
        permissionBody(config.toolName() + " " + verb, config.width()),
        permissionBody(config.description(), config.width()),
        permissionBody("", config.width()),
        permissionBody(hints, config.width()),
        row("╰" + "─".repeat(config.width() - 2) + "╯", Tone.WARNING));
  }

  /** Maya's bottom-inset provider picker, including its list and footer padding. */
  public static List<Row> providerPicker(List<PickerRow> pickerRows, int width) {
    return providerPicker(pickerRows, width, pickerRows.size());
  }

  public static List<Row> providerPicker(
      List<PickerRow> pickerRows, int width, int viewportRows) {
    List<PickerRow> values = List.copyOf(Objects.requireNonNull(pickerRows, "pickerRows"));
    if (width < 52) throw new IllegalArgumentException("provider picker width must be at least 52");
    if (viewportRows < 1) throw new IllegalArgumentException("picker viewport must be positive");
    int panelWidth = width - 2;
    String title = " Providers ";
    int rules = panelWidth - columns(title) - 2;
    int leftRule = rules / 2;
    var rows = new ArrayList<Row>();
    rows.add(row("  ╭" + "─".repeat(leftRule) + title
        + "─".repeat(rules - leftRule) + "╮", Tone.ACCENT));
    rows.add(pickerBody("", width, Tone.NORMAL));
    int visibleRows = Math.min(values.size(), viewportRows);
    int selected = 0;
    for (int index = 0; index < values.size(); index++) {
      if (values.get(index).selected()) selected = index;
    }
    int start = Math.max(0, Math.min(selected - visibleRows + 1, values.size() - visibleRows));
    for (PickerRow value : values.subList(start, start + visibleRows)) {
      rows.add(pickerBody(pickerDataRow(value, width - 9) + "┃", width,
          value.selected() ? Tone.ACCENT : Tone.NORMAL));
    }
    rows.add(pickerBody("", width, Tone.NORMAL));
    rows.add(pickerBody("✓ ready  ⚠ set the named key first", width, Tone.MUTED));
    rows.add(pickerBody("↑↓ move   Enter switch   Esc close", width, Tone.MUTED));
    rows.add(pickerBody("", width, Tone.NORMAL));
    rows.add(row("  ╰" + "─".repeat(panelWidth - 2) + "╯", Tone.ACCENT));
    return List.copyOf(rows);
  }

  /** Maya's searchable command palette; the command catalog remains caller-owned. */
  public static List<Row> commandPalette(
      String query, List<PickerRow> commandRows, int width, int viewportRows) {
    Objects.requireNonNull(query, "query");
    List<PickerRow> values = List.copyOf(Objects.requireNonNull(commandRows, "commandRows"));
    if (width < 50) throw new IllegalArgumentException("command palette width must be at least 50");
    if (viewportRows < 1) throw new IllegalArgumentException("picker viewport must be positive");
    int panelWidth = width - 2;
    String title = " Command Palette ";
    int rules = panelWidth - columns(title) - 2;
    int leftRule = rules / 2;
    var rows = new ArrayList<Row>();
    rows.add(row("  ╭" + "─".repeat(leftRule) + title
        + "─".repeat(rules - leftRule) + "╮", Tone.ACCENT));
    rows.add(pickerBody("", width, Tone.NORMAL));
    rows.add(pickerBody("› " + (query.isEmpty() ? "type to filter…" : query),
        width, Tone.MUTED));
    rows.add(pickerBody("─".repeat(width - 8), width, Tone.MUTED));
    int visibleRows = Math.min(values.size(), viewportRows);
    int selected = 0;
    for (int index = 0; index < values.size(); index++) {
      if (values.get(index).selected()) selected = index;
    }
    int start = Math.max(0, Math.min(selected - visibleRows + 1, values.size() - visibleRows));
    for (PickerRow value : values.subList(start, start + visibleRows)) {
      rows.add(pickerBody(pickerDataRow(value, width - 9) + "┃", width,
          value.selected() ? Tone.ACCENT : Tone.NORMAL));
    }
    rows.add(pickerBody("", width, Tone.NORMAL));
    rows.add(row("  ╰" + "─".repeat(panelWidth - 2) + "╯", Tone.ACCENT));
    return List.copyOf(rows);
  }

  /** Maya's saved-thread picker with active marker, position, and key hints. */
  public static List<Row> threadPicker(
      List<PickerRow> threadRows, String position, int width, int viewportRows) {
    List<PickerRow> values = List.copyOf(Objects.requireNonNull(threadRows, "threadRows"));
    Objects.requireNonNull(position, "position");
    if (width < 50) throw new IllegalArgumentException("thread picker width must be at least 50");
    if (viewportRows < 1) throw new IllegalArgumentException("picker viewport must be positive");
    int panelWidth = width - 2;
    String title = " Threads ";
    int rules = panelWidth - columns(title) - 2;
    int leftRule = rules / 2;
    var rows = new ArrayList<Row>();
    rows.add(row("  ╭" + "─".repeat(leftRule) + title
        + "─".repeat(rules - leftRule) + "╮", Tone.ACCENT));
    rows.add(pickerBody("", width, Tone.NORMAL));
    int visibleRows = Math.min(values.size(), viewportRows);
    int selected = 0;
    for (int index = 0; index < values.size(); index++) {
      if (values.get(index).selected()) selected = index;
    }
    int start = Math.max(0, Math.min(selected - visibleRows + 1, values.size() - visibleRows));
    for (PickerRow value : values.subList(start, start + visibleRows)) {
      rows.add(pickerBody(pickerDataRow(value, width - 9) + "┃", width,
          value.selected() ? Tone.ACCENT : Tone.NORMAL));
    }
    rows.add(pickerBody("", width, Tone.NORMAL));
    rows.add(pickerBody("  " + position, width, Tone.MUTED));
    rows.add(pickerBody("↑↓ move   PgUp/PgDn page   Enter open   N new   Esc close",
        width, Tone.MUTED));
    rows.add(pickerBody("", width, Tone.NORMAL));
    rows.add(row("  ╰" + "─".repeat(panelWidth - 2) + "╯", Tone.ACCENT));
    return List.copyOf(rows);
  }

  /** Maya's searchable model picker with optional reasoning-effort footer. */
  public static List<Row> modelPicker(String query, List<PickerRow> modelRows,
      String effortHint, int width, int viewportRows) {
    Objects.requireNonNull(query, "query");
    List<PickerRow> values = List.copyOf(Objects.requireNonNull(modelRows, "modelRows"));
    Objects.requireNonNull(effortHint, "effortHint");
    if (width < 40) throw new IllegalArgumentException("model picker width must be at least 40");
    if (viewportRows < 1) throw new IllegalArgumentException("picker viewport must be positive");
    int panelWidth = width - 2;
    String title = " Models ";
    int rules = panelWidth - columns(title) - 2;
    int leftRule = rules / 2;
    var rows = new ArrayList<Row>();
    rows.add(row("  ╭" + "─".repeat(leftRule) + title
        + "─".repeat(rules - leftRule) + "╮", Tone.ACCENT));
    rows.add(pickerBody("", width, Tone.NORMAL));
    rows.add(pickerBody("🔍 " + (query.isEmpty() ? "type to filter models…" : query),
        width, Tone.MUTED));
    rows.add(pickerBody("─".repeat(width - 8), width, Tone.MUTED));
    int visibleRows = Math.min(values.size(), viewportRows);
    int selected = 0;
    for (int index = 0; index < values.size(); index++) {
      if (values.get(index).selected()) selected = index;
    }
    int start = Math.max(0, Math.min(selected - visibleRows + 1, values.size() - visibleRows));
    for (PickerRow value : values.subList(start, start + visibleRows)) {
      rows.add(pickerBody(pickerDataRow(value, width - 9) + "┃", width,
          value.selected() ? Tone.ACCENT : Tone.NORMAL));
    }
    rows.add(pickerBody("", width, Tone.NORMAL));
    if (!effortHint.isBlank()) rows.add(pickerBody(effortHint, width, Tone.MUTED));
    rows.add(pickerBody("↑↓ move   type filter   Enter select   F favorite   Esc close",
        width, Tone.MUTED));
    rows.add(pickerBody("", width, Tone.NORMAL));
    rows.add(row("  ╰" + "─".repeat(panelWidth - 2) + "╯", Tone.ACCENT));
    return List.copyOf(rows);
  }

  /** Maya's @file picker. */
  public static List<Row> mentionPicker(String query, List<PickerRow> fileRows,
      String position, int width, int viewportRows) {
    return searchablePicker(" Mention File ", "@", "type to filter files…", query,
        fileRows, position, width, viewportRows, 50);
  }

  /** Maya's #symbol picker. */
  public static List<Row> symbolPicker(String query, List<PickerRow> symbolRows,
      String position, int width, int viewportRows) {
    return searchablePicker(" Symbol ", "#", "type to filter symbols…", query,
        symbolRows, position, width, viewportRows, 60);
  }

  /** Maya's Ctrl+G fenced-code picker. */
  public static List<Row> codeBlockPicker(
      List<PickerRow> blockRows, int width, int viewportRows) {
    List<PickerRow> values = List.copyOf(Objects.requireNonNull(blockRows, "blockRows"));
    if (width < 60) throw new IllegalArgumentException("code picker width must be at least 60");
    if (viewportRows < 1) throw new IllegalArgumentException("picker viewport must be positive");
    var rows = pickerStart(" Run Code Block ", width);
    appendPickerRows(rows, values, width, viewportRows);
    rows.add(pickerBody("", width, Tone.NORMAL));
    rows.add(pickerBody(
        "↑↓ move   Enter/1-9 run   e edit   y copy   Esc close", width, Tone.MUTED));
    rows.add(pickerBody("", width, Tone.NORMAL));
    rows.add(pickerBottom(width));
    return List.copyOf(rows);
  }

  /** Maya's post-execution result card. */
  public static List<Row> codeBlockResult(String command, String status,
      List<String> outputRows, boolean success, int width, int viewportRows) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(status, "status");
    List<String> output = List.copyOf(Objects.requireNonNull(outputRows, "outputRows"));
    if (width < 60) throw new IllegalArgumentException("result width must be at least 60");
    if (viewportRows < 1) throw new IllegalArgumentException("picker viewport must be positive");
    var rows = pickerStart(" Run Result ", width);
    rows.add(pickerBody("$ " + command, width, success ? Tone.SUCCESS : Tone.DANGER));
    rows.add(pickerBody("  " + status, width, success ? Tone.MUTED : Tone.DANGER));
    rows.add(pickerBody("─".repeat(width - 8), width, Tone.MUTED));
    for (String line : output.stream().limit(viewportRows).toList()) {
      rows.add(pickerScrollBody("  " + line, width,
          success ? Tone.MUTED : Tone.DANGER));
    }
    rows.add(pickerBody("", width, Tone.NORMAL));
    rows.add(pickerBody("a attach to composer   y copy   Esc discard", width, Tone.MUTED));
    rows.add(pickerBody("", width, Tone.NORMAL));
    rows.add(pickerBottom(width));
    return List.copyOf(rows);
  }

  /** Maya's two-axis pending-change review panel. */
  public static List<Row> diffReview(
      DiffReview.File file, int fileIndex, int fileCount, int hunkIndex, int width) {
    Objects.requireNonNull(file, "file");
    if (width < 40) throw new IllegalArgumentException("diff review width must be at least 40");
    if (fileIndex < 0 || fileIndex >= fileCount) {
      throw new IllegalArgumentException("invalid diff review file index");
    }
    if (hunkIndex < 0 || hunkIndex >= file.hunks().size()) {
      throw new IllegalArgumentException("invalid diff review hunk index");
    }
    var rows = pickerStart(" Review Changes ", width);
    String counts = "+" + file.added() + " -" + file.removed();
    String position = "file " + (fileIndex + 1) + "/" + fileCount;
    rows.add(pickerBody(spaced(file.path(), counts + "  " + position, width - 8),
        width, Tone.NORMAL));
    rows.add(pickerBody("─".repeat(width - 8), width, Tone.MUTED));
    for (int index = 0; index < file.hunks().size(); index++) {
      DiffReview.Hunk hunk = file.hunks().get(index);
      String status = switch (hunk.status()) {
        case PENDING -> "[ pending ]";
        case ACCEPTED -> "[✓ accepted]";
        case REJECTED -> "[✗ rejected]";
      };
      rows.add(pickerBody((index == hunkIndex ? "› " : "  ")
          + hunk.header().replace(" @@", "") + "  " + status, width, Tone.MUTED));
      appendDiffView(rows, file.path(), hunk.patch(), width);
      rows.add(pickerBody("", width, Tone.NORMAL));
      rows.add(pickerBody("", width, Tone.NORMAL));
    }
    rows.add(pickerBody("─".repeat(width - 8), width, Tone.MUTED));
    rows.add(pickerBody(
        "↑↓ hunk  ←→ file  Y accept  N reject  A all  X none  Esc close",
        width, Tone.MUTED));
    rows.add(pickerBody("", width, Tone.NORMAL));
    rows.add(pickerBottom(width));
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

  private static Row permissionBody(String content, int width) {
    return row("│ " + fit(content, width - 4) + " │", Tone.NORMAL);
  }

  private static Row pickerBody(String content, int width, Tone tone) {
    return row("  │  " + fit(content, width - 8) + "  │", tone);
  }

  private static Row pickerScrollBody(String content, int width, Tone tone) {
    return row("  │  " + fit(content, width - 9) + "┃  │", tone);
  }

  private static void appendDiffView(
      List<Row> rows, String path, String patch, int width) {
    int nestedWidth = width - 8;
    String title = " " + truncate(path, Math.max(1, nestedWidth - 4)) + " ";
    rows.add(pickerBody("╭─" + title
        + "─".repeat(Math.max(0, nestedWidth - columns(title) - 3)) + "╮",
        width, Tone.MUTED));
    int newLine = 0;
    for (String line : patch.lines().toList()) {
      if (line.isEmpty()) continue;
      String rendered;
      Tone tone;
      if (line.charAt(0) == '+') {
        rendered = String.format(java.util.Locale.ROOT, "%4d %s", newLine++, line);
        tone = Tone.SUCCESS;
      } else if (line.charAt(0) == '-') {
        rendered = "     " + line;
        tone = Tone.DANGER;
      } else {
        rendered = String.format(java.util.Locale.ROOT, "%4d %s", newLine++, line);
        tone = Tone.MUTED;
      }
      rows.add(pickerBody("│ " + fit(rendered, nestedWidth - 4) + " │", width, tone));
    }
    rows.add(pickerBody("╰" + "─".repeat(nestedWidth - 2) + "╯", width, Tone.MUTED));
  }

  private static String spaced(String leading, String trailing, int width) {
    String left = truncate(leading, Math.max(1, width - columns(trailing) - 1));
    return left + " ".repeat(Math.max(1, width - columns(left) - columns(trailing))) + trailing;
  }

  private static List<Row> searchablePicker(String title, String prefix, String placeholder,
      String query, List<PickerRow> pickerRows, String position, int width, int viewportRows,
      int minimumWidth) {
    Objects.requireNonNull(query, "query");
    List<PickerRow> values = List.copyOf(Objects.requireNonNull(pickerRows, "pickerRows"));
    Objects.requireNonNull(position, "position");
    if (width < minimumWidth) {
      throw new IllegalArgumentException("picker width must be at least " + minimumWidth);
    }
    if (viewportRows < 1) throw new IllegalArgumentException("picker viewport must be positive");
    int panelWidth = width - 2;
    int rules = panelWidth - columns(title) - 2;
    int leftRule = rules / 2;
    var rows = new ArrayList<Row>();
    rows.add(row("  ╭" + "─".repeat(leftRule) + title
        + "─".repeat(rules - leftRule) + "╮", Tone.ACCENT));
    rows.add(pickerBody("", width, Tone.NORMAL));
    rows.add(pickerBody(prefix + " " + (query.isEmpty() ? placeholder : query),
        width, Tone.MUTED));
    rows.add(pickerBody("─".repeat(width - 8), width, Tone.MUTED));
    int visibleRows = Math.min(values.size(), viewportRows);
    int selected = 0;
    for (int index = 0; index < values.size(); index++) {
      if (values.get(index).selected()) selected = index;
    }
    int start = Math.max(0, Math.min(selected - visibleRows + 1, values.size() - visibleRows));
    for (PickerRow value : values.subList(start, start + visibleRows)) {
      rows.add(pickerBody(pickerDataRow(value, width - 9) + "┃", width,
          value.selected() ? Tone.ACCENT : Tone.NORMAL));
    }
    if (!position.isBlank()) rows.add(pickerBody("  " + position, width, Tone.MUTED));
    rows.add(pickerBody("", width, Tone.NORMAL));
    rows.add(row("  ╰" + "─".repeat(panelWidth - 2) + "╯", Tone.ACCENT));
    return List.copyOf(rows);
  }

  private static ArrayList<Row> pickerStart(String title, int width) {
    int panelWidth = width - 2;
    int rules = panelWidth - columns(title) - 2;
    int leftRule = rules / 2;
    var rows = new ArrayList<Row>();
    rows.add(row("  ╭" + "─".repeat(leftRule) + title
        + "─".repeat(rules - leftRule) + "╮", Tone.ACCENT));
    rows.add(pickerBody("", width, Tone.NORMAL));
    return rows;
  }

  private static void appendPickerRows(
      List<Row> output, List<PickerRow> values, int width, int viewportRows) {
    int visibleRows = Math.min(values.size(), viewportRows);
    int selected = 0;
    for (int index = 0; index < values.size(); index++) {
      if (values.get(index).selected()) selected = index;
    }
    int start = Math.max(0, Math.min(selected - visibleRows + 1, values.size() - visibleRows));
    for (PickerRow value : values.subList(start, start + visibleRows)) {
      output.add(pickerBody(pickerDataRow(value, width - 9) + "┃", width,
          value.selected() ? Tone.ACCENT : Tone.NORMAL));
    }
  }

  private static Row pickerBottom(int width) {
    return row("  ╰" + "─".repeat(width - 4) + "╯", Tone.ACCENT);
  }

  private static String pickerDataRow(PickerRow row, int width) {
    String edge = row.selected() || row.active() ? "▎" : " ";
    int availableLeading = Math.max(1,
        width - columns(edge) - 1 - columns(row.trailing()) - 2);
    String leading = truncate(row.leading(), availableLeading);
    int spaces = Math.max(1, width - columns(edge) - 1 - columns(leading)
        - columns(row.trailing()) - 1);
    return fit(edge + " " + leading + " ".repeat(spaces) + row.trailing() + " ", width);
  }

  private static String phase(Status config) {
    String glyph;
    String verb;
    boolean active;
    switch (config.phase()) {
      case AWAITING_PERMISSION -> {
        glyph = "⚠";
        verb = config.detail().isBlank() ? "approve?" : "approve " + config.detail();
        active = true;
      }
      case COMPACTING -> { glyph = "◐"; verb = "compacting"; active = true; }
      case RETRYING -> { glyph = "◐"; verb = "retrying"; active = true; }
      case STALLED -> { glyph = "◐"; verb = "stalled"; active = true; }
      case EXECUTING_TOOL -> {
        glyph = "◐";
        verb = config.detail().isBlank() ? "running" : config.detail();
        active = true;
      }
      case STREAMING -> { glyph = "◐"; verb = "Streaming"; active = true; }
      case AUTHENTICATING -> { glyph = "◐"; verb = "auth…"; active = false; }
      case LOADING -> { glyph = "◐"; verb = "loading…"; active = false; }
      case IDLE -> {
        glyph = config.queued() > 0 ? "▸" : "●";
        verb = config.queued() > 0 ? "+" + config.queued() + " queued" : "Ready";
        active = false;
      }
      default -> throw new IllegalStateException("unexpected phase: " + config.phase());
    }
    String value = glyph + " " + fit(verb, 10);
    return active && config.elapsedMillis() >= 0
        ? value + " " + formatElapsed5(config.elapsedMillis()) : value;
  }

  private static String fitStatusActivity(Status config, String banner) {
    if (!config.banner().isBlank() && !config.banner().equals("ready")) {
      return fit(banner, config.width());
    }
    String phase = phase(config);
    int gaugeCells = 10;
    String right = statusRight(config, gaugeCells);
    String left = "▌ " + phase;
    int fixed = 3 + columns(phase) + columns(right);
    int leftover = config.width() - fixed - 7;
    String title = config.title().isBlank() || leftover < 14 ? ""
        : "▎ " + truncate(config.title(), Math.min(leftover, 28)) + "   ·   ";
    int used = 1 + columns(title) + columns(left) + columns(right);

    // Maya measures the fixed groups before admitting the breadcrumb. A short title can then
    // consume one more cell than its measured leftover (the edge glyph and its space), and the
    // flex row takes that cell from the context bar while preserving its percentage suffix.
    if (used > config.width() && config.contextMax() > 0) {
      gaugeCells = Math.max(0, gaugeCells - (used - config.width()));
      right = statusRight(config, gaugeCells);
      used = 1 + columns(title) + columns(left) + columns(right);
    }
    int spaces = Math.max(0, config.width() - used);
    return fit(" " + title + left + " ".repeat(spaces) + right, config.width());
  }

  private static String statusRight(Status config, int gaugeCells) {
    String right = "● " + config.provider();
    if (config.contextMax() > 0) right += " · "
        + compactNativeContextGauge(config.tokensIn(), config.contextMax(), gaugeCells);
    return right + " ";
  }

  private static String formatElapsed5(long millis) {
    double seconds = millis / 1_000.0;
    if (seconds < 100) return String.format(Locale.ROOT, "%4.1fs", seconds);
    if (seconds < 600) return String.format(Locale.ROOT, "%4ds", (long) seconds);
    if (seconds < 3_600) {
      long minutes = (long) seconds / 60;
      return String.format(Locale.ROOT, "%dm%02ds", minutes, (long) seconds - minutes * 60);
    }
    return " >1hr";
  }

  private static String compactNativeContextGauge(int used, int maximum) {
    return compactNativeContextGauge(used, maximum, 10);
  }

  private static String compactNativeContextGauge(int used, int maximum, int cells) {
    if (maximum <= 0) return "";
    int percent = used <= 0 ? 0 : Math.min(100, used * 100 / maximum);
    int eighths = percent * cells * 8 / 100;
    String[] partials = {"", "▏", "▎", "▍", "▌", "▋", "▊", "▉"};
    var bar = new StringBuilder();
    for (int cell = 0; cell < cells; cell++) {
      int filled = Math.max(0, eighths - cell * 8);
      bar.append(filled >= 8 ? "█" : filled > 0 ? partials[filled] : "░");
    }
    return "CTX " + bar + (used <= 0 ? " ———%"
        : String.format(Locale.ROOT, " %3d%%", percent));
  }

  private static String composerPlaceholder(Composer config) {
    if (config.queued() > 0) return config.queued() == 1
        ? "press ↑ to edit queued"
        : "↑ drain queue • ⌥↑/⌥↓ cycle items";
    return switch (config.phase()) {
      case AWAITING_PERMISSION -> "awaiting permission — respond above…";
      case EXECUTING_TOOL -> "running tool — type to queue…";
      case STREAMING -> "streaming — type to queue…";
      default -> "type a message…";
    };
  }

  private static String withCursor(String text, int cursor) {
    return text.substring(0, cursor) + "█" + text.substring(cursor);
  }

  private static List<String> composerBodyLines(String text, int cursor, int width) {
    var rows = new ArrayList<String>();
    String[] logicalLines = withCursor(text, cursor).split("\\n", -1);
    int contentWidth = Math.max(1, width - 2);
    for (int logicalIndex = 0; logicalIndex < logicalLines.length; logicalIndex++) {
      List<String> wrapped = wrapCellLine(logicalLines[logicalIndex], contentWidth);
      for (int visualIndex = 0; visualIndex < wrapped.size(); visualIndex++) {
        String prefix = visualIndex == 0
            ? (logicalIndex == 0 ? "❯ " : "┊ ") : "  ";
        rows.add(prefix + wrapped.get(visualIndex));
      }
    }
    return List.copyOf(rows);
  }

  private static List<String> wrapCellLine(String value, int width) {
    if (value.isEmpty()) return List.of("");
    var rows = new ArrayList<String>();
    String remaining = value;
    while (!remaining.isEmpty()) {
      int end = 0;
      int used = 0;
      while (end < remaining.length()) {
        int codePoint = remaining.codePointAt(end);
        int cellWidth = UnicodeWidth.of(codePoint);
        if (used + cellWidth > width) break;
        used += cellWidth;
        end += Character.charCount(codePoint);
      }
      if (end == 0) end = Character.charCount(remaining.codePointAt(0));
      rows.add(remaining.substring(0, end));
      remaining = remaining.substring(end);
    }
    return List.copyOf(rows);
  }

  private static String compactModelBadge(String modelId) {
    String value = modelId == null ? "" : modelId;
    String lower = value.toLowerCase(Locale.ROOT);
    String label = lower.contains("opus") ? "Opus"
        : lower.contains("sonnet") ? "Sonnet"
        : lower.contains("haiku") ? "Haiku"
        : lower.contains("gpt") ? "GPT"
        : lower.contains("gemini") ? "Gemini" : value;
    return "● " + label;
  }

  private static String titleCase(String value) {
    if (value.isEmpty()) return value;
    return value.substring(0, 1).toUpperCase(Locale.ROOT)
        + value.substring(1).toLowerCase(Locale.ROOT);
  }

  private static String letterSpaced(String value) {
    return String.join(" ", value.toUpperCase(Locale.ROOT).chars()
        .mapToObj(codePoint -> Character.toString(codePoint)).toList());
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
    // Maya's two flex spacers assign an odd remainder to the leading spacer.
    int padding = (Math.max(0, width - columns(value)) + 1) / 2;
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
