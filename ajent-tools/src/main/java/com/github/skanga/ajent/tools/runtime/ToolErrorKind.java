package com.github.skanga.ajent.tools.runtime;

public enum ToolErrorKind {
  INVALID_ARGS("invalid args"), NOT_FOUND("not found"), NOT_A_FILE("not a file"),
  NOT_A_DIRECTORY("not a directory"), TOO_LARGE("too large"), BINARY("binary"),
  AMBIGUOUS("ambiguous"), NO_MATCH("no match"), INVALID_REGEX("invalid regex"),
  NETWORK("network"), SPAWN("spawn failed"), SUBPROCESS("subprocess failed"), IO("io"),
  OUT_OF_WORKSPACE("out of workspace"), UNKNOWN("unknown");

  private final String label;
  ToolErrorKind(String label) { this.label = label; }
  @Override public String toString() { return label; }
}
