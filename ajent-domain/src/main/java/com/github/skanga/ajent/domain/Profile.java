package com.github.skanga.ajent.domain;

/** Tool-permission tier, in AgenTTY's persisted ordinal order. */
public enum Profile {
  WRITE,
  ASK,
  MINIMAL;

  public static Profile fromPersistedOrdinal(int ordinal) {
    return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : WRITE;
  }
}
