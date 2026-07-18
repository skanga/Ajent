package com.github.skanga.ajent.terminal.render;

import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.ThematicBreak;
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

/** Incremental Markdown block parser mirroring AgenTTY's streaming block vocabulary. */
public final class StreamingMarkdown {
  private static final Parser PARSER = parser();

  private String source = "";
  private List<Block> blocks = List.of();
  private final TextReveal reveal = new TextReveal(90, 0.3, 0.2);
  private TextReveal.Frame revealFrame;
  private boolean live;
  private boolean revealEffects = true;
  private int rowFloor;
  private int floorWidth = -1;

  /** Native top-level block kinds exposed by Maya's StreamingMarkdown. */
  public enum BlockKind {
    PARAGRAPH,
    HEADING,
    CODE_BLOCK,
    BLOCKQUOTE,
    LIST,
    HORIZONTAL_RULE,
    TABLE,
    OTHER
  }

  /** A parsed block with UTF-16 source extents; {@code sourceEnd} is exclusive. */
  public record Block(BlockKind kind, int sourceStart, int sourceEnd, String text) {
    public Block {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(text, "text");
      if (sourceStart < 0 || sourceEnd < sourceStart) {
        throw new IllegalArgumentException("invalid Markdown source extent");
      }
    }
  }

  public void setContent(String content) {
    Objects.requireNonNull(content, "content");
    if (!content.startsWith(source)) resetVisualState();
    source = content;
    blocks = parse(source);
  }

  public void append(String delta) {
    setContent(source + Objects.requireNonNull(delta, "delta"));
  }

  public void setLive(boolean streaming) {
    live = streaming;
  }

  public boolean isLive() {
    return live;
  }

  public void setRevealEffects(boolean enabled) {
    if (revealEffects != enabled) resetVisualState();
    revealEffects = enabled;
  }

  public void finish() {
    live = false;
    blocks = parse(source);
  }

  public String content() {
    return source;
  }

  public List<Block> blocks() {
    return blocks;
  }

  /** Builds a production frame while preserving the largest live height at this width. */
  public List<MarkdownTerminalRenderer.Line> render(int width, long nowNanos) {
    if (width <= 0) throw new IllegalArgumentException("Markdown width must be positive");
    revealFrame = revealFrame == null
        ? reveal.begin(source, live && revealEffects, nowNanos)
        : reveal.update(source, live && revealEffects, nowNanos);
    List<MarkdownTerminalRenderer.Line> rendered =
        MarkdownTerminalRenderer.render(revealFrame, width);
    if (width != floorWidth) {
      floorWidth = width;
      rowFloor = 0;
    }
    if (revealFrame.requiresAnimation()) {
      rowFloor = Math.max(rowFloor, rendered.size());
      if (rendered.size() < rowFloor) {
        var padded = new ArrayList<>(rendered);
        while (padded.size() < rowFloor) {
          padded.add(new MarkdownTerminalRenderer.Line(List.of()));
        }
        rendered = List.copyOf(padded);
      }
    } else {
      rowFloor = rendered.size();
    }
    return rendered;
  }

  public boolean requiresAnimation() {
    return live || revealFrame != null && revealFrame.requiresAnimation();
  }

  public boolean settled() {
    return !live && (revealFrame == null || !revealFrame.requiresAnimation());
  }

  private void resetVisualState() {
    revealFrame = null;
    rowFloor = 0;
    floorWidth = -1;
  }

  private static Parser parser() {
    var options = new MutableDataSet();
    options.set(Parser.EXTENSIONS, List.of(
        StrikethroughExtension.create(), TaskListExtension.create(), TablesExtension.create()));
    return Parser.builder(options).build();
  }

  private static List<Block> parse(String markdown) {
    Node document = PARSER.parse(markdown);
    var result = new ArrayList<Block>();
    for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
      int start = node.getChars().getStartOffset();
      int end = node.getChars().getEndOffset();
      String text = markdown.substring(start, end);
      result.add(new Block(kind(node, text), start, end, text));
    }
    return List.copyOf(result);
  }

  private static BlockKind kind(Node node, String source) {
    if (node instanceof Paragraph) return BlockKind.PARAGRAPH;
    if (node instanceof Heading) return BlockKind.HEADING;
    if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) {
      return BlockKind.CODE_BLOCK;
    }
    if (node instanceof BlockQuote) return BlockKind.BLOCKQUOTE;
    if (node instanceof BulletList || node instanceof OrderedList) return BlockKind.LIST;
    if (node instanceof ThematicBreak) return BlockKind.HORIZONTAL_RULE;
    if (node instanceof TableBlock) {
      return isNativeTable(source) ? BlockKind.TABLE : BlockKind.PARAGRAPH;
    }
    return BlockKind.OTHER;
  }

  static boolean isNativeTable(String source) {
    List<String> rows = source.lines().filter(line -> !line.isBlank()).toList();
    if (rows.size() < 2 || !rows.getFirst().strip().startsWith("|")) return false;
    List<String> header = tableCells(rows.getFirst());
    List<String> delimiter = tableCells(rows.get(1));
    return !header.isEmpty() && header.size() == delimiter.size()
        && delimiter.stream().allMatch(cell -> cell.matches(":?-{3,}:?"));
  }

  private static List<String> tableCells(String line) {
    String row = line.strip();
    if (row.startsWith("|")) row = row.substring(1);
    if (row.endsWith("|")) row = row.substring(0, row.length() - 1);
    return java.util.regex.Pattern.compile("(?<!\\\\)\\|").splitAsStream(row)
        .map(String::strip).toList();
  }
}
