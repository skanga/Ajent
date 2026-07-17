package com.github.skanga.ajent.tools.policy;

/** Observable tool capability using AgenTTY's bit assignments. */
public enum Effect {
  READ_FS(1, "ReadFs"),
  WRITE_FS(2, "WriteFs"),
  NET(4, "Net"),
  EXEC(8, "Exec");

  private final int bit;
  private final String label;

  Effect(int bit, String label) {
    this.bit = bit;
    this.label = label;
  }

  public int bit() { return bit; }

  @Override public String toString() { return label; }
}
