package com.github.skanga.ajent.terminal.ui;

import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.terminal.render.UnicodeWidth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.nio.charset.StandardCharsets;

/** Pure text projection of Ajent's stable bordered tool-action timeline. */
public final class AgentTimeline {
  public enum Tone {
    NORMAL, MUTED, WHITE, INSPECT, EXECUTE, MUTATE, VCS, PLAN, AGENT,
    SUCCESS, DANGER, WARNING
  }

  public record Config(
      List<ToolUse> calls, int width, int terminalRows, long nowNanos) {
    public Config {
      calls = List.copyOf(calls);
      if (width < 1 || terminalRows < 1 || nowNanos < 0) {
        throw new IllegalArgumentException("invalid agent timeline bounds");
      }
    }
  }

  public record Span(
      String text, Tone tone, boolean bold, boolean dim, boolean italic) {
    public Span {
      text = Objects.requireNonNull(text, "text");
      tone = Objects.requireNonNull(tone, "tone");
    }
  }

  public record Row(String text, Tone tone, List<Span> spans) {
    public Row(String text, Tone tone) {
      this(text, tone, List.of(new Span(text, tone, false, false, false)));
    }

    public Row {
      text = Objects.requireNonNull(text, "text");
      tone = Objects.requireNonNull(tone, "tone");
      spans = List.copyOf(spans);
    }
  }

  private static final String TITLE = " A C T I O N S ";
  private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

  private AgentTimeline() {}

  public static List<Row> render(Config config) {
    Objects.requireNonNull(config, "config");
    if (config.calls().isEmpty()) return List.of();
    int width = config.width();
    if (width < 4) return narrow(config);
    var rows = new ArrayList<Row>();
    rows.add(new Row(top(width), Tone.MUTED));

    Map<String, Integer> categories = new LinkedHashMap<>();
    for (ToolUse call : config.calls()) categories.merge(category(call), 1, Integer::sum);
    rows.add(box(stats(categories), width, Tone.WHITE));
    rows.add(blank(width));

    Map<String, Set<Integer>> grepHits = ToolBodyPreview.collectGrepHits(config.calls());
    for (int index = 0; index < config.calls().size(); index++) {
      ToolUse call = config.calls().get(index);
      rows.add(box(event(call, index, config.calls().size()), width, categoryTone(call)));
      ToolBodyPreview.Preview preview = ToolBodyPreview.describe(
          call, config.terminalRows(), grepHits);
      for (ToolBodyPreview.Row body : ToolBodyPreview.render(preview)) {
        rows.add(box(List.of(
            span("   ", Tone.NORMAL),
            span("│", connectorTone(call), false, call.status().isTerminal(), false),
            span("  ", Tone.NORMAL),
            span(body.text(), bodyTone(body.tone()))), width, bodyTone(body.tone())));
      }
      if (index + 1 < config.calls().size()) {
        ToolUse next = config.calls().get(index + 1);
        rows.add(box(List.of(span("   ", Tone.NORMAL),
            span("│", connectorTone(next), false, true, false)), width, Tone.MUTED));
      }
    }

    rows.add(blank(width));
    rows.add(box(footer(config.calls(), config.nowNanos()), width, Tone.WHITE));
    rows.add(new Row("╰" + "─".repeat(width - 2) + "╯", Tone.MUTED));
    return List.copyOf(rows);
  }

  private static List<Row> narrow(Config config) {
    ToolUse call = config.calls().getLast();
    String status = statusGlyph(call.status()) + " " + displayName(call.name().value());
    return List.of(new Row(clip(status, config.width()), statusTone(call.status())));
  }

  private static String top(int width) {
    int fill = Math.max(0, width - 2 - columns(TITLE));
    return "╭" + clip(TITLE, width - 2) + "─".repeat(fill) + "╮";
  }

  private static Row blank(int width) {
    return new Row("│" + " ".repeat(width - 2) + "│", Tone.MUTED);
  }

  private static Row box(List<Span> content, int width, Tone tone) {
    int available = width - 4;
    List<Span> visible = clip(content, available);
    int used = visible.stream().mapToInt(span -> columns(span.text())).sum();
    int padding = Math.max(0, available - used);
    var spans = new ArrayList<Span>();
    spans.add(span("│ ", Tone.MUTED));
    spans.addAll(visible);
    spans.add(span(" ".repeat(padding) + " │", Tone.MUTED));
    String text = spans.stream().map(Span::text).collect(java.util.stream.Collectors.joining());
    return new Row(text, tone, spans);
  }

  private static List<Span> stats(Map<String, Integer> categories) {
    var output = new ArrayList<Span>();
    categories.forEach((category, count) -> {
      if (!output.isEmpty()) output.add(span("  ·  ", Tone.MUTED));
      output.add(span(smallCaps(category), categoryTone(category), true, false, false));
      output.add(span(" " + count, Tone.WHITE));
    });
    return List.copyOf(output);
  }

  private static List<Span> event(ToolUse call, int index, int total) {
    String tree = total == 1 ? "──" : index == 0 ? "╭─" : index + 1 == total ? "╰─" : "├─";
    boolean active = !call.status().isTerminal();
    Tone category = categoryTone(call);
    Tone nameTone = call.status() instanceof ToolStatus.Failed ? Tone.DANGER
        : call.status() instanceof ToolStatus.Rejected ? Tone.WARNING : category;
    return List.of(
        span(tree, category, false, !active, false),
        span(" ", Tone.NORMAL),
        span(statusGlyph(call.status()), statusTone(call.status()), true, false, false),
        span("  ", Tone.NORMAL),
        span(displayName(call.name().value()), nameTone, active
            || call.status() instanceof ToolStatus.Failed
            || call.status() instanceof ToolStatus.Rejected, !active
                && !(call.status() instanceof ToolStatus.Failed)
                && !(call.status() instanceof ToolStatus.Rejected), false),
        span("  ", Tone.NORMAL),
        span(detail(call), category, false, false, true));
  }

  private static List<Span> footer(List<ToolUse> calls, long nowNanos) {
    int terminal = 0, failed = 0, rejected = 0;
    long totalNanos = 0;
    for (ToolUse call : calls) {
      ToolStatus status = call.status();
      if (status.isTerminal()) {
        terminal++;
        totalNanos += elapsedNanos(status, nowNanos);
      }
      if (status instanceof ToolStatus.Failed) failed++;
      if (status instanceof ToolStatus.Rejected) rejected++;
    }
    boolean allDone = terminal == calls.size();
    String glyph;
    String label;
    Tone tone;
    if (!allDone) {
      int frame = Math.floorMod(nowNanos / 80_000_000L, SPINNER.length);
      glyph = SPINNER[frame];
      label = "running";
      tone = Tone.MUTED;
    } else if (failed > 0) {
      glyph = "✗";
      label = failed + " failed";
      tone = Tone.DANGER;
    } else if (rejected > 0) {
      glyph = "⊘";
      label = rejected + " rejected";
      tone = Tone.WARNING;
    } else {
      glyph = "✓";
      label = "done";
      tone = Tone.SUCCESS;
    }
    String summary = terminal + "/" + calls.size() + " "
        + (calls.size() == 1 ? "action" : "actions") + "   "
        + duration(totalNanos);
    return List.of(
        span("   ", Tone.NORMAL),
        span(glyph + " ", tone, true, false, false),
        span(smallCaps(label), tone, true, false, false),
        span("   ", Tone.NORMAL),
        span(summary, Tone.WHITE));
  }

  private static long elapsedNanos(ToolStatus status, long nowNanos) {
    if (status.startedNanos() <= 0) return 0;
    long end = status.finishedNanos() > 0 ? status.finishedNanos() : nowNanos;
    return Math.max(0, end - status.startedNanos());
  }

  private static String duration(long nanos) {
    double seconds = nanos / 1_000_000_000.0;
    if (seconds < 1) return String.format(Locale.ROOT, "%.0fms", seconds * 1_000);
    if (seconds < 60) return String.format(Locale.ROOT, "%.1fs", seconds);
    int minutes = (int) seconds / 60;
    return String.format(Locale.ROOT, "%dm%.0fs", minutes, seconds - minutes * 60);
  }

  private static String statusGlyph(ToolStatus status) {
    return switch (status) {
      case ToolStatus.Done ignored -> "✓";
      case ToolStatus.Failed ignored -> "✗";
      case ToolStatus.Rejected ignored -> "⊘";
      case ToolStatus.Pending ignored -> "●";
      case ToolStatus.Approved ignored -> "●";
      case ToolStatus.Running ignored -> "●";
    };
  }

  private static String displayName(String name) {
    return switch (name) {
      case "read" -> "Read";
      case "write" -> "Write";
      case "edit" -> "Edit";
      case "bash" -> "Bash";
      case "grep" -> "Grep";
      case "glob" -> "Glob";
      case "list_dir" -> "List";
      case "todo" -> "Todo";
      case "web_fetch" -> "Fetch";
      case "web_search" -> "Search";
      case "find_definition" -> "Definition";
      case "repo_map" -> "Repo Map";
      case "diagnostics" -> "Diag";
      case "git_status" -> "Git Status";
      case "git_diff" -> "Git Diff";
      case "git_log" -> "Git Log";
      case "git_commit" -> "Git Commit";
      case "task" -> "Agent";
      default -> name;
    };
  }

  private static String category(ToolUse call) {
    String name = call.name().value();
    if (name.equals("edit") || name.equals("write")) return "mutate";
    if (name.equals("bash")) return "execute";
    if (name.equals("todo")) return "plan";
    if (name.equals("task")) return "agent";
    if (name.startsWith("git_")) return "vcs";
    return "inspect";
  }

  private static Tone categoryTone(ToolUse call) {
    return categoryTone(category(call));
  }

  private static Tone categoryTone(String category) {
    return switch (category) {
      case "mutate" -> Tone.MUTATE;
      case "execute" -> Tone.EXECUTE;
      case "plan" -> Tone.PLAN;
      case "agent" -> Tone.AGENT;
      case "vcs" -> Tone.VCS;
      default -> Tone.INSPECT;
    };
  }

  private static Tone connectorTone(ToolUse call) {
    return call.status() instanceof ToolStatus.Failed ? Tone.DANGER
        : call.status() instanceof ToolStatus.Rejected ? Tone.WARNING
        : call.status() instanceof ToolStatus.Running
            || call.status() instanceof ToolStatus.Approved ? Tone.VCS : Tone.MUTED;
  }

  private static Tone statusTone(ToolStatus status) {
    return status instanceof ToolStatus.Done ? Tone.SUCCESS
        : status instanceof ToolStatus.Failed ? Tone.DANGER
        : status instanceof ToolStatus.Rejected ? Tone.WARNING : Tone.INSPECT;
  }

  private static Tone bodyTone(ToolBodyPreview.Tone tone) {
    return switch (tone) {
      case NORMAL -> Tone.NORMAL;
      case MUTED -> Tone.MUTED;
      case DANGER -> Tone.DANGER;
      case SUCCESS -> Tone.SUCCESS;
      case ACCENT -> Tone.INSPECT;
    };
  }

  private static String detail(ToolUse call) {
    Map<String, Object> arguments = call.arguments();
    String name = call.name().value();
    String path = prettyPath(firstString(
        arguments, "path", "file_path", "filepath", "filename"));
    return switch (name) {
      case "read" -> readDetail(call, path, arguments);
      case "write", "edit" -> fallback(path);
      case "bash", "diagnostics" -> commandDetail(call, arguments);
      case "grep" -> grepDetail(call, path, arguments);
      case "glob" -> globDetail(call, arguments);
      case "list_dir" -> listDetail(call, path);
      case "find_definition" -> definitionDetail(call, arguments);
      case "web_fetch" -> fetchDetail(call, arguments);
      case "web_search" -> searchDetail(call, arguments);
      case "git_commit" -> commitDetail(call, arguments);
      case "git_status" -> gitStatusDetail(call, path);
      case "git_diff", "git_log" -> path.isEmpty() ? "." : path;
      case "remember" -> memoryDetail(call, arguments);
      case "forget" -> forgetDetail(call, arguments);
      case "todo" -> todoDetail(arguments.get("todos"));
      case "task" -> taskDetail(call, arguments);
      default -> fallback(firstString(arguments, "display_description"));
    };
  }

  private static String readDetail(
      ToolUse call, String path, Map<String, Object> arguments) {
    String output = fallback(path);
    int offset = integer(arguments.get("offset"));
    if (offset > 0) output += " @" + offset;
    if (call.status() instanceof ToolStatus.Done && !call.status().output().isEmpty()) {
      int lines = countLines(call.status().output());
      if (lines > 1) output += "  ·  " + lines + " lines";
    }
    return output;
  }

  private static String commandDetail(ToolUse call, Map<String, Object> arguments) {
    String command = firstLine(firstString(arguments, "command"));
    if (call.status() instanceof ToolStatus.Done) {
      int exit = parseExitCode(call.status().output());
      if (exit != 0) command += "  ·  exit " + exit;
    }
    return command;
  }

  private static String grepDetail(
      ToolUse call, String path, Map<String, Object> arguments) {
    String pattern = firstString(arguments, "pattern");
    if (pattern.isEmpty()) return "…";
    String detail = path.isEmpty() ? pattern : pattern + "  in  " + path;
    if (call.status() instanceof ToolStatus.Done) {
      int count = foundCount(call.status().output());
      if (count == 0) detail += "  ·  no matches";
      else if (count > 0) detail += "  ·  " + count + (count == 1 ? " match" : " matches");
    }
    return detail;
  }

  private static String globDetail(ToolUse call, Map<String, Object> arguments) {
    String detail = fallback(firstString(arguments, "pattern"));
    if (call.status() instanceof ToolStatus.Done) {
      int count = foundCount(call.status().output());
      if (count == 0) detail += "  ·  no hits";
      else if (count > 0) detail += "  ·  " + count + (count == 1 ? " hit" : " hits");
    }
    return detail;
  }

  private static String listDetail(ToolUse call, String path) {
    String detail = path.isEmpty() ? "." : path;
    if (call.status() instanceof ToolStatus.Done) {
      int count = countLines(call.status().output());
      if (count > 0) detail += "  ·  " + count + " entries";
    }
    return detail;
  }

  private static String definitionDetail(ToolUse call, Map<String, Object> arguments) {
    String detail = firstString(arguments, "symbol");
    if (call.status() instanceof ToolStatus.Done) {
      int count = occurrences(call.status().output(), "## Matches in ");
      if (count > 0) detail += "  ·  " + count + (count == 1 ? " file" : " files");
    }
    return detail;
  }

  private static String fetchDetail(ToolUse call, Map<String, Object> arguments) {
    String detail = firstString(arguments, "url");
    if (call.status() instanceof ToolStatus.Done) {
      String output = call.status().output();
      int newline = output.indexOf('\n');
      int space = output.indexOf(' ', 5);
      if (newline >= 0 && output.startsWith("HTTP ") && space > 5 && space < newline) {
        detail += "  ·  " + output.substring(5, space);
      }
    }
    return detail;
  }

  private static String searchDetail(ToolUse call, Map<String, Object> arguments) {
    String detail = firstString(arguments, "query");
    if (call.status() instanceof ToolStatus.Done) {
      int results = 0;
      for (String line : call.status().output().split("\\R")) {
        int cursor = 0;
        while (cursor < line.length() && Character.isDigit(line.charAt(cursor))) cursor++;
        if (cursor > 0 && cursor < line.length() && line.charAt(cursor) == '.') results++;
      }
      if (results > 0) {
        detail += "  ·  " + results + (results == 1 ? " result" : " results");
      }
    }
    return detail;
  }

  private static String commitDetail(ToolUse call, Map<String, Object> arguments) {
    String detail = beforeNewline(firstString(arguments, "message"));
    if (call.status() instanceof ToolStatus.Done) {
      String output = call.status().output();
      int open = output.indexOf('[');
      int close = open < 0 ? -1 : output.indexOf(']', open);
      int space = open < 0 ? -1 : output.indexOf(' ', open + 1);
      if (space > open && close > space) {
        String hash = output.substring(space + 1, close);
        if (!hash.isEmpty() && hash.length() <= 12) detail += "  ·  " + hash;
      }
    }
    return detail;
  }

  private static String gitStatusDetail(ToolUse call, String path) {
    if (!(call.status() instanceof ToolStatus.Done)) return path.isEmpty() ? "." : path;
    String branch = "";
    int modified = 0, staged = 0, untracked = 0;
    boolean seenBranch = false;
    for (String line : call.status().output().split("\\R")) {
      if (!seenBranch && line.startsWith("## ")) {
        seenBranch = true;
        branch = line.substring(3);
        int dots = branch.indexOf("...");
        int space = branch.indexOf(' ');
        int end = branch.length();
        if (dots >= 0) end = Math.min(end, dots);
        if (space >= 0) end = Math.min(end, space);
        branch = branch.substring(0, end);
      } else if (seenBranch && line.startsWith("??")) {
        untracked++;
      } else if (seenBranch && line.length() >= 3 && line.charAt(2) == ' ') {
        if (line.charAt(0) != ' ' && line.charAt(0) != '?') staged++;
        if (line.charAt(1) == 'M' || line.charAt(1) == 'D') modified++;
      }
    }
    String detail = branch.isEmpty() ? "(detached)" : branch;
    if (modified + staged + untracked == 0) return detail + "  ·  clean";
    var changes = new ArrayList<String>();
    if (modified > 0) changes.add(modified + "M");
    if (staged > 0) changes.add(staged + "S");
    if (untracked > 0) changes.add(untracked + "?");
    return detail + "  ·  " + String.join(" ", changes);
  }

  private static String memoryDetail(ToolUse call, Map<String, Object> arguments) {
    String scope = firstString(arguments, "scope");
    if (scope.isEmpty()) scope = "project";
    String text = firstString(arguments, "text");
    if (text.isEmpty()) return "…";
    String detail = "[" + scope + "] " + firstLine(text);
    if (call.status() instanceof ToolStatus.Done) {
      String output = call.status().output();
      int id = output.indexOf("id=");
      if (id >= 0) {
        int end = output.length();
        for (int index = id + 3; index < output.length(); index++) {
          if (")\n ".indexOf(output.charAt(index)) >= 0) {
            end = index;
            break;
          }
        }
        if (end - id - 3 <= 16) detail += "  ·  " + output.substring(id + 3, end);
      }
    }
    return detail;
  }

  private static String forgetDetail(ToolUse call, Map<String, Object> arguments) {
    String id = firstString(arguments, "id");
    String substring = firstString(arguments, "substring");
    String detail = !id.isEmpty() ? "id=" + id
        : !substring.isEmpty() ? "“" + substring + "”" : "…";
    detail = firstLine(detail);
    if (call.status() instanceof ToolStatus.Done) {
      String output = call.status().output();
      if (output.startsWith("Forgot ")) {
        int space = output.indexOf(' ', 7);
        if (space > 7) detail += "  ·  " + output.substring(7, space) + " removed";
      } else if (output.startsWith("No memory matched")) {
        detail += "  ·  no match";
      }
    }
    return detail;
  }

  private static String todoDetail(Object value) {
    if (!(value instanceof List<?> todos) || todos.isEmpty()) return "…";
    int total = 0, done = 0, active = 0;
    for (Object item : todos) {
      if (!(item instanceof Map<?, ?> todo)) continue;
      total++;
      Object status = todo.get("status");
      if (Objects.equals(status, "completed")) done++;
      if (Objects.equals(status, "in_progress")) active++;
    }
    return done + "/" + total + (active > 0 ? "  ·  " + active + " in progress" : "");
  }

  private static String taskDetail(ToolUse call, Map<String, Object> arguments) {
    String type = firstString(arguments, "agent_type");
    if (type.isEmpty()) type = "general";
    String what = firstString(arguments, "display_description");
    if (what.isEmpty()) {
      what = beforeNewline(firstString(arguments, "prompt"));
      if (what.getBytes(StandardCharsets.UTF_8).length > 60) {
        what = utf8Prefix(what, 57) + "…";
      }
    }
    String detail = what.isEmpty() ? type : type + "  ·  " + what;
    if (call.status().isTerminal()) {
      String output = call.status().output();
      int report = output.indexOf("report (");
      int end = report < 0 ? -1 : output.indexOf(')', report);
      if (end > report) {
        String inner = output.substring(report + 8, end);
        int comma = inner.indexOf(", ");
        if (comma >= 0) inner = inner.substring(comma + 2);
        detail += "  ·  " + inner;
      }
      if (call.status() instanceof ToolStatus.Failed) detail += "  ·  failed";
    }
    return detail;
  }

  private static String firstString(Map<String, Object> arguments, String... keys) {
    for (String key : keys) {
      if (arguments.get(key) instanceof String value && !value.isEmpty()) return value;
    }
    return "";
  }

  private static int integer(Object value) {
    return value instanceof Number number ? number.intValue() : 0;
  }

  private static String firstLine(String value) {
    if (value.isEmpty()) return "…";
    int newline = value.indexOf('\n');
    return newline < 0 ? value : value.substring(0, newline) + " …";
  }

  private static String beforeNewline(String value) {
    int newline = value.indexOf('\n');
    return newline < 0 ? value : value.substring(0, newline);
  }

  private static int countLines(String value) {
    if (value.isEmpty()) return 0;
    int count = 0;
    for (int index = 0; index < value.length(); index++) {
      if (value.charAt(index) == '\n') count++;
    }
    return count + (value.endsWith("\n") ? 0 : 1);
  }

  private static int foundCount(String output) {
    if (!output.startsWith("Found ")) return -1;
    int cursor = "Found ".length();
    int count = 0;
    boolean found = false;
    while (cursor < output.length() && Character.isDigit(output.charAt(cursor))) {
      count = count * 10 + output.charAt(cursor++) - '0';
      found = true;
    }
    return found ? count : -1;
  }

  private static int occurrences(String source, String needle) {
    int count = 0;
    for (int index = source.indexOf(needle); index >= 0;
        index = source.indexOf(needle, index + needle.length())) count++;
    return count;
  }

  private static int parseExitCode(String output) {
    for (String marker : List.of("failed with exit code ", "[exit code ")) {
      int index = output.lastIndexOf(marker);
      if (index < 0) continue;
      int cursor = index + marker.length();
      int end = cursor;
      while (end < output.length() && Character.isDigit(output.charAt(end))) end++;
      if (end == cursor) return 1;
      try {
        return Integer.parseInt(output.substring(cursor, end));
      } catch (NumberFormatException ignored) {
        return 1;
      }
    }
    return output.contains("timed out") ? 124 : 0;
  }

  private static String fallback(String value) {
    return value.isEmpty() ? "…" : value;
  }

  private static String prettyPath(String path) {
    if (path.isEmpty()) return path;
    String cwd = java.nio.file.Path.of("").toAbsolutePath().toString();
    if (path.length() > cwd.length() && path.startsWith(cwd)
        && path.charAt(cwd.length()) == '/') {
      return path.substring(cwd.length() + 1);
    }
    String home = System.getenv("HOME");
    if (home != null && !home.isEmpty() && path.length() > home.length()
        && path.startsWith(home) && path.charAt(home.length()) == '/') {
      return "~/" + path.substring(home.length() + 1);
    }
    return path;
  }

  private static String utf8Prefix(String value, int byteLimit) {
    int end = 0;
    int bytes = 0;
    while (end < value.length()) {
      int codePoint = value.codePointAt(end);
      int encoded = new String(Character.toChars(codePoint))
          .getBytes(StandardCharsets.UTF_8).length;
      if (bytes + encoded > byteLimit) break;
      bytes += encoded;
      end += Character.charCount(codePoint);
    }
    return value.substring(0, end);
  }

  private static String smallCaps(String value) {
    var result = new StringBuilder(value.length() * 2);
    for (int index = 0; index < value.length(); index++) {
      if (index > 0) result.append(' ');
      result.append(Character.toUpperCase(value.charAt(index)));
    }
    return result.toString();
  }

  private static Span span(String text, Tone tone) {
    return span(text, tone, false, false, false);
  }

  private static Span span(
      String text, Tone tone, boolean bold, boolean dim, boolean italic) {
    return new Span(text, tone, bold, dim, italic);
  }

  private static List<Span> clip(List<Span> spans, int width) {
    int total = spans.stream().mapToInt(span -> columns(span.text())).sum();
    if (total <= width) return List.copyOf(spans);
    if (width <= 0) return List.of();
    var result = new ArrayList<Span>();
    int remaining = Math.max(0, width - 1);
    Span ellipsisStyle = spans.isEmpty() ? span("", Tone.NORMAL) : spans.getFirst();
    outer:
    for (Span source : spans) {
      var text = new StringBuilder();
      for (int offset = 0; offset < source.text().length();) {
        int codePoint = source.text().codePointAt(offset);
        int cellWidth = UnicodeWidth.of(codePoint);
        if (cellWidth > remaining) {
          if (!text.isEmpty()) result.add(new Span(text.toString(), source.tone(),
              source.bold(), source.dim(), source.italic()));
          ellipsisStyle = source;
          break outer;
        }
        text.appendCodePoint(codePoint);
        remaining -= cellWidth;
        offset += Character.charCount(codePoint);
      }
      if (!text.isEmpty()) result.add(new Span(text.toString(), source.tone(),
          source.bold(), source.dim(), source.italic()));
      ellipsisStyle = source;
      if (remaining == 0) break;
    }
    result.add(new Span("…", ellipsisStyle.tone(), ellipsisStyle.bold(),
        ellipsisStyle.dim(), ellipsisStyle.italic()));
    return List.copyOf(result);
  }

  private static String clip(String value, int width) {
    if (width <= 0 || value.isEmpty()) return "";
    if (columns(value) <= width) return value;
    if (width == 1) return "…";
    var result = new StringBuilder();
    int used = 0;
    for (int offset = 0; offset < value.length();) {
      int codePoint = value.codePointAt(offset);
      int cellWidth = UnicodeWidth.of(codePoint);
      if (used + cellWidth > width - 1) break;
      result.appendCodePoint(codePoint);
      used += cellWidth;
      offset += Character.charCount(codePoint);
    }
    return result.append('…').toString();
  }

  private static int columns(String value) {
    return UnicodeWidth.stringWidth(value, UnicodeWidth.Mode.MODERN);
  }
}
