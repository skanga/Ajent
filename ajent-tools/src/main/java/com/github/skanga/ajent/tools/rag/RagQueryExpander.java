package com.github.skanga.ajent.tools.rag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Opt-in generative multi-query expansion with original-query fallback. */
public final class RagQueryExpander {
  public record Config(String host, int port, String model, int variants) {}

  @FunctionalInterface
  public interface GenerationTransport {
    Optional<String> generate(Config config, String prompt);
  }

  private final GenerationTransport transport;
  public RagQueryExpander(GenerationTransport transport) { this.transport = transport; }

  public List<String> expand(Config config, String query) {
    var result = new ArrayList<String>();
    result.add(query);
    if (config.model().isEmpty() || config.variants() == 0) return List.copyOf(result);
    String prompt = "You rewrite a search query into alternative phrasings to improve document "
        + "retrieval. Given the user's query, output " + config.variants()
        + " DIFFERENT search queries that capture the same intent using different wording, synonyms, "
        + "and levels of specificity.\nRules: output ONLY the queries, ONE per line, no numbering, no "
        + "commentary, no blank lines.\n\nUser query: " + query + "\n\nAlternative queries:";
    String text;
    try {
      Optional<String> generated = transport == null ? Optional.empty() : transport.generate(config, prompt);
      if (generated.isEmpty() || generated.orElseThrow().isEmpty()) return List.copyOf(result);
      text = generated.orElseThrow();
    } catch (RuntimeException exception) {
      return List.copyOf(result);
    }
    Set<String> seen = new HashSet<>();
    seen.add(query.toLowerCase(Locale.ROOT));
    String[] lines = text.split("\n", -1);
    for (String line : lines) {
      if (result.size() >= config.variants() + 1) break;
      String candidate = cleanLine(line);
      if (candidate.length() < 2) continue;
      if (seen.add(candidate.toLowerCase(Locale.ROOT))) result.add(candidate);
    }
    return List.copyOf(result);
  }

  static String cleanLine(String value) {
    String result = trimAscii(value);
    int index = 0;
    while (index < result.length() && result.charAt(index) >= '0' && result.charAt(index) <= '9') index++;
    if (index > 0 && index < result.length()
        && (result.charAt(index) == '.' || result.charAt(index) == ')')) {
      result = trimLeft(result.substring(index + 1));
    } else if (!result.isEmpty() && (result.charAt(0) == '-'
        || result.charAt(0) == '*' || result.charAt(0) == '+')) {
      result = trimLeft(result.substring(1));
    } else if (result.startsWith("•")) {
      result = trimLeft(result.substring(1));
    }
    if (result.length() >= 2 && (result.charAt(0) == '"'
        && result.charAt(result.length() - 1) == '"' || result.charAt(0) == '\''
        && result.charAt(result.length() - 1) == '\''))
      result = trimAscii(result.substring(1, result.length() - 1));
    return result;
  }

  private static String trimAscii(String value) {
    return trimRight(trimLeft(value));
  }
  private static String trimLeft(String value) {
    int index = 0;
    while (index < value.length() && isSpace(value.charAt(index))) index++;
    return value.substring(index);
  }
  private static String trimRight(String value) {
    int index = value.length();
    while (index > 0 && isSpace(value.charAt(index - 1))) index--;
    return value.substring(0, index);
  }
  private static boolean isSpace(char value) {
    return value == ' ' || value == '\t' || value == '\n' || value == '\r'
        || value == '\f' || value == '\u000b';
  }
}
