package com.github.skanga.ajent.tools.rag;

import java.util.List;
import java.util.Locale;

/** Classic Porter (1980) English stemmer used by opt-in BM25 normalization. */
public final class RagStemmer {
  private static final Rule[] STEP_TWO = {
      new Rule("ational", "ate"), new Rule("tional", "tion"), new Rule("enci", "ence"),
      new Rule("anci", "ance"), new Rule("izer", "ize"), new Rule("abli", "able"),
      new Rule("alli", "al"), new Rule("entli", "ent"), new Rule("eli", "e"),
      new Rule("ousli", "ous"), new Rule("ization", "ize"), new Rule("ation", "ate"),
      new Rule("ator", "ate"), new Rule("alism", "al"), new Rule("iveness", "ive"),
      new Rule("fulness", "ful"), new Rule("ousness", "ous"), new Rule("aliti", "al"),
      new Rule("iviti", "ive"), new Rule("biliti", "ble")};
  private static final Rule[] STEP_THREE = {
      new Rule("icate", "ic"), new Rule("ative", ""), new Rule("alize", "al"),
      new Rule("iciti", "ic"), new Rule("ical", "ic"), new Rule("ful", ""),
      new Rule("ness", "")};
  private static final String[] STEP_FOUR = {"al", "ance", "ence", "er", "ic", "able", "ible",
      "ant", "ement", "ment", "ent", "ion", "ou", "ism", "ate", "iti", "ous", "ive", "ize"};

  private record Rule(String from, String to) {}
  private RagStemmer() {}

  public static String stem(String word) {
    if (word.length() < 3) return word;
    String stem = word.toLowerCase(Locale.ROOT);
    stem = stepOneA(stem);
    stem = stepOneB(stem);
    stem = stepOneC(stem);
    stem = replaceMeasured(stem, STEP_TWO);
    stem = replaceMeasured(stem, STEP_THREE);
    stem = stepFour(stem);
    stem = stepFiveA(stem);
    return stepFiveB(stem);
  }

  public static List<String> stemTokens(List<String> tokens) {
    return tokens.stream().map(RagStemmer::stem).toList();
  }

  private static String stepOneA(String value) {
    if (value.endsWith("sses") || value.endsWith("ies")) return drop(value, 2);
    if (value.endsWith("ss")) return value;
    return value.endsWith("s") ? drop(value, 1) : value;
  }

  private static String stepOneB(String value) {
    boolean changed = false;
    if (value.endsWith("eed")) {
      String base = drop(value, 3);
      if (measure(base) > 0) value = drop(value, 2);
    } else if (value.endsWith("ed")) {
      String base = drop(value, 2);
      if (hasVowel(base)) {
        value = base;
        changed = true;
      }
    } else if (value.endsWith("ing")) {
      String base = drop(value, 3);
      if (hasVowel(base)) {
        value = base;
        changed = true;
      }
    }
    if (!changed) return value;
    if (value.endsWith("at") || value.endsWith("bl") || value.endsWith("iz")) return value + 'e';
    if (endsDoubleConsonant(value) && !value.endsWith("l")
        && !value.endsWith("s") && !value.endsWith("z")) return drop(value, 1);
    return measure(value) == 1 && endsCvc(value) ? value + 'e' : value;
  }

  private static String stepOneC(String value) {
    if (!value.endsWith("y")) return value;
    String base = drop(value, 1);
    return hasVowel(base) ? base + 'i' : value;
  }

  private static String replaceMeasured(String value, Rule[] rules) {
    for (Rule rule : rules) if (value.endsWith(rule.from())) {
      String base = drop(value, rule.from().length());
      return measure(base) > 0 ? base + rule.to() : value;
    }
    return value;
  }

  private static String stepFour(String value) {
    for (String suffix : STEP_FOUR) if (value.endsWith(suffix)) {
      String base = drop(value, suffix.length());
      if (suffix.equals("ion")) {
        if (!base.isEmpty() && (base.endsWith("s") || base.endsWith("t")) && measure(base) > 1)
          return base;
      } else if (measure(base) > 1) return base;
      return value;
    }
    return value;
  }

  private static String stepFiveA(String value) {
    if (!value.endsWith("e")) return value;
    String base = drop(value, 1);
    int measure = measure(base);
    return measure > 1 || measure == 1 && !endsCvc(base) ? base : value;
  }

  private static String stepFiveB(String value) {
    return measure(value) > 1 && endsDoubleConsonant(value) && value.endsWith("l")
        ? drop(value, 1) : value;
  }

  private static int measure(String value) {
    int measure = 0;
    int index = 0;
    while (index < value.length() && isConsonant(value, index)) index++;
    while (index < value.length()) {
      while (index < value.length() && !isConsonant(value, index)) index++;
      if (index >= value.length()) break;
      while (index < value.length() && isConsonant(value, index)) index++;
      measure++;
    }
    return measure;
  }

  private static boolean hasVowel(String value) {
    for (int index = 0; index < value.length(); index++) if (!isConsonant(value, index)) return true;
    return false;
  }

  private static boolean endsDoubleConsonant(String value) {
    int length = value.length();
    return length >= 2 && value.charAt(length - 1) == value.charAt(length - 2)
        && isConsonant(value, length - 1);
  }

  private static boolean endsCvc(String value) {
    int length = value.length();
    if (length < 3 || !isConsonant(value, length - 1) || isConsonant(value, length - 2)
        || !isConsonant(value, length - 3)) return false;
    char last = value.charAt(length - 1);
    return last != 'w' && last != 'x' && last != 'y';
  }

  private static boolean isConsonant(String value, int index) {
    if (index >= value.length()) return false;
    char character = value.charAt(index);
    if (character == 'a' || character == 'e' || character == 'i'
        || character == 'o' || character == 'u') return false;
    if (character == 'y') return index == 0 || !isConsonant(value, index - 1);
    return true;
  }

  private static String drop(String value, int count) {
    return value.substring(0, value.length() - count);
  }
}
