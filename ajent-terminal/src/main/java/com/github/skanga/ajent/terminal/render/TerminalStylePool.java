package com.github.skanga.ajent.terminal.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Compact style interning and exact renderer-facing SGR serialization. */
public final class TerminalStylePool {
  public static final int MAX_STYLES = 65_535;
  public static final int UNKNOWN_STYLE = 0xffff;
  private static final AtomicLong NEXT_POOL_ID = new AtomicLong(1);

  private final int colorLevel;
  private final List<TerminalStyle> styles = new ArrayList<>();
  private final List<String> sgrCache = new ArrayList<>();
  private final Map<TerminalStyle, Integer> ids = new HashMap<>();
  private volatile long poolId;
  private volatile boolean overflowed;

  public TerminalStylePool() { this(3); }

  public TerminalStylePool(int colorLevel) {
    if (colorLevel < 1 || colorLevel > 3) throw new IllegalArgumentException("color level must be 1..3");
    this.colorLevel = colorLevel;
    poolId = NEXT_POOL_ID.getAndIncrement();
    add(TerminalStyle.EMPTY);
  }

  public int intern(TerminalStyle style) {
    java.util.Objects.requireNonNull(style, "style");
    Integer existing = ids.get(style);
    if (existing != null) return existing;
    if (styles.size() >= MAX_STYLES) {
      overflowed = true;
      return 0;
    }
    return add(style);
  }

  public TerminalStyle get(int id) { return styles.get(id); }

  public String sgr(int id) { return sgrCache.get(validId(id)); }

  public void appendTransition(int previousId, int newId, StringBuilder output) {
    java.util.Objects.requireNonNull(output, "output");
    if (previousId == newId) return;
    newId = validId(newId);
    if (previousId == UNKNOWN_STYLE) {
      output.append(sgrCache.get(newId));
      return;
    }
    previousId = validId(previousId);
    var from = styles.get(previousId);
    var to = styles.get(newId);
    var parameters = new ArrayList<String>(7);
    toggle(parameters, from.bold(), to.bold(), "1", "22");
    toggle(parameters, from.italic(), to.italic(), "3", "23");
    toggle(parameters, from.underline(), to.underline(), "4", "24");
    toggle(parameters, from.inverse(), to.inverse(), "7", "27");
    toggle(parameters, from.strikethrough(), to.strikethrough(), "9", "29");
    appendColorTransition(parameters, effective(from.foreground()), effective(to.foreground()), true);
    appendColorTransition(parameters, effective(from.background()), effective(to.background()), false);
    if (!parameters.isEmpty()) output.append("\u001b[").append(String.join(";", parameters)).append('m');
  }

  public int size() { return styles.size(); }

  public long poolId() { return poolId; }

  public boolean overflowed() { return overflowed; }

  public void clear() {
    poolId = NEXT_POOL_ID.getAndIncrement();
    styles.clear();
    sgrCache.clear();
    ids.clear();
    overflowed = false;
    add(TerminalStyle.EMPTY);
  }

  private int add(TerminalStyle style) {
    int id = styles.size();
    styles.add(style);
    sgrCache.add(buildSgr(style));
    ids.put(style, id);
    return id;
  }

  private String buildSgr(TerminalStyle style) {
    var parameters = new ArrayList<String>(7);
    parameters.add("0");
    if (style.bold()) parameters.add("1");
    // Faint is intentionally identity-only: Ajent suppresses SGR 2 for readability.
    if (style.italic()) parameters.add("3");
    if (style.underline()) parameters.add("4");
    if (style.inverse()) parameters.add("7");
    if (style.strikethrough()) parameters.add("9");
    var foreground = effective(style.foreground());
    var background = effective(style.background());
    if (foreground != null) parameters.add(colorSgr(foreground, true));
    if (background != null) parameters.add(colorSgr(background, false));
    return "\u001b[" + String.join(";", parameters) + 'm';
  }

  private void appendColorTransition(List<String> parameters, TerminalColor from,
      TerminalColor to, boolean foreground) {
    if (java.util.Objects.equals(from, to)) return;
    parameters.add(to == null ? (foreground ? "39" : "49") : colorSgr(to, foreground));
  }

  private String colorSgr(TerminalColor color, boolean foreground) {
    var degraded = color.degrade(colorLevel);
    return foreground ? degraded.foregroundSgr() : degraded.backgroundSgr();
  }

  private int validId(int id) { return id >= 0 && id < styles.size() ? id : 0; }

  private static TerminalColor effective(TerminalColor color) {
    return color != null && color.kind() != TerminalColor.Kind.DEFAULT ? color : null;
  }

  private static void toggle(List<String> parameters, boolean from, boolean to,
      String enabled, String disabled) {
    if (from != to) parameters.add(to ? enabled : disabled);
  }
}
