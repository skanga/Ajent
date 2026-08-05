package com.github.skanga.ajent.tools.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

final class RagStemmerTest {
  @Test void portsAjentAdvancedStemmerCases() {
    assertThat(RagStemmer.stem("running")).isEqualTo("run");
    assertThat(RagStemmer.stem("configured")).isEqualTo("configur");
    assertThat(RagStemmer.stem("deployment")).isEqualTo("deploy");
    assertThat(RagStemmer.stem("happily")).isEqualTo("happili");
    assertThat(RagStemmer.stem("connections")).isEqualTo("connect");
    assertThat(RagStemmer.stem("go")).isEqualTo("go");
    assertThat(RagStemmer.stem("a")).isEqualTo("a");
    assertThat(RagStemmer.stemTokens(List.of("running", "quickly", "configurations")))
        .containsExactly("run", "quickli", "configur");
  }

  @Test void implementsClassicPorterRuleFamilies() {
    assertThat(List.of("caresses", "ponies", "caress", "cats").stream().map(RagStemmer::stem))
        .containsExactly("caress", "poni", "caress", "cat");
    assertThat(List.of("feed", "agreed", "plastered", "bled", "motoring", "sing").stream()
        .map(RagStemmer::stem)).containsExactly("feed", "agr", "plaster", "bled", "motor", "sing");
    assertThat(List.of("conflated", "troubled", "sized", "hopping", "tanned", "falling",
        "hissing", "fizzed", "failing", "filing").stream().map(RagStemmer::stem))
        .containsExactly("conflat", "troubl", "size", "hop", "tan", "fall", "hiss", "fizz",
            "fail", "file");
    assertThat(List.of("relational", "conditional", "vietnamization", "triplicate", "formalize",
        "electriciti", "hopeful", "goodness", "revival", "allowance", "effective",
        "bowdlerize").stream().map(RagStemmer::stem))
        .containsExactly("relat", "condit", "vietnam", "triplic", "formal", "electr", "hope",
            "good", "reviv", "allow", "effect", "bowdler");
    assertThat(List.of("probate", "rate", "cease", "controll", "roll").stream()
        .map(RagStemmer::stem)).containsExactly("probat", "rate", "ceas", "control", "roll");
    assertThat(RagStemmer.stem("SKIES")).isEqualTo("ski");
  }

  @Test void optInStemmingUsesTheSameVocabularyForIndexAndQuery() {
    List<RagChunk> chunks = List.of(new RagChunk("configure.md", 1, 1, "configurations guide"),
        new RagChunk("other.md", 1, 1, "unrelated material"));
    Bm25Index literal = RagAlgorithms.buildBm25(chunks, false);
    Bm25Index stemmed = RagAlgorithms.buildBm25(chunks, true);

    assertThat(RagAlgorithms.searchBm25(literal, "configured", 2)).isEmpty();
    assertThat(RagAlgorithms.searchBm25(stemmed, "configured", 2)).first()
        .extracting(RagAlgorithms.Score::document).isEqualTo(0);
    assertThat(stemmed.stemmed()).isTrue();
  }

  @Test void matchesAjentEnvironmentTruthiness() {
    assertThat(RagAlgorithms.stemmerEnabled(null)).isFalse();
    assertThat(RagAlgorithms.stemmerEnabled("")).isFalse();
    assertThat(RagAlgorithms.stemmerEnabled("0")).isFalse();
    assertThat(RagAlgorithms.stemmerEnabled("false")).isFalse();
    assertThat(RagAlgorithms.stemmerEnabled("FALSE")).isFalse();
    assertThat(RagAlgorithms.stemmerEnabled("False")).isTrue();
    assertThat(RagAlgorithms.stemmerEnabled("1")).isTrue();
  }
}
