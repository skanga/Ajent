package com.github.skanga.ajent.domain;

import java.util.ArrayList;
import java.util.List;

/** User-selectable adaptive-thinking tier with AgenTTY's model capability clamps. */
public enum Effort {
  NONE(""), LOW("low"), MEDIUM("medium"), HIGH("high"), XHIGH("xhigh"), MAX("max");

  private final String wire;

  Effort(String wire) { this.wire = wire; }

  public String wire() { return wire; }
  public String label() { return this == NONE ? "off" : wire; }

  public static Effort fromWire(String value) {
    if (value == null) return NONE;
    return switch (value) {
      case "low" -> LOW;
      case "medium" -> MEDIUM;
      case "high" -> HIGH;
      case "xhigh" -> XHIGH;
      case "max" -> MAX;
      default -> NONE;
    };
  }

  public Effort clamp(ModelCapabilities capabilities) {
    if (this == NONE || !capabilities.supportsEffort()) return NONE;
    if (this == MAX && !capabilities.supportsEffortMax()) return HIGH;
    if (this == XHIGH && !capabilities.supportsEffortXhigh()) return HIGH;
    return this;
  }

  public static List<Effort> available(ModelCapabilities capabilities) {
    if (!capabilities.supportsEffort()) return List.of();
    var result = new ArrayList<>(List.of(NONE, LOW, MEDIUM, HIGH));
    if (capabilities.supportsEffortXhigh()) result.add(XHIGH);
    if (capabilities.supportsEffortMax()) result.add(MAX);
    return List.copyOf(result);
  }

  public Effort cycle(int delta, ModelCapabilities capabilities) {
    List<Effort> available = available(capabilities);
    if (available.isEmpty()) return NONE;
    int index = available.indexOf(this);
    if (index < 0) index = 0;
    return available.get(Math.floorMod(index + delta, available.size()));
  }
}
