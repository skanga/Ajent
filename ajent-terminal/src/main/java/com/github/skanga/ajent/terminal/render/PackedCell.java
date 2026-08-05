package com.github.skanga.ajent.terminal.render;

/** Ajent's bit-identical 64-bit terminal cell representation. */
public record PackedCell(int character, int styleId, int hyperlinkId, int width) {
  public static final PackedCell BLANK = new PackedCell(' ', 0, 0, 0);

  public PackedCell {
    if (styleId < 0 || styleId > 0xffff) {
      throw new IllegalArgumentException("style id must fit unsigned 16 bits");
    }
    if (hyperlinkId < 0 || hyperlinkId > 0xff) {
      throw new IllegalArgumentException("hyperlink id must fit unsigned 8 bits");
    }
    if (width < 0 || width > 0xff) {
      throw new IllegalArgumentException("width marker must fit unsigned 8 bits");
    }
  }

  public long pack() {
    return Integer.toUnsignedLong(character)
        | ((long) styleId << 32)
        | ((long) hyperlinkId << 48)
        | ((long) width << 56);
  }

  public static PackedCell unpack(long packed) {
    return new PackedCell(
        (int) (packed & 0xffff_ffffL),
        (int) ((packed >>> 32) & 0xffff),
        (int) ((packed >>> 48) & 0xff),
        (int) ((packed >>> 56) & 0xff));
  }

  public boolean isWideLead() { return width == 1; }

  public boolean isWideTrail() { return width == 2; }
}
