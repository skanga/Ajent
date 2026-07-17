package com.github.skanga.ajent.core.persistence;

import java.util.Objects;

public record DeserializeError(Kind kind, String field, String detail) {
  public enum Kind { JSON_PARSE, MISSING_FIELD, INVALID_VALUE, INVALID_VARIANT_TAG, IO }

  public DeserializeError {
    kind = Objects.requireNonNull(kind, "kind");
    field = Objects.requireNonNull(field, "field");
    detail = Objects.requireNonNull(detail, "detail");
  }

  public String render() {
    String name = kind.name().toLowerCase(java.util.Locale.ROOT);
    return "[" + name + "] " + (field.isEmpty() ? "" : field + ": ") + detail;
  }
}
