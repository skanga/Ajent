package com.github.skanga.ajent.terminal.ui;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Fenced-block extraction and immutable Ctrl-G picker state. */
public final class CodeBlockPicker {
  private CodeBlockPicker() {}

  public enum Shell { NONE, POSIX, CMD, POWERSHELL }

  public record Block(String language, String body, int lineCount) {
    public Block {
      Objects.requireNonNull(language, "language");
      Objects.requireNonNull(body, "body");
      if (lineCount < 1) throw new IllegalArgumentException("lineCount must be positive");
    }

    public String preview() {
      int newline = body.indexOf('\n');
      return newline < 0 ? body : body.substring(0, newline);
    }
  }

  public sealed interface State permits Closed, Open, Result {}
  public record Closed() implements State {}
  public record Open(List<Block> blocks, int index) implements State {
    public Open {
      blocks = List.copyOf(blocks);
    }
  }
  public record Result(String command, String output, int exitCode, boolean timedOut, int scroll)
      implements State {
    public Result {
      Objects.requireNonNull(command, "command");
      Objects.requireNonNull(output, "output");
    }
    public Result(String command, String output, int exitCode, boolean timedOut) {
      this(command, output, exitCode, timedOut, 0);
    }
  }

  public static List<Block> extract(String text) {
    Objects.requireNonNull(text, "text");
    var blocks = new ArrayList<Block>();
    boolean inside = false;
    char fence = '`';
    String language = "";
    var body = new StringBuilder();
    int position = 0;
    while (position <= text.length()) {
      int newline = text.indexOf('\n', position);
      int end = newline < 0 ? text.length() : newline;
      String line = text.substring(position, end);
      if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
      String trimmed = stripUpToThreeSpaces(line);
      boolean marker = trimmed.length() >= 3
          && (trimmed.charAt(0) == '`' || trimmed.charAt(0) == '~')
          && trimmed.charAt(1) == trimmed.charAt(0) && trimmed.charAt(2) == trimmed.charAt(0);
      if (!inside) {
        if (marker) {
          inside = true;
          fence = trimmed.charAt(0);
          int offset = 3;
          while (offset < trimmed.length() && trimmed.charAt(offset) == fence) offset++;
          while (offset < trimmed.length() && trimmed.charAt(offset) == ' ') offset++;
          int start = offset;
          while (offset < trimmed.length() && trimmed.charAt(offset) != ' '
              && trimmed.charAt(offset) != '\t') offset++;
          language = trimmed.substring(start, offset).toLowerCase(Locale.ROOT);
        }
      } else {
        boolean close = marker && trimmed.charAt(0) == fence;
        if (close) {
          int offset = 0;
          while (offset < trimmed.length() && trimmed.charAt(offset) == fence) offset++;
          while (offset < trimmed.length() && trimmed.charAt(offset) == ' ') offset++;
          close = offset == trimmed.length();
        }
        if (close) {
          addBlock(blocks, language, body);
          inside = false;
          language = "";
        } else {
          body.append(line).append('\n');
        }
      }
      if (newline < 0) break;
      position = newline + 1;
    }
    if (inside) addBlock(blocks, language, body);
    return List.copyOf(blocks);
  }

  public static Optional<List<Block>> latestAssistantBlocks(
      List<com.github.skanga.ajent.domain.Message> messages) {
    for (int index = messages.size() - 1; index >= 0; index--) {
      var message = messages.get(index);
      if (message.role() != com.github.skanga.ajent.domain.Role.ASSISTANT
          || message.text().isEmpty()) continue;
      List<Block> blocks = extract(message.text());
      if (!blocks.isEmpty()) return Optional.of(blocks);
    }
    return Optional.empty();
  }

  public static Shell shell(String language, boolean windows) {
    String value = Objects.requireNonNull(language, "language");
    boolean posix = List.of("sh", "bash", "zsh", "shell", "console", "terminal", "posix",
        "shell-session", "shellsession").contains(value);
    boolean powershell = List.of("powershell", "pwsh", "ps", "ps1").contains(value);
    boolean cmd = List.of("cmd", "bat", "batch", "dos", "winbatch", "cmd.exe").contains(value);
    if (windows) {
      if (powershell) return Shell.POWERSHELL;
      if (cmd || posix || value.isEmpty()) return Shell.CMD;
      return Shell.NONE;
    }
    return posix || value.isEmpty() ? Shell.POSIX : Shell.NONE;
  }

  public static String commandFor(Shell shell, String body) {
    Objects.requireNonNull(shell, "shell");
    Objects.requireNonNull(body, "body");
    if (shell != Shell.POWERSHELL) return body;
    String encoded = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_16LE));
    return "powershell -NoProfile -ExecutionPolicy Bypass -EncodedCommand " + encoded;
  }

  public static State open(List<Block> blocks) {
    return blocks.isEmpty() ? new Closed() : new Open(blocks, 0);
  }

  public static State close(State ignored) { return new Closed(); }

  public static State move(State state, int delta) {
    if (state instanceof Open open && !open.blocks().isEmpty()) {
      int index = Math.max(0, Math.min(open.blocks().size() - 1, open.index() + delta));
      return new Open(open.blocks(), index);
    }
    if (state instanceof Result result) {
      return new Result(result.command(), result.output(), result.exitCode(), result.timedOut(),
          Math.max(0, result.scroll() + delta));
    }
    return state;
  }

  public static Optional<Block> selected(State state, int directIndex) {
    if (!(state instanceof Open open)) return Optional.empty();
    int index = directIndex >= 0 ? directIndex : open.index();
    return index < 0 || index >= open.blocks().size()
        ? Optional.empty() : Optional.of(open.blocks().get(index));
  }

  private static String stripUpToThreeSpaces(String line) {
    int offset = 0;
    while (offset < line.length() && offset < 3 && line.charAt(offset) == ' ') offset++;
    return line.substring(offset);
  }

  private static void addBlock(List<Block> blocks, String language, StringBuilder source) {
    while (!source.isEmpty() && (source.charAt(source.length() - 1) == '\n'
        || source.charAt(source.length() - 1) == '\r')) source.setLength(source.length() - 1);
    if (!source.isEmpty()) {
      String body = stripUniformPrompts(source.toString());
      blocks.add(new Block(language, body, 1 + (int) body.chars().filter(c -> c == '\n').count()));
    }
    source.setLength(0);
  }

  static String stripUniformPrompts(String body) {
    String[] lines = body.split("\n", -1);
    boolean any = false;
    for (String line : lines) {
      if (line.isEmpty()) continue;
      any = true;
      if (line.length() < 2 || (line.charAt(0) != '$' && line.charAt(0) != '>')
          || line.charAt(1) != ' ') return body;
    }
    if (!any) return body;
    var clean = new ArrayList<String>(lines.length);
    for (String line : lines) clean.add(line.isEmpty() ? line : line.substring(2));
    return String.join("\n", clean);
  }
}
