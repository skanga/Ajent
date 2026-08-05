package com.github.skanga.ajent.terminal.render;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/** Mutable packed-cell canvas preserving Ajent's clipping and content-extent invariants. */
public final class TerminalCanvas {
  public enum Stage { DRAINED, PAINTED }

  public record Rect(int x, int y, int width, int height) {
    public Rect {
      if (width < 0 || height < 0) throw new IllegalArgumentException("negative rectangle size");
    }

    public boolean isEmpty() { return width == 0 || height == 0; }

    public Rect intersect(Rect other) {
      int left = Math.max(x, other.x), top = Math.max(y, other.y);
      int right = Math.min(x + width, other.x + other.width);
      int bottom = Math.min(y + height, other.y + other.height);
      return new Rect(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }

    public Rect unite(Rect other) {
      if (isEmpty()) return other;
      if (other.isEmpty()) return this;
      int left = Math.min(x, other.x), top = Math.min(y, other.y);
      int right = Math.max(x + width, other.x + other.width);
      int bottom = Math.max(y + height, other.y + other.height);
      return new Rect(left, top, right - left, bottom - top);
    }
  }

  public final class ClipScope implements AutoCloseable {
    private boolean open = true;

    private ClipScope(Rect clip) { pushClip(clip); }

    @Override public void close() {
      if (open) {
        popClip();
        open = false;
      }
    }
  }

  private int width;
  private int height;
  private long[] cells;
  private int[] lastColumn;
  private long[] rowEpoch;
  private long epoch;
  private int maxRow = -1;
  private Rect damage;
  private final Deque<Rect> clips = new ArrayDeque<>();
  private Stage stage = Stage.DRAINED;

  public TerminalCanvas(int width, int height) {
    requireDimensions(width, height);
    this.width = width;
    this.height = height;
    cells = new long[Math.multiplyExact(width, height)];
    Arrays.fill(cells, PackedCell.BLANK.pack());
    lastColumn = new int[height];
    Arrays.fill(lastColumn, -1);
    rowEpoch = new long[height];
    damage = fullRect();
  }

  public int width() { return width; }
  public int height() { return height; }
  public int cellCount() { return cells.length; }
  public Stage stage() { return stage; }
  public int maxContentRow() { return maxRow; }
  public Rect damage() { return damage; }
  public int clipDepth() { return clips.size(); }

  public long rowEpoch(int row) { return inRowBounds(row) ? rowEpoch[row] : 0; }

  public int lastContentColumn(int row) { return inRowBounds(row) ? lastColumn[row] : -1; }

  public PackedCell get(int x, int y) {
    return inBounds(x, y) ? PackedCell.unpack(cells[index(x, y)]) : PackedCell.BLANK;
  }

  public long getPacked(int x, int y) { return cells[index(x, y)]; }

  public long[] packedCells() { return cells.clone(); }

  public void set(int x, int y, int character, int styleId) {
    set(x, y, character, styleId, 0);
  }

  public void set(int x, int y, int character, int styleId, int cellWidth) {
    if (!inBounds(x, y) || isClipped(x, y)) return;
    if (cellWidth == 1 && (!inBounds(x + 1, y) || isClipped(x + 1, y))) return;
    if (cellWidth == 2 && (!inBounds(x - 1, y) || isClipped(x - 1, y))) return;
    long packed = new PackedCell(character, styleId, 0, cellWidth).pack();
    int index = index(x, y);
    if (cells[index] != packed) {
      cells[index] = packed;
      stamp(y);
    }
    if (character != ' ' || styleId != 0) {
      maxRow = Math.max(maxRow, y);
      lastColumn[y] = Math.max(lastColumn[y], cellWidth == 1 ? x + 1 : x);
    }
    stage = Stage.PAINTED;
  }

  public void writeText(int x, int y, String text, int styleId) {
    java.util.Objects.requireNonNull(text, "text");
    if (!inBounds(x, y)) return;
    int column = x;
    for (var iterator = text.codePoints().iterator(); iterator.hasNext();) {
      int codePoint = iterator.nextInt();
      if (codePoint < 0x20) continue;
      if (UnicodeWidth.of(codePoint) == 2) {
        set(column, y, codePoint, styleId, 1);
        set(column + 1, y, ' ', styleId, 2);
        column += 2;
      } else {
        set(column++, y, codePoint, styleId);
      }
    }
  }

  public void fill(Rect region, int character, int styleId) {
    java.util.Objects.requireNonNull(region, "region");
    Rect clipped = region.intersect(fullRect());
    if (!clips.isEmpty()) clipped = clipped.intersect(clips.peek());
    if (clipped.isEmpty()) return;
    long packed = new PackedCell(character, styleId, 0, 0).pack();
    for (int y = clipped.y; y < clipped.y + clipped.height; y++) {
      int start = index(clipped.x, y), end = start + clipped.width;
      boolean changed = false;
      for (int offset = start; offset < end; offset++) {
        if (cells[offset] != packed) {
          cells[offset] = packed;
          changed = true;
        }
      }
      if (changed) stamp(y);
      if (character != ' ' || styleId != 0) {
        maxRow = Math.max(maxRow, y);
        lastColumn[y] = Math.max(lastColumn[y], clipped.x + clipped.width - 1);
      }
    }
    stage = Stage.PAINTED;
  }

  public void blitPackedRow(int x, int y, long[] source, boolean rowHasContent) {
    java.util.Objects.requireNonNull(source, "source");
    if (!inRowBounds(y) || source.length == 0) return;
    int left = Math.max(0, x), right = Math.min(width, x + source.length);
    if (!clips.isEmpty()) {
      Rect clip = clips.peek();
      if (y < clip.y || y >= clip.y + clip.height) return;
      left = Math.max(left, clip.x);
      right = Math.min(right, clip.x + clip.width);
    }
    if (right <= left) return;
    int destination = index(left, y), sourceOffset = left - x, count = right - left;
    boolean changed = false;
    for (int index = 0; index < count; index++) {
      long value = source[sourceOffset + index];
      if (cells[destination + index] != value) {
        cells[destination + index] = value;
        changed = true;
      }
    }
    if (PackedCell.unpack(cells[destination]).isWideTrail()) {
      cells[destination] = PackedCell.BLANK.pack();
      changed = true;
    }
    int last = destination + count - 1;
    if (PackedCell.unpack(cells[last]).isWideLead()) {
      cells[last] = PackedCell.BLANK.pack();
      changed = true;
    }
    if (changed) stamp(y);
    if (rowHasContent) {
      for (int column = right - 1; column >= left; column--) {
        if (cells[index(column, y)] != PackedCell.BLANK.pack()) {
          maxRow = Math.max(maxRow, y);
          lastColumn[y] = Math.max(lastColumn[y], column);
          break;
        }
      }
    }
    stage = Stage.PAINTED;
  }

  public void blitPackedRow(int x, int y, long[] source, boolean rowHasContent,
      int knownLastColumn) {
    blitPackedRow(x, y, source, rowHasContent);
  }

  /** Returns true when the packed destination was already byte-identical. */
  public boolean blitPackedRowCached(int x, int y, long[] source, boolean rowHasContent,
      int knownLastColumn) {
    java.util.Objects.requireNonNull(source, "source");
    int left = Math.max(0, x), right = Math.min(width, x + source.length);
    if (!inRowBounds(y) || source.length == 0) return false;
    if (!clips.isEmpty()) {
      Rect clip = clips.peek();
      if (y < clip.y || y >= clip.y + clip.height) return false;
      left = Math.max(left, clip.x);
      right = Math.min(right, clip.x + clip.width);
    }
    if (right <= left) return false;
    boolean identical = true;
    for (int column = left; column < right; column++) {
      if (cells[index(column, y)] != source[column - x]) {
        identical = false;
        break;
      }
    }
    blitPackedRow(x, y, source, rowHasContent, knownLastColumn);
    return identical;
  }

  public void clear() {
    Arrays.fill(cells, PackedCell.BLANK.pack());
    Arrays.fill(lastColumn, -1);
    for (int row = 0; row < height; row++) stamp(row);
    maxRow = -1;
    damage = fullRect();
    stage = Stage.DRAINED;
  }

  public void clearRows(int count) {
    int rows = Math.clamp(count, 0, height);
    Arrays.fill(cells, 0, rows * width, PackedCell.BLANK.pack());
    Arrays.fill(lastColumn, 0, rows, -1);
    for (int row = 0; row < rows; row++) stamp(row);
    rescanMaxRow();
    damage = new Rect(0, 0, width, rows);
    stage = Stage.DRAINED;
  }

  public void clearBelow(int keepTop) { clearBelow(keepTop, Integer.MAX_VALUE); }

  public void clearBelow(int keepTop, int clearBottom) {
    if (keepTop <= 0 && clearBottom >= height) {
      clear();
      return;
    }
    if (keepTop >= height) return;
    keepTop = Math.max(0, keepTop);
    int bottom = Math.min(height, Math.max(0, clearBottom));
    if (bottom > keepTop) {
      Arrays.fill(cells, keepTop * width, bottom * width, PackedCell.BLANK.pack());
      Arrays.fill(lastColumn, keepTop, bottom, -1);
      for (int row = keepTop; row < bottom; row++) stamp(row);
    }
    maxRow = -1;
    for (int row = keepTop - 1; row >= 0; row--) {
      if (lastColumn[row] >= 0) {
        maxRow = row;
        break;
      }
    }
    damage = new Rect(0, keepTop, width, Math.max(0, bottom - keepTop));
    stage = Stage.DRAINED;
  }

  public void clearRow(int row) {
    if (!inRowBounds(row)) return;
    Arrays.fill(cells, row * width, (row + 1) * width, PackedCell.BLANK.pack());
    lastColumn[row] = -1;
    stamp(row);
    if (row == maxRow) rescanMaxRow();
  }

  public void resize(int width, int height) {
    requireDimensions(width, height);
    this.width = width;
    this.height = height;
    cells = new long[Math.multiplyExact(width, height)];
    Arrays.fill(cells, PackedCell.BLANK.pack());
    lastColumn = new int[height];
    Arrays.fill(lastColumn, -1);
    rowEpoch = new long[height];
    for (int row = 0; row < height; row++) stamp(row);
    maxRow = -1;
    clips.clear();
    damage = fullRect();
    stage = Stage.DRAINED;
  }

  public void pushClip(Rect clip) {
    java.util.Objects.requireNonNull(clip, "clip");
    clips.push(clips.isEmpty() ? clip : clips.peek().intersect(clip));
  }

  public void popClip() { if (!clips.isEmpty()) clips.pop(); }

  public void resetClips() { clips.clear(); }

  public ClipScope clipScope(Rect clip) { return new ClipScope(clip); }

  public boolean isClipped(int x, int y) {
    if (clips.isEmpty()) return false;
    Rect clip = clips.peek();
    return x < clip.x || x >= clip.x + clip.width || y < clip.y || y >= clip.y + clip.height;
  }

  public void resetDamage() { damage = new Rect(0, 0, 0, 0); }

  public void markAllDamaged() { damage = fullRect(); }

  public void markDamage(Rect region) { damage = damage.unite(region); }

  private void stamp(int row) { rowEpoch[row] = ++epoch; }

  private void rescanMaxRow() {
    maxRow = -1;
    for (int row = height - 1; row >= 0; row--) {
      if (lastColumn[row] >= 0) {
        maxRow = row;
        break;
      }
    }
  }

  private boolean inBounds(int x, int y) { return x >= 0 && x < width && inRowBounds(y); }
  private boolean inRowBounds(int y) { return y >= 0 && y < height; }
  private int index(int x, int y) { return y * width + x; }
  private Rect fullRect() { return new Rect(0, 0, width, height); }

  private static void requireDimensions(int width, int height) {
    if (width < 0 || height < 0) throw new IllegalArgumentException("negative canvas size");
  }
}
