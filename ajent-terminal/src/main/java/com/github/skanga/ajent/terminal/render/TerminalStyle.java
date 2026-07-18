package com.github.skanga.ajent.terminal.render;

/** Immutable composable terminal text style. Null colors mean inherited/default state. */
public record TerminalStyle(
    TerminalColor foreground,
    TerminalColor background,
    boolean bold,
    boolean dim,
    boolean italic,
    boolean underline,
    boolean strikethrough,
    boolean inverse) {

  public static final TerminalStyle EMPTY = new TerminalStyle(null, null, false, false,
      false, false, false, false);

  public TerminalStyle withForeground(TerminalColor color) {
    return new TerminalStyle(java.util.Objects.requireNonNull(color), background, bold, dim,
        italic, underline, strikethrough, inverse);
  }

  public TerminalStyle withBackground(TerminalColor color) {
    return new TerminalStyle(foreground, java.util.Objects.requireNonNull(color), bold, dim,
        italic, underline, strikethrough, inverse);
  }

  public TerminalStyle withBold() { return withAttributes(true, dim, italic, underline, strikethrough, inverse); }
  public TerminalStyle withDim() { return withAttributes(bold, true, italic, underline, strikethrough, inverse); }
  public TerminalStyle withItalic() { return withAttributes(bold, dim, true, underline, strikethrough, inverse); }
  public TerminalStyle withUnderline() { return withAttributes(bold, dim, italic, true, strikethrough, inverse); }
  public TerminalStyle withStrikethrough() { return withAttributes(bold, dim, italic, underline, true, inverse); }
  public TerminalStyle withInverse() { return withAttributes(bold, dim, italic, underline, strikethrough, true); }

  public TerminalStyle merge(TerminalStyle overlay) {
    java.util.Objects.requireNonNull(overlay, "overlay");
    return new TerminalStyle(
        overlay.foreground != null ? overlay.foreground : foreground,
        overlay.background != null ? overlay.background : background,
        bold || overlay.bold, dim || overlay.dim, italic || overlay.italic,
        underline || overlay.underline, strikethrough || overlay.strikethrough,
        inverse || overlay.inverse);
  }

  public boolean isEmpty() { return equals(EMPTY); }

  /** Public style-builder form; renderer pools use reset-prefixed cached SGR instead. */
  public String toSgr() {
    var parameters = new java.util.ArrayList<String>(8);
    if (bold) parameters.add("1");
    if (dim) parameters.add("2");
    if (italic) parameters.add("3");
    if (underline) parameters.add("4");
    if (inverse) parameters.add("7");
    if (strikethrough) parameters.add("9");
    if (foreground != null) parameters.add(foreground.foregroundSgr());
    if (background != null) parameters.add(background.backgroundSgr());
    return parameters.isEmpty() ? "" : "\u001b[" + String.join(";", parameters) + 'm';
  }

  private TerminalStyle withAttributes(boolean bold, boolean dim, boolean italic,
      boolean underline, boolean strikethrough, boolean inverse) {
    return new TerminalStyle(foreground, background, bold, dim, italic, underline,
        strikethrough, inverse);
  }
}
