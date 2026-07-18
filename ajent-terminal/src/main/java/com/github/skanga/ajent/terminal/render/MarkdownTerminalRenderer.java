package com.github.skanga.ajent.terminal.render;

import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.gfm.strikethrough.Strikethrough;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** CommonMark/GFM-to-terminal renderer used by live and settled assistant turns. */
public final class MarkdownTerminalRenderer {
  private static final TerminalStyle HEADING = TerminalStyle.EMPTY
      .withForeground(TerminalColor.cyan()).withBold();
  private static final TerminalStyle CODE = TerminalStyle.EMPTY
      .withForeground(TerminalColor.cyan());
  private static final TerminalStyle MUTED = TerminalStyle.EMPTY
      .withForeground(TerminalColor.brightBlack());
  private static final TerminalStyle GHOST = MUTED.withDim();
  private static final TerminalStyle SWEEP_HEAD = TerminalStyle.EMPTY
      .withForeground(TerminalColor.rgb(255, 90, 200)).withBold();
  private static final Parser PARSER = parser();

  /** One styled terminal span. */
  public record Span(String text, TerminalStyle style) {
    public Span {
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(style, "style");
    }
  }

  /** One terminal row with adjacent compatible spans already coalesced. */
  public record Line(List<Span> spans) {
    public Line {
      spans = List.copyOf(Objects.requireNonNull(spans, "spans"));
    }

    public String text() {
      var result = new StringBuilder();
      spans.forEach(span -> result.append(span.text()));
      return result.toString();
    }
  }

  private MarkdownTerminalRenderer() {}

  public static List<Line> render(String markdown, int width) {
    Objects.requireNonNull(markdown, "markdown");
    if (width <= 0) throw new IllegalArgumentException("Markdown width must be positive");
    Node document = PARSER.parse(markdown);
    var output = new ArrayList<Line>();
    for (Node block = document.getFirstChild(); block != null; block = block.getNext()) {
      renderBlock(block, width, output);
    }
    return List.copyOf(output);
  }

  /** Renders a rate-paced frame without exposing Markdown punctuation or changing row shape. */
  public static List<Line> render(TextReveal.Frame frame, int width) {
    Objects.requireNonNull(frame, "frame");
    List<Line> rendered = render(frame.source(), width);
    if (!frame.live() && !frame.animating()) return rendered;
    int content = rendered.stream().mapToInt(MarkdownTerminalRenderer::maskableCount).sum();
    double fraction = frame.totalCodePoints() == 0 ? 1.0
        : (double) frame.revealedCodePoints() / frame.totalCodePoints();
    int visible = Math.clamp((int) Math.round(content * fraction), 0, content);
    var masked = new ArrayList<Line>(rendered.size());
    int[] position = {0};
    for (Line line : rendered) masked.add(mask(line, visible, position));
    return List.copyOf(masked);
  }

  private static int maskableCount(Line line) {
    int count = 0;
    for (Span span : line.spans()) {
      for (int offset = 0; offset < span.text().length();) {
        int codePoint = span.text().codePointAt(offset);
        if (maskable(codePoint)) count++;
        offset += Character.charCount(codePoint);
      }
    }
    return count;
  }

  private static Line mask(Line line, int visible, int[] position) {
    var spans = new ArrayList<Span>();
    for (Span span : line.spans()) {
      for (int offset = 0; offset < span.text().length();) {
        int codePoint = span.text().codePointAt(offset);
        offset += Character.charCount(codePoint);
        String glyph = new String(Character.toChars(codePoint));
        if (!maskable(codePoint)) {
          add(spans, glyph, span.style());
          continue;
        }
        int index = position[0]++;
        if (index < visible) {
          add(spans, glyph, span.style());
        } else if (index == visible) {
          add(spans, glyph, span.style().merge(SWEEP_HEAD));
        } else {
          add(spans, " ".repeat(Math.max(1, UnicodeWidth.of(codePoint))), GHOST);
        }
      }
    }
    return new Line(coalesce(spans));
  }

  private static boolean maskable(int codePoint) {
    return !Character.isWhitespace(codePoint) && (codePoint < 0x2500 || codePoint > 0x259f);
  }

  private static Parser parser() {
    var options = new MutableDataSet();
    options.set(Parser.EXTENSIONS, List.of(
        StrikethroughExtension.create(), TaskListExtension.create(), TablesExtension.create()));
    return Parser.builder(options).build();
  }

  private static void renderBlock(Node block, int width, List<Line> output) {
    if (block instanceof Heading) {
      output.addAll(wrap(inline(block, HEADING), width, ""));
    } else if (block instanceof Paragraph) {
      output.addAll(wrap(inline(block, TerminalStyle.EMPTY), width, ""));
    } else if (block instanceof FencedCodeBlock fenced) {
      renderCode(fenced.getContentChars().toString(), width, output);
    } else if (block instanceof IndentedCodeBlock indented) {
      renderCode(indented.getContentChars().toString(), width, output);
    } else if (block instanceof BlockQuote) {
      renderQuote(block, width, output);
    } else if (block instanceof BulletList || block instanceof OrderedList) {
      renderList(block, width, output);
    } else if (block instanceof ThematicBreak) {
      output.add(line("─".repeat(width), MUTED));
    } else if (block instanceof TableBlock) {
      if (StreamingMarkdown.isNativeTable(block.getChars().toString())) {
        renderTable(block.getChars().toString(), width, output);
      } else {
        output.addAll(wrap(List.of(new Span(block.getChars().toString()
            .replace('\n', ' ').strip(), TerminalStyle.EMPTY)), width, ""));
      }
    } else {
      for (Node child = block.getFirstChild(); child != null; child = child.getNext()) {
        renderBlock(child, width, output);
      }
    }
  }

  private static void renderQuote(Node quote, int width, List<Line> output) {
    var nested = new ArrayList<Line>();
    for (Node child = quote.getFirstChild(); child != null; child = child.getNext()) {
      renderBlock(child, Math.max(1, width - 2), nested);
    }
    for (Line line : nested) output.add(prefixed("│ ", MUTED, line));
  }

  private static void renderList(Node list, int width, List<Line> output) {
    int ordinal = orderedStart(list);
    for (Node child = list.getFirstChild(); child != null; child = child.getNext()) {
      if (!(child instanceof ListItem)) continue;
      String raw = child.getChars().toString().stripLeading();
      String prefix;
      if (raw.matches("(?is)^[-+*]\\s+\\[[xX]\\].*")) prefix = "  [x] ";
      else if (raw.matches("(?is)^[-+*]\\s+\\[ \\].*")) prefix = "  [ ] ";
      else if (list instanceof OrderedList) prefix = "  " + ordinal++ + ". ";
      else prefix = "  • ";
      Node paragraph = firstParagraph(child);
      List<Span> spans = paragraph == null
          ? List.of(new Span(stripListMarker(raw), TerminalStyle.EMPTY))
          : inline(paragraph, TerminalStyle.EMPTY);
      if (prefix.contains("[")) spans = stripTaskMarker(spans);
      List<Line> wrapped = wrap(spans, Math.max(1, width - prefix.length()), "");
      if (wrapped.isEmpty()) output.add(line(prefix.stripTrailing(), TerminalStyle.EMPTY));
      for (int index = 0; index < wrapped.size(); index++) {
        output.add(prefixed(index == 0 ? prefix : " ".repeat(prefix.length()),
            MUTED, wrapped.get(index)));
      }
    }
  }

  private static int orderedStart(Node list) {
    String raw = list.getChars().toString().stripLeading();
    int delimiter = Math.max(raw.indexOf('.'), raw.indexOf(')'));
    if (delimiter <= 0) return 1;
    try {
      return Integer.parseInt(raw.substring(0, delimiter));
    } catch (NumberFormatException ignored) {
      return 1;
    }
  }

  private static Node firstParagraph(Node item) {
    for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
      if (child instanceof Paragraph) return child;
    }
    return null;
  }

  private static String stripListMarker(String value) {
    return value.replaceFirst("^(?:[-+*]|\\d{1,9}[.)])\\s+", "").stripTrailing();
  }

  private static List<Span> stripTaskMarker(List<Span> spans) {
    if (spans.isEmpty()) return spans;
    var result = new ArrayList<>(spans);
    Span first = result.getFirst();
    String text = first.text().replaceFirst("^\\[[ xX]\\]\\s*", "");
    if (text.isEmpty()) result.removeFirst();
    else result.set(0, new Span(text, first.style()));
    return List.copyOf(result);
  }

  private static void renderCode(String source, int width, List<Line> output) {
    String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
    String[] lines = normalized.split("\n", -1);
    int count = lines.length > 0 && lines[lines.length - 1].isEmpty()
        ? lines.length - 1 : lines.length;
    for (int index = 0; index < count; index++) {
      String value = lines[index];
      if (value.isEmpty()) output.add(line("", CODE));
      else output.addAll(wrap(List.of(new Span(value, CODE)), width, ""));
    }
  }

  private static List<Span> inline(Node parent, TerminalStyle base) {
    var result = new ArrayList<Span>();
    appendInlineChildren(parent, base, result);
    return coalesce(result);
  }

  private static void appendInlineChildren(Node parent, TerminalStyle style, List<Span> output) {
    for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
      appendInline(node, style, output);
    }
  }

  private static void appendInline(Node node, TerminalStyle style, List<Span> output) {
    if (node instanceof Text text) {
      add(output, text.getChars().toString(), style);
    } else if (node instanceof Code code) {
      add(output, code.getText().toString(), style.merge(CODE));
    } else if (node instanceof StrongEmphasis) {
      appendInlineChildren(node, style.withBold(), output);
    } else if (node instanceof Emphasis) {
      appendInlineChildren(node, style.withItalic(), output);
    } else if (node instanceof Strikethrough) {
      appendInlineChildren(node, style.withStrikethrough(), output);
    } else if (node instanceof Link) {
      appendInlineChildren(node, style.withUnderline(), output);
    } else if (node instanceof SoftLineBreak) {
      add(output, " ", style);
    } else if (node instanceof HardLineBreak) {
      add(output, "\n", style);
    } else {
      appendInlineChildren(node, style, output);
    }
  }

  private static List<Line> wrap(List<Span> source, int width, String firstPrefix) {
    var lines = new ArrayList<Line>();
    var current = new ArrayList<Span>();
    int columns = 0;
    if (!firstPrefix.isEmpty()) {
      add(current, firstPrefix, MUTED);
      columns = UnicodeWidth.stringWidth(firstPrefix, UnicodeWidth.Mode.MODERN);
    }
    for (Span span : source) {
      StringBuilder token = new StringBuilder();
      for (int offset = 0; offset < span.text().length();) {
        int codePoint = span.text().codePointAt(offset);
        offset += Character.charCount(codePoint);
        if (codePoint == '\n') {
          flushToken(token, span.style(), width, lines, current, columns);
          columns = widthOf(current);
          lines.add(new Line(coalesce(current)));
          current = new ArrayList<>();
          columns = 0;
        } else if (Character.isWhitespace(codePoint)) {
          columns = flushToken(token, span.style(), width, lines, current, columns);
          if (columns > 0) {
            if (columns + 1 > width) {
              lines.add(new Line(coalesce(current)));
              current = new ArrayList<>();
              columns = 0;
            } else {
              add(current, " ", span.style());
              columns++;
            }
          }
        } else {
          token.appendCodePoint(codePoint);
        }
      }
      columns = flushToken(token, span.style(), width, lines, current, columns);
    }
    trimTrailingSpace(current);
    if (!current.isEmpty() || lines.isEmpty()) lines.add(new Line(coalesce(current)));
    return lines;
  }

  private static int flushToken(StringBuilder token, TerminalStyle style, int width,
      List<Line> lines, List<Span> current, int columns) {
    int tokenWidth = UnicodeWidth.stringWidth(token.toString(), UnicodeWidth.Mode.MODERN);
    if (columns > 0 && columns + tokenWidth > width && tokenWidth <= width) {
      trimTrailingSpace(current);
      lines.add(new Line(coalesce(current)));
      current.clear();
      columns = 0;
    }
    for (int offset = 0; offset < token.length();) {
      int codePoint = token.codePointAt(offset);
      int count = Character.charCount(codePoint);
      int glyphWidth = UnicodeWidth.of(codePoint);
      if (columns > 0 && columns + glyphWidth > width) {
        trimTrailingSpace(current);
        lines.add(new Line(coalesce(current)));
        current.clear();
        columns = 0;
      }
      add(current, token.substring(offset, offset + count), style);
      columns += glyphWidth;
      offset += count;
    }
    token.setLength(0);
    return columns;
  }

  private static int widthOf(List<Span> spans) {
    int width = 0;
    for (Span span : spans) {
      width += UnicodeWidth.stringWidth(span.text(), UnicodeWidth.Mode.MODERN);
    }
    return width;
  }

  private static void trimTrailingSpace(List<Span> spans) {
    if (spans.isEmpty()) return;
    Span last = spans.getLast();
    String stripped = last.text().stripTrailing();
    if (stripped.isEmpty()) spans.removeLast();
    else if (!stripped.equals(last.text())) spans.set(spans.size() - 1,
        new Span(stripped, last.style()));
  }

  private static void renderTable(String source, int width, List<Line> output) {
    List<List<String>> rows = source.lines().filter(line -> !line.isBlank())
        .map(MarkdownTerminalRenderer::tableCells).toList();
    if (rows.size() < 2) return;
    var data = new ArrayList<List<String>>();
    data.add(rows.getFirst());
    data.addAll(rows.subList(2, rows.size()));
    int columns = data.stream().mapToInt(List::size).max().orElse(1);
    int available = Math.max(columns, width - columns * 3 - 1);
    int perColumn = Math.max(1, available / columns);
    int[] widths = new int[columns];
    for (List<String> row : data) {
      for (int column = 0; column < row.size(); column++) {
        widths[column] = Math.max(widths[column], Math.min(perColumn,
            UnicodeWidth.stringWidth(row.get(column), UnicodeWidth.Mode.MODERN)));
      }
    }
    for (int column = 0; column < widths.length; column++) widths[column] = Math.max(1, widths[column]);
    output.add(line(border(widths, '┌', '┬', '┐'), MUTED));
    output.add(line(tableRow(data.getFirst(), widths), TerminalStyle.EMPTY));
    output.add(line(border(widths, '├', '┼', '┤'), MUTED));
    for (int row = 1; row < data.size(); row++) {
      output.add(line(tableRow(data.get(row), widths), TerminalStyle.EMPTY));
    }
    output.add(line(border(widths, '└', '┴', '┘'), MUTED));
  }

  private static List<String> tableCells(String line) {
    String row = line.strip();
    if (row.startsWith("|")) row = row.substring(1);
    if (row.endsWith("|")) row = row.substring(0, row.length() - 1);
    return java.util.regex.Pattern.compile("(?<!\\\\)\\|").splitAsStream(row)
        .map(String::strip).toList();
  }

  private static String border(int[] widths, char left, char join, char right) {
    var value = new StringBuilder().append(left);
    for (int column = 0; column < widths.length; column++) {
      if (column > 0) value.append(join);
      value.append("─".repeat(widths[column] + 2));
    }
    return value.append(right).toString();
  }

  private static String tableRow(List<String> cells, int[] widths) {
    var value = new StringBuilder("│");
    for (int column = 0; column < widths.length; column++) {
      String cell = column < cells.size() ? cells.get(column) : "";
      cell = clip(cell, widths[column]);
      int padding = widths[column]
          - UnicodeWidth.stringWidth(cell, UnicodeWidth.Mode.MODERN);
      value.append(' ').append(cell).append(" ".repeat(padding)).append(" │");
    }
    return value.toString();
  }

  private static String clip(String value, int width) {
    var result = new StringBuilder();
    int columns = 0;
    for (int offset = 0; offset < value.length();) {
      int codePoint = value.codePointAt(offset);
      int glyphWidth = UnicodeWidth.of(codePoint);
      if (columns + glyphWidth > width) break;
      result.appendCodePoint(codePoint);
      columns += glyphWidth;
      offset += Character.charCount(codePoint);
    }
    return result.toString();
  }

  private static Line prefixed(String prefix, TerminalStyle style, Line line) {
    var spans = new ArrayList<Span>();
    add(spans, prefix, style);
    spans.addAll(line.spans());
    return new Line(coalesce(spans));
  }

  private static Line line(String text, TerminalStyle style) {
    return new Line(text.isEmpty() ? List.of() : List.of(new Span(text, style)));
  }

  private static void add(List<Span> spans, String text, TerminalStyle style) {
    if (text.isEmpty()) return;
    if (!spans.isEmpty() && spans.getLast().style().equals(style)) {
      Span previous = spans.removeLast();
      spans.add(new Span(previous.text() + text, style));
    } else {
      spans.add(new Span(text, style));
    }
  }

  private static List<Span> coalesce(List<Span> spans) {
    var result = new ArrayList<Span>();
    spans.forEach(span -> add(result, span.text(), span.style()));
    return List.copyOf(result);
  }
}
