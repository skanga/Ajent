package com.github.skanga.ajent.terminal.ui;

import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Pure Ajent-compatible discriminator for inline tool-body previews. */
public final class ToolBodyPreview {
  private static final Pattern GIT_PLUMBING = Pattern.compile(
      "^(diff |index |new file|deleted file|old mode|new mode|similarity |rename |copy )");
  private static final Pattern COMPILER_DIAGNOSTIC = Pattern.compile(
      "^(.+?):(\\d+)(?::(\\d+))?:\\s*(error|warning|note):\\s*(.*)$");
  public enum Kind {
    NONE, CODE_BLOCK, FAILURE, EDIT_DIFF, GIT_DIFF, TODO_LIST,
    BASH_OUTPUT, FILE_READ, FILE_WRITE, JSON
  }

  public enum TodoStatus { PENDING, IN_PROGRESS, COMPLETED }

  public enum Tone { NORMAL, MUTED, DANGER, SUCCESS, ACCENT }

  public record Row(String text, Tone tone) {
    public Row {
      text = Objects.requireNonNull(text, "text");
      tone = Objects.requireNonNull(tone, "tone");
    }
  }

  public record EditHunk(String oldText, String newText) {
    public EditHunk {
      oldText = Objects.requireNonNull(oldText, "oldText");
      newText = Objects.requireNonNull(newText, "newText");
    }
  }

  public record TodoItem(String content, TodoStatus status) {
    public TodoItem {
      content = Objects.requireNonNull(content, "content");
      status = Objects.requireNonNull(status, "status");
    }
  }

  public record Preview(
      Kind kind,
      String text,
      List<EditHunk> hunks,
      List<TodoItem> todos,
      boolean streaming,
      boolean showAll,
      boolean tailOnly,
      boolean failed,
      int startLine,
      Set<Integer> highlightLines,
      int codeTail,
      boolean footerStats,
      int editTailPerSide,
      int streamHunkNumber) {
    public Preview {
      kind = Objects.requireNonNull(kind, "kind");
      text = Objects.requireNonNull(text, "text");
      hunks = List.copyOf(hunks);
      todos = List.copyOf(todos);
      highlightLines = Collections.unmodifiableSet(new TreeSet<>(highlightLines));
      if (startLine < 1 || codeTail < 0 || editTailPerSide < 0 || streamHunkNumber < 0) {
        throw new IllegalArgumentException("invalid tool preview bound");
      }
    }
  }

  private ToolBodyPreview() {}

  public static List<Row> render(Preview preview) {
    Objects.requireNonNull(preview, "preview");
    return switch (preview.kind()) {
      case NONE -> List.of();
      case CODE_BLOCK -> codeRows(preview, Tone.NORMAL);
      case JSON -> jsonRows(preview);
      case FAILURE -> codeRows(preview, Tone.DANGER);
      case BASH_OUTPUT -> bashRows(preview);
      case FILE_READ -> readRows(preview);
      case FILE_WRITE -> writeRows(preview);
      case GIT_DIFF -> gitDiffRows(preview);
      case EDIT_DIFF -> editRows(preview);
      case TODO_LIST -> todoRows(preview);
    };
  }

  public static Preview describe(
      ToolUse call, int terminalRows, Map<String, Set<Integer>> grepHits) {
    Objects.requireNonNull(call, "call");
    Objects.requireNonNull(grepHits, "grepHits");
    String name = call.name().value();
    Map<String, Object> arguments = call.arguments();
    ToolStatus status = call.status();
    boolean terminal = status.isTerminal();
    boolean done = status instanceof ToolStatus.Done;
    boolean failed = status instanceof ToolStatus.Failed;

    if (name.equals("edit")) {
      if (failed && !status.output().isEmpty()) {
        return preview(Kind.FAILURE).text(status.output()).failed(true).freeze();
      }
      if (terminal && !failed) {
        String diff = fencedDiff(status.output());
        if (!diff.isEmpty()) return preview(Kind.GIT_DIFF).text(diff).showAll(true).freeze();
      }
      List<EditHunk> hunks = editHunks(arguments);
      if (!hunks.isEmpty()) {
        boolean streaming = !terminal;
        Builder result = preview(Kind.EDIT_DIFF).hunks(hunks).streaming(streaming)
            .showAll(!streaming);
        if (streaming) {
          result.editTailPerSide(Math.clamp((streamBodyBudget(terminalRows) - 1) / 2, 1, 6));
          if (arguments.get("edits") instanceof List<?> edits) {
            result.streamHunkNumber(edits.size());
          }
        }
        return result.freeze();
      }
      return none();
    }

    if (name.equals("bash") || name.equals("diagnostics")) {
      if (status instanceof ToolStatus.Running running && !running.progressText().isEmpty()) {
        return preview(Kind.BASH_OUTPUT).text(running.progressText()).streaming(true).freeze();
      }
      if (terminal) {
        String text = stripBashOutputFence(status.output());
        return text.isEmpty() ? none()
            : preview(Kind.BASH_OUTPUT).text(text).failed(failed).freeze();
      }
      return none();
    }

    if (name.equals("write")) {
      if (failed && !status.output().isEmpty()) {
        return preview(Kind.FAILURE).text(status.output()).failed(true).freeze();
      }
      String content = stringArgument(arguments, "content");
      boolean streaming = !terminal;
      if (!content.isEmpty()) {
        Builder result = preview(Kind.FILE_WRITE).text(content).streaming(streaming)
            .showAll(!streaming).footerStats(!streaming);
        if (streaming) result.codeTail(Math.clamp(streamBodyBudget(terminalRows), 3, 12));
        return result.freeze();
      }
      return streaming ? preview(Kind.FILE_WRITE).streaming(true).footerStats(false).freeze()
          : none();
    }

    if (name.equals("git_diff") && done) {
      String output = status.output();
      return output.isEmpty() || output.equals("no changes") ? none()
          : preview(Kind.GIT_DIFF).text(output).freeze();
    }

    if ((name.equals("read") || name.equals("find_definition")) && done) {
      if (status.output().isEmpty()) return none();
      int startLine = positiveInteger(arguments, "start_line",
          positiveInteger(arguments, "offset", 1));
      String path = pathArgument(arguments);
      return preview(Kind.FILE_READ).text(status.output()).startLine(startLine)
          .highlightLines(grepHits.getOrDefault(path, Set.of())).freeze();
    }

    if (name.equals("web_fetch") && done) {
      return status.output().isEmpty() ? none()
          : preview(Kind.JSON).text(status.output()).freeze();
    }

    if (Set.of("grep", "glob", "list_dir", "web_search", "git_status", "git_log",
        "git_commit").contains(name) && done) {
      return status.output().isEmpty() ? none()
          : preview(Kind.CODE_BLOCK).text(status.output()).freeze();
    }

    if (name.equals("task")) {
      if (status instanceof ToolStatus.Running running && !running.progressText().isEmpty()) {
        return preview(Kind.BASH_OUTPUT).text(tailWindow(running.progressText(), 8))
            .streaming(true).freeze();
      }
      if (terminal && !status.output().isEmpty()) {
        return preview(Kind.CODE_BLOCK).text(headWindow(stripTaskHeader(status.output()), 4))
            .showAll(true).freeze();
      }
      return none();
    }

    if (failed && !status.output().isEmpty()) {
      return preview(Kind.FAILURE).text(status.output()).failed(true).freeze();
    }

    if (name.equals("todo")) {
      List<TodoItem> todos = todoItems(arguments.get("todos"));
      if (!todos.isEmpty()) return preview(Kind.TODO_LIST).todos(todos).freeze();
    }
    return none();
  }

  public static Map<String, Set<Integer>> collectGrepHits(List<ToolUse> calls) {
    Objects.requireNonNull(calls, "calls");
    var mutable = new LinkedHashMap<String, TreeSet<Integer>>();
    for (ToolUse call : calls) {
      if (!call.name().value().equals("grep") || call.status().output().isEmpty()) continue;
      String path = "";
      for (String line : lines(call.status().output())) {
        if (line.startsWith("## Matches in ")) {
          path = line.substring("## Matches in ".length());
        } else if (!path.isEmpty() && line.startsWith("### L")) {
          int start = parseLeadingPositive(line.substring("### L".length()));
          if (start > 0) mutable.computeIfAbsent(path, ignored -> new TreeSet<>()).add(start);
        }
      }
    }
    var result = new LinkedHashMap<String, Set<Integer>>();
    mutable.forEach((path, hits) -> result.put(path, Set.copyOf(hits)));
    return Map.copyOf(result);
  }

  private static List<Row> codeRows(Preview preview, Tone tone) {
    List<String> visible = visibleTail(preview.text(), preview.showAll(), 4);
    var result = new ArrayList<Row>(visible.size());
    for (int index = 0; index < visible.size(); index++) {
      result.add(new Row(String.format(java.util.Locale.ROOT, "%3d │ %s",
          index + 1, visible.get(index)), tone));
    }
    return List.copyOf(result);
  }

  private static List<Row> bashRows(Preview preview) {
    Integer passed = countBefore(preview.text(), "tests passed");
    Integer failed = countBefore(preview.text(), "tests failed");
    int passedCount = passed == null ? 0 : passed;
    int failedCount = failed == null ? 0 : failed;
    if (passedCount + failedCount > 0) {
      if (failedCount == 0) {
        return List.of(new Row("✓ " + passedCount + "/" + passedCount + " tests passed",
            Tone.SUCCESS));
      }
      var result = new ArrayList<Row>();
      result.add(new Row("✗ " + failedCount + "/" + (passedCount + failedCount)
          + " tests failed", Tone.DANGER));
      int names = 0;
      for (String line : lines(preview.text())) {
        int marker = line.indexOf("[  FAILED  ]");
        if (marker < 0 || names == 3) continue;
        String name = line.substring(marker + "[  FAILED  ]".length()).strip();
        if (!name.isEmpty()) {
          result.add(new Row("    " + name, Tone.DANGER));
          names++;
        }
      }
      if (names < failedCount) {
        result.add(new Row("    ⋯ " + (failedCount - names) + " more failing", Tone.MUTED));
      }
      return List.copyOf(result);
    }
    List<Diagnostic> diagnostics = compilerDiagnostics(preview.text());
    if (!diagnostics.isEmpty()) {
      boolean oneFile = diagnostics.stream().allMatch(
          diagnostic -> diagnostic.path().equals(diagnostics.getFirst().path()));
      long errors = diagnostics.stream().filter(diagnostic -> diagnostic.severity().equals("error"))
          .count();
      long count = errors == 0 ? diagnostics.size() : errors;
      String header = oneFile
          ? "✗ " + count + (count == 1 ? " issue in " : " issues in ")
              + diagnostics.getFirst().path()
          : "✗ " + diagnostics.size()
              + (diagnostics.size() == 1 ? " issue across files" : " issues across files");
      var result = new ArrayList<Row>();
      result.add(new Row(header, Tone.DANGER));
      int shown = Math.min(3, diagnostics.size());
      for (int index = 0; index < shown; index++) {
        Diagnostic diagnostic = diagnostics.get(index);
        String coordinate = diagnostic.line()
            + (diagnostic.column().isEmpty() ? "" : ":" + diagnostic.column());
        result.add(new Row("    " + coordinate + "  " + diagnostic.message(),
            diagnostic.severity().equals("error") ? Tone.DANGER : Tone.MUTED));
      }
      if (shown < diagnostics.size()) {
        result.add(new Row("    ⋯ " + (diagnostics.size() - shown) + " more issues", Tone.MUTED));
      }
      return List.copyOf(result);
    }
    List<String> visible = visibleTail(preview.text(), false, 4);
    var result = new ArrayList<Row>(visible.size());
    for (String line : visible) result.add(new Row("  > │ " + line, Tone.NORMAL));
    return List.copyOf(result);
  }

  private static List<Row> readRows(Preview preview) {
    List<String> visible = visibleTail(preview.text(), preview.showAll(), 5);
    var result = new ArrayList<Row>();
    if (!preview.highlightLines().isEmpty()) {
      String summary = preview.highlightLines().stream().limit(5).map(String::valueOf)
          .collect(java.util.stream.Collectors.joining(", "));
      int more = preview.highlightLines().size() - Math.min(5, preview.highlightLines().size());
      result.add(new Row("▸ matches: " + summary + (more > 0 ? " +" + more + " more" : ""),
          Tone.ACCENT));
    }
    int lineNumber = preview.startLine();
    for (String line : visible) {
      boolean highlighted = preview.highlightLines().contains(lineNumber);
      result.add(new Row(String.format(java.util.Locale.ROOT, "%s%3d │ %s",
          highlighted ? "▸" : " ", lineNumber, line),
          highlighted ? Tone.ACCENT : Tone.NORMAL));
      lineNumber++;
    }
    return List.copyOf(result);
  }

  private static List<Row> jsonRows(Preview preview) {
    List<String> pretty = prettyJson(preview.text());
    if (pretty.isEmpty()) return codeRows(preview, Tone.NORMAL);
    List<String> visible = preview.showAll() || pretty.size() <= 4
        ? pretty : pretty.subList(pretty.size() - 4, pretty.size());
    return visible.stream().map(line -> new Row(line, Tone.NORMAL)).toList();
  }

  private static List<Row> writeRows(Preview preview) {
    List<String> all = lines(preview.text());
    List<String> visible = preview.showAll() || all.size() <= preview.codeTail()
        ? all : all.subList(all.size() - preview.codeTail(), all.size());
    var result = new ArrayList<Row>();
    int lineNumber = preview.showAll() ? 1 : Math.max(1, all.size() - visible.size() + 1);
    for (String line : visible) {
      result.add(new Row(String.format(java.util.Locale.ROOT, "%3d %s", lineNumber++, line),
          Tone.SUCCESS));
    }
    if (preview.footerStats() && !preview.text().isEmpty()) {
      int bytes = preview.text().getBytes(StandardCharsets.UTF_8).length;
      result.add(new Row("    " + all.size() + (all.size() == 1 ? " line · " : " lines · ")
          + formatBytes(bytes), Tone.MUTED));
    }
    return List.copyOf(result);
  }

  private static List<Row> todoRows(Preview preview) {
    var result = new ArrayList<Row>();
    int shown = Math.min(8, preview.todos().size());
    for (int index = 0; index < shown; index++) {
      TodoItem item = preview.todos().get(index);
      String marker = switch (item.status()) {
        case COMPLETED -> "  ✓";
        case IN_PROGRESS -> "  ◍";
        case PENDING -> "  ○";
      };
      Tone tone = switch (item.status()) {
        case COMPLETED -> Tone.SUCCESS;
        case IN_PROGRESS -> Tone.ACCENT;
        case PENDING -> Tone.MUTED;
      };
      result.add(new Row(marker + " │ " + item.content(), tone));
    }
    if (shown < preview.todos().size()) {
      result.add(new Row("⋯ " + (preview.todos().size() - shown) + " more", Tone.MUTED));
    }
    return List.copyOf(result);
  }

  private static List<Row> editRows(Preview preview) {
    if (preview.hunks().isEmpty()) return List.of();
    if (preview.streaming()) return streamingEditRows(preview);
    int shown = preview.showAll() ? preview.hunks().size() : Math.min(4, preview.hunks().size());
    var result = new ArrayList<Row>();
    for (int index = 0; index < shown; index++) {
      EditHunk hunk = preview.hunks().get(index);
      String prefix = preview.hunks().size() > 1
          ? "edit " + (index + 1) + "/" + preview.hunks().size() + "  ·  " : "";
      result.add(new Row("   " + prefix + "−" + countLines(hunk.oldText()) + " / +"
          + countLines(hunk.newText()), Tone.ACCENT));
      addDiffSide(result, hunk.oldText(), '-', Integer.MAX_VALUE);
      addDiffSide(result, hunk.newText(), '+', Integer.MAX_VALUE);
    }
    if (shown < preview.hunks().size()) {
      result.add(new Row("⋯ " + (preview.hunks().size() - shown) + " more edits", Tone.MUTED));
    }
    return List.copyOf(result);
  }

  private static List<Row> streamingEditRows(Preview preview) {
    EditHunk current = preview.hunks().getLast();
    String prefix = preview.streamHunkNumber() > 0
        ? "edit " + preview.streamHunkNumber() + "  ·  " : "";
    Row header = new Row("   " + prefix + "−" + countLines(current.oldText()) + " / +"
        + countLines(current.newText()), Tone.ACCENT);
    var body = new ArrayList<Row>();
    int budget = Math.max(2, preview.editTailPerSide() * 2);
    for (EditHunk hunk : preview.hunks()) {
      addDiffSide(body, hunk.oldText(), '-', budget);
      addDiffSide(body, hunk.newText(), '+', budget);
    }
    if (body.size() > budget) body.subList(0, body.size() - budget).clear();
    var result = new ArrayList<Row>(body.size() + 1);
    result.add(header);
    result.addAll(body);
    return List.copyOf(result);
  }

  private static void addDiffSide(List<Row> rows, String text, char marker, int tail) {
    List<String> all = lines(text);
    int start = Math.max(0, all.size() - tail);
    Tone tone = marker == '+' ? Tone.SUCCESS : Tone.DANGER;
    for (int index = start; index < all.size(); index++) {
      rows.add(new Row(" " + marker + " " + all.get(index), tone));
    }
  }

  private static List<Row> gitDiffRows(Preview preview) {
    List<String> visible = visibleTail(preview.text(), preview.showAll(), 4);
    var result = new ArrayList<Row>();
    String from = "";
    for (String line : visible) {
      if (isGitPlumbing(line)) continue;
      if (line.startsWith("--- ")) {
        from = stripDiffPrefix(line.substring(4));
      } else if (line.startsWith("+++ ")) {
        String to = stripDiffPrefix(line.substring(4));
        result.add(new Row("~ " + (to.equals("/dev/null") ? from : to), Tone.ACCENT));
        from = "";
      } else if (line.startsWith("@@")) {
        result.add(new Row("~ " + line, Tone.ACCENT));
      } else if (line.startsWith("+")) {
        result.add(new Row(" + " + line.substring(1), Tone.SUCCESS));
      } else if (line.startsWith("-")) {
        result.add(new Row(" - " + line.substring(1), Tone.DANGER));
      } else {
        result.add(new Row("   " + (line.startsWith(" ") ? line.substring(1) : line),
            Tone.NORMAL));
      }
    }
    return List.copyOf(result);
  }

  private static boolean isGitPlumbing(String line) {
    return GIT_PLUMBING.matcher(line).find();
  }

  private static String stripDiffPrefix(String path) {
    return path.startsWith("a/") || path.startsWith("b/") ? path.substring(2) : path;
  }

  private static List<String> visibleTail(String text, boolean showAll, int limit) {
    List<String> all = lines(text);
    return showAll || all.size() <= limit ? all : all.subList(all.size() - limit, all.size());
  }

  private static Integer countBefore(String text, String phrase) {
    int phraseAt = text.indexOf(phrase);
    if (phraseAt < 0) return null;
    int end = phraseAt;
    while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
    int start = end;
    while (start > 0 && Character.isDigit(text.charAt(start - 1))) start--;
    return start == end ? null : Integer.parseInt(text.substring(start, end));
  }

  private static int countLines(String text) { return lines(text).size(); }

  private static String formatBytes(int bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
    return String.format(java.util.Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
  }

  private record Diagnostic(
      String path, String line, String column, String severity, String message) {}

  private static List<Diagnostic> compilerDiagnostics(String text) {
    var result = new ArrayList<Diagnostic>();
    for (String line : lines(text)) {
      var matcher = COMPILER_DIAGNOSTIC.matcher(line);
      if (!matcher.matches()) continue;
      result.add(new Diagnostic(matcher.group(1), matcher.group(2),
          matcher.group(3) == null ? "" : matcher.group(3), matcher.group(4), matcher.group(5)));
    }
    return List.copyOf(result);
  }

  private static List<String> prettyJson(String text) {
    int first = 0;
    while (first < text.length() && Character.isWhitespace(text.charAt(first))) first++;
    if (first == text.length() || (text.charAt(first) != '{' && text.charAt(first) != '[')) {
      return List.of();
    }
    var result = new ArrayList<String>();
    var line = new StringBuilder();
    int depth = 0;
    for (int index = first; index < text.length();) {
      char current = text.charAt(index);
      if (Character.isWhitespace(current)) {
        index++;
        continue;
      }
      if (current == '"') {
        indent(line, depth);
        int end = index + 1;
        boolean escaped = false;
        while (end < text.length()) {
          char value = text.charAt(end++);
          if (value == '"' && !escaped) break;
          escaped = value == '\\' && !escaped;
          if (value != '\\') escaped = false;
        }
        line.append(text, index, end);
        index = end;
        continue;
      }
      if (current == '{' || current == '[') {
        indent(line, depth);
        line.append(current);
        result.add(line.toString());
        line = new StringBuilder();
        depth++;
      } else if (current == '}' || current == ']') {
        if (!line.isEmpty()) {
          result.add(line.toString());
          line = new StringBuilder();
        }
        depth = Math.max(0, depth - 1);
        indent(line, depth);
        line.append(current);
      } else if (current == ',') {
        line.append(current);
        result.add(line.toString());
        line = new StringBuilder();
      } else if (current == ':') {
        line.append(": ");
      } else {
        indent(line, depth);
        int end = index + 1;
        while (end < text.length() && ",]} \t\r\n".indexOf(text.charAt(end)) < 0) end++;
        line.append(text, index, end);
        index = end;
        continue;
      }
      index++;
    }
    if (!line.isEmpty()) result.add(line.toString());
    return List.copyOf(result);
  }

  private static void indent(StringBuilder line, int depth) {
    if (line.isEmpty()) line.append("  ".repeat(depth));
  }

  private static List<EditHunk> editHunks(Map<String, Object> arguments) {
    var result = new ArrayList<EditHunk>();
    if (arguments.get("edits") instanceof List<?> edits && !edits.isEmpty()) {
      for (Object raw : edits) {
        if (!(raw instanceof Map<?, ?> edit)) continue;
        result.add(new EditHunk(firstString(edit, "old_text", "old_string"),
            firstString(edit, "new_text", "new_string")));
      }
    } else {
      String oldText = firstString(arguments, "old_text", "old_string");
      String newText = firstString(arguments, "new_text", "new_string");
      if (!oldText.isEmpty() || !newText.isEmpty()) result.add(new EditHunk(oldText, newText));
    }
    return List.copyOf(result);
  }

  private static List<TodoItem> todoItems(Object raw) {
    if (!(raw instanceof List<?> values) || values.isEmpty()) return List.of();
    var result = new ArrayList<TodoItem>();
    for (Object value : values) {
      if (!(value instanceof Map<?, ?> item)) continue;
      String content = item.get("content") instanceof String text ? text : "";
      String status = item.get("status") instanceof String text ? text : "pending";
      TodoStatus parsed = switch (status) {
        case "completed" -> TodoStatus.COMPLETED;
        case "in_progress" -> TodoStatus.IN_PROGRESS;
        default -> TodoStatus.PENDING;
      };
      result.add(new TodoItem(content, parsed));
    }
    return List.copyOf(result);
  }

  private static String fencedDiff(String output) {
    int start = output.indexOf("```diff\n");
    if (start < 0) return "";
    start += "```diff\n".length();
    int end = output.indexOf("\n```", start);
    return output.substring(start, end < 0 ? output.length() : end);
  }

  static String stripBashOutputFence(String output) {
    String value = dropTrailer(dropTrailer(output, "\n\n[elapsed:"), "\n\n[output truncated");
    int fence = value.indexOf("```");
    if (fence < 0) return value;
    int bodyStart = value.indexOf('\n', fence + 3);
    if (bodyStart < 0) return value.substring(fence + 3);
    bodyStart++;
    int close = value.lastIndexOf("```");
    if (close <= bodyStart) close = value.length();
    int bodyEnd = close;
    while (bodyEnd > bodyStart && "\n\r".indexOf(value.charAt(bodyEnd - 1)) >= 0) bodyEnd--;
    String header = value.substring(0, fence).stripTrailing();
    String body = value.substring(bodyStart, bodyEnd);
    if (header.isEmpty()) return body;
    return body.isEmpty() ? header : header + "\n\n" + body;
  }

  private static String dropTrailer(String value, String marker) {
    int position = value.lastIndexOf(marker);
    return (position < 0 ? value : value.substring(0, position)).stripTrailing();
  }

  private static String stripTaskHeader(String output) {
    int newline = output.indexOf('\n');
    String value = newline >= 0 && output.substring(0, newline).contains("Subagent report")
        ? output.substring(newline + 1) : output;
    int start = 0;
    while (start < value.length() && (value.charAt(start) == '\n' || value.charAt(start) == ' ')) {
      start++;
    }
    return value.substring(start);
  }

  private static String headWindow(String text, int keepLines) {
    List<String> all = lines(text);
    if (all.size() <= keepLines) return text;
    int more = all.size() - keepLines;
    return String.join("\n", all.subList(0, keepLines)) + "\n⋯ " + more
        + (more == 1 ? " more line" : " more lines");
  }

  private static String tailWindow(String text, int keepLines) {
    List<String> all = lines(text);
    return all.size() <= keepLines ? text
        : String.join("\n", all.subList(all.size() - keepLines, all.size()));
  }

  private static List<String> lines(String text) {
    if (text.isEmpty()) return List.of();
    String[] split = text.split("\n", -1);
    int size = split.length;
    if (size > 0 && split[size - 1].isEmpty()) size--;
    return List.of(split).subList(0, size);
  }

  private static int streamBodyBudget(int terminalRows) {
    return Math.max(3, (terminalRows > 0 ? terminalRows : 24) - 15);
  }

  private static int positiveInteger(Map<String, Object> values, String key, int fallback) {
    Object raw = values.get(key);
    return raw instanceof Number number && number.intValue() >= 1 ? number.intValue() : fallback;
  }

  private static int parseLeadingPositive(String text) {
    int value = 0;
    int index = 0;
    while (index < text.length() && Character.isDigit(text.charAt(index))) {
      value = value * 10 + text.charAt(index++) - '0';
    }
    return value;
  }

  private static String pathArgument(Map<String, Object> arguments) {
    return firstString(arguments, "path", "file_path", "filepath", "filename");
  }

  private static String stringArgument(Map<String, Object> arguments, String key) {
    return arguments.get(key) instanceof String text ? text : "";
  }

  private static String firstString(Map<?, ?> values, String... keys) {
    for (String key : keys) {
      if (values.get(key) instanceof String text && !text.isEmpty()) return text;
    }
    return "";
  }

  private static Preview none() { return preview(Kind.NONE).freeze(); }

  private static Builder preview(Kind kind) { return new Builder(kind); }

  private static final class Builder {
    private final Kind kind;
    private String text = "";
    private List<EditHunk> hunks = List.of();
    private List<TodoItem> todos = List.of();
    private boolean streaming;
    private boolean showAll;
    private boolean tailOnly = true;
    private boolean failed;
    private int startLine = 1;
    private Set<Integer> highlightLines = Set.of();
    private int codeTail = 3;
    private boolean footerStats = true;
    private int editTailPerSide = 2;
    private int streamHunkNumber;

    private Builder(Kind kind) { this.kind = kind; }
    private Builder text(String value) { text = value; return this; }
    private Builder hunks(List<EditHunk> value) { hunks = value; return this; }
    private Builder todos(List<TodoItem> value) { todos = value; return this; }
    private Builder streaming(boolean value) { streaming = value; return this; }
    private Builder showAll(boolean value) { showAll = value; return this; }
    private Builder failed(boolean value) { failed = value; return this; }
    private Builder startLine(int value) { startLine = value; return this; }
    private Builder highlightLines(Set<Integer> value) { highlightLines = value; return this; }
    private Builder codeTail(int value) { codeTail = value; return this; }
    private Builder footerStats(boolean value) { footerStats = value; return this; }
    private Builder editTailPerSide(int value) { editTailPerSide = value; return this; }
    private Builder streamHunkNumber(int value) { streamHunkNumber = value; return this; }

    private Preview freeze() {
      return new Preview(kind, text, hunks, todos, streaming, showAll, tailOnly, failed,
          startLine, highlightLines, codeTail, footerStats, editTailPerSide, streamHunkNumber);
    }
  }
}
