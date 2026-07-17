package com.github.skanga.ajent.provider;

/** Reducer-facing classification of provider failures. */
public enum ErrorClass {
  TRANSIENT("transient"),
  RATE_LIMIT("rate_limit"),
  AUTH("auth"),
  CANCELLED("cancelled"),
  TERMINAL("terminal");

  private final String label;

  ErrorClass(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
