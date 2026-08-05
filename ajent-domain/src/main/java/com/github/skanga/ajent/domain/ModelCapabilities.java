package com.github.skanga.ajent.domain;

import java.util.Locale;

/** Capabilities inferred from the model identifier, matching Ajent's catalog rules. */
public record ModelCapabilities(
    Family family,
    int generation,
    boolean generation4OrLater,
    int revision,
    boolean extendedContext1m,
    boolean weakToolUse) {

  public enum Family { UNKNOWN, HAIKU, SONNET, OPUS, FABLE, MYTHOS }

  public static ModelCapabilities fromId(String modelId) {
    String id = modelId == null ? "" : modelId;
    boolean extended = id.contains("[1m]");
    if (extended) id = id.substring(0, id.indexOf("[1m]"));
    Family family = Family.UNKNOWN;
    int generation = 0;
    int revision = 0;
    boolean expectRevision = false;
    String previous = "";
    for (String token : id.split("-")) {
      if (token.isEmpty()) continue;
      boolean wasExpectingRevision = expectRevision;
      expectRevision = false;
      Family decoded = decodeFamily(token);
      if (decoded != Family.UNKNOWN) {
        family = decoded;
      } else if (wasExpectingRevision && plausibleInteger(token)) {
        revision = Integer.parseInt(token);
      } else if (decodeFamily(previous) != Family.UNKNOWN && plausibleInteger(token)) {
        generation = Integer.parseInt(token);
        expectRevision = true;
      }
      previous = token;
    }
    var preliminary = new ModelCapabilities(
        family, generation, generation >= 4, revision, extended, false);
    return new ModelCapabilities(family, generation, generation >= 4, revision, extended,
        inferWeakToolUse(id, preliminary));
  }

  public static boolean isWeakModel(String modelId) {
    return fromId(modelId).isWeakToolUser();
  }

  public static int maxOutputTokensFor(String modelId) {
    return maxOutputTokensFor(modelId, System.getenv("AJENT_MAX_OUTPUT_TOKENS"));
  }

  public static int maxOutputTokensFor(String modelId, String override) {
    if (override != null) {
      int length = 0;
      while (length < override.length() && Character.isDigit(override.charAt(length))) length++;
      if (length > 0) {
        int value = Integer.parseInt(override.substring(0, length));
        if (value > 0) return value;
      }
    }
    String id = modelId == null ? "" : modelId;
    var capabilities = fromId(id);
    if (!capabilities.isKnownFamily()) return 16_384;
    if (capabilities.isHaiku()) return 8_192;
    if (capabilities.isFable() || capabilities.isMythos()) return 64_000;
    if (capabilities.generation >= 4) return 64_000;
    if (id.contains("claude-3") || capabilities.generation == 3) return 8_192;
    return 16_384;
  }

  public boolean isHaiku() { return family == Family.HAIKU; }
  public boolean isSonnet() { return family == Family.SONNET; }
  public boolean isOpus() { return family == Family.OPUS; }
  public boolean isFable() { return family == Family.FABLE; }
  public boolean isMythos() { return family == Family.MYTHOS; }
  public boolean isFlagship() { return isOpus() || isFable() || isMythos(); }
  public boolean isKnownFamily() { return family != Family.UNKNOWN; }
  public boolean isWeakToolUser() { return weakToolUse; }

  public boolean supportsEffort() {
    if (isFable() || isMythos()) return generation >= 5;
    if (isOpus()) return generation > 4 || generation == 4 && revision >= 5;
    if (isSonnet()) return generation > 4 || generation == 4 && revision >= 6;
    return false;
  }

  public boolean supportsEffortMax() {
    if (!supportsEffort()) return false;
    if (isFable() || isMythos()) return true;
    if (isOpus()) return generation > 4 || generation == 4 && revision >= 6;
    return true;
  }

  public boolean supportsEffortXhigh() {
    if (!supportsEffort()) return false;
    if (isFable() || isMythos()) return true;
    return isOpus() && (generation > 4 || generation == 4 && revision >= 7);
  }

  private static Family decodeFamily(String token) {
    return switch (token) {
      case "haiku" -> Family.HAIKU;
      case "sonnet" -> Family.SONNET;
      case "opus" -> Family.OPUS;
      case "fable" -> Family.FABLE;
      case "mythos" -> Family.MYTHOS;
      default -> Family.UNKNOWN;
    };
  }

  private static boolean plausibleInteger(String token) {
    if (token.isEmpty() || token.length() > 2) return false;
    for (int index = 0; index < token.length(); index++) {
      if (!Character.isDigit(token.charAt(index))) return false;
    }
    return true;
  }

  private static boolean inferWeakToolUse(String id, ModelCapabilities capabilities) {
    if (capabilities.isKnownFamily()) return false;
    String lower = id.toLowerCase(Locale.ROOT);
    int paramsBillions = parameterSizeBillions(lower);
    boolean strongFamily = containsAny(lower, "qwen3", "llama3.1", "llama-3.1", "llama3.3",
        "llama-3.3", "mistral", "mixtral", "ministral", "command-r", "hermes",
        "firefunction", "functionary", "devstral", "codestral", "gpt-oss", "granite",
        "glm-4", "deepseek-v3", "deepseek-r1");
    if (strongFamily) return paramsBillions != 0 && paramsBillions <= 3;
    boolean weakFamily = containsAny(lower, "qwen2.5", "qwen2", "codellama", "code-llama",
        "deepseek-coder", "starcoder", "stable-code", "phi", "gemma", "tinyllama",
        "smollm", "codegemma", "sqlcoder");
    if (weakFamily) return true;
    if (paramsBillions >= 14) return false;
    return paramsBillions != 0 && paramsBillions <= 8;
  }

  private static boolean containsAny(String value, String... needles) {
    for (String needle : needles) if (value.contains(needle)) return true;
    return false;
  }

  private static int parameterSizeBillions(String id) {
    int largest = 0;
    for (int index = 1; index < id.length(); index++) {
      if (id.charAt(index) != 'b') continue;
      if (index + 1 < id.length() && Character.isLetter(id.charAt(index + 1))) continue;
      int start = index;
      boolean dot = false;
      boolean digit = false;
      while (start > 0) {
        char previous = id.charAt(start - 1);
        if (Character.isDigit(previous)) { digit = true; start--; }
        else if (previous == '.' && !dot) { dot = true; start--; }
        else break;
      }
      if (!digit || start > 0 && Character.isLetter(id.charAt(start - 1))) continue;
      int end = id.indexOf('.', start);
      if (end < 0 || end > index) end = index;
      if (end > start) largest = Math.max(largest, Integer.parseInt(id.substring(start, end)));
    }
    return largest;
  }
}
