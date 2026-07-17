package com.github.skanga.ajent.tools.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RagRerankerTest {
  @Test void extractsDistinctQueryTermsInEncounterOrder() {
    assertThat(RagReranker.queryTerms("How do I configure OAuth tokens?"))
        .containsExactly("how", "do", "configure", "oauth", "tokens");
    assertThat(RagReranker.queryTerms("token token token")).containsExactly("token");
    assertThat(RagReranker.queryTerms("I x ?")).isEmpty();
  }

  @Test void promotesBroadQueryTermCoverage() {
    RagChunk narrow = chunk("doc0.md",
        "the kubernetes cluster runs many things and other unrelated words here");
    RagChunk broad = chunk("doc1.md",
        "configure oauth tokens and refresh credentials for the kubernetes cluster");
    RagChunk unrelated = chunk("doc2.md",
        "a completely unrelated paragraph about weather and oceans");

    List<RagCorpus.Hit> result = RagReranker.rerank("configure oauth tokens", List.of(
        new RagCorpus.Hit(narrow, .90), new RagCorpus.Hit(broad, .40),
        new RagCorpus.Hit(unrelated, .20)), 3);

    assertThat(result).hasSize(3);
    assertThat(result.getFirst().chunk()).isSameAs(broad);
  }

  @Test void pathMatchBreaksOtherwiseStableTie() {
    RagChunk general = chunk("misc/setup.md", "general notes about configuration and setup steps");
    RagChunk oauth = chunk("auth/oauth_guide.md", "general notes about configuration and setup steps");

    assertThat(RagReranker.rerank("oauth", List.of(
        new RagCorpus.Hit(general, .5), new RagCorpus.Hit(oauth, .5)), 2).getFirst().chunk())
        .isSameAs(oauth);
  }

  @Test void capsOutputAndPreservesStableTies() {
    var hits = new ArrayList<RagCorpus.Hit>();
    for (int index = 0; index < 10; index++)
      hits.add(new RagCorpus.Hit(chunk("doc" + index, "some text number " + index), .5 + index * .01));
    assertThat(RagReranker.rerank("text", hits, 3)).hasSize(3);

    RagChunk first = chunk("a", "same text");
    RagChunk second = chunk("b", "same text");
    assertThat(RagReranker.rerank("text", List.of(
        new RagCorpus.Hit(first, 1), new RagCorpus.Hit(second, 1)), 2))
        .extracting(RagCorpus.Hit::chunk).containsExactly(first, second);
    assertThat(RagReranker.rerank("q", List.of(), 3)).isEmpty();
    assertThat(RagReranker.rerank("q", List.of(new RagCorpus.Hit(first, 1)), 0)).isEmpty();
  }

  @Test void compressionExtractsTheRelevantVerbatimSpan() {
    String text = "Intro sentence about nothing in particular here. "
        + "Another filler sentence with weather and oceans. "
        + "To configure oauth tokens you must set the client id and secret. "
        + "Then refresh credentials periodically. "
        + "Closing remarks about unrelated topics and more filler text follows.";

    String result = RagReranker.compress("configure oauth tokens", text, 120);
    assertThat(result).isNotEmpty().hasSizeLessThan(text.length()).contains("oauth");
    assertThat(text).contains(result);
    assertThat(RagReranker.compress("oauth", "just a short note about oauth", 600))
        .isEqualTo("just a short note about oauth");
    assertThat(RagReranker.compress("zzzznotpresent", text, 100))
        .isNotEmpty().hasSizeLessThanOrEqualTo(100);
  }

  @Test void compressionHandlesBoundariesAndExpansionDirections() {
    assertThat(RagReranker.compress("q", "", 5)).isEmpty();
    assertThat(RagReranker.compress("match", "match is a single long sentence", 0))
        .isEqualTo("match is a single long sentence");
    assertThat(RagReranker.compress("right", "match left. target match. right right! tail", 31))
        .contains("target match");
    assertThat(RagReranker.compress("left", "left left. target left. unrelated tail.", 29))
        .contains("target left");
  }

  @Test void mmrDiversifiesNearDuplicateResults() {
    List<RagCorpus.Hit> hits = List.of(
        hit("k8s.md", "kubernetes deployment scales replicas pods containers", 1.0),
        hit("k8s2.md", "kubernetes deployment replicas containers orchestration", .9),
        hit("k8s3.md", "kubernetes pods replicas scaling cluster deployment", .8),
        hit("logging.md", "structured logging severity levels rotation json", .7),
        hit("db.md", "database transactions isolation btree indexes rows", .6));

    List<RagCorpus.Hit> diverse = RagReranker.mmrDiversify(hits, 3, .5);
    assertThat(diverse).hasSize(3);
    assertThat(diverse.stream().filter(hit -> hit.chunk().path().contains("k8s"))).hasSizeLessThan(3);
  }

  @Test void mmrHonorsDegenerateInputsAndClampsLambda() {
    RagCorpus.Hit empty = hit("empty", "", 0);
    RagCorpus.Hit words = hit("words", "some words", 0);
    assertThat(RagReranker.mmrDiversify(List.of(), 2, .5)).isEmpty();
    assertThat(RagReranker.mmrDiversify(List.of(empty), 0, .5)).isEmpty();
    assertThat(RagReranker.mmrDiversify(List.of(empty, words), 2, .5))
        .containsExactly(empty, words);
    assertThat(RagReranker.mmrDiversify(List.of(empty, words), 1, -2)).containsExactly(empty);
    assertThat(RagReranker.mmrDiversify(List.of(words, empty), 1, 2)).containsExactly(words);
  }

  @Test void contextDerivesConfidenceFromScoreDistribution() {
    RagContext high = RagContext.fromHits("query", List.of(hit("a", "text", .9), hit("b", "text", .85)));
    RagContext low = RagContext.fromHits("query", List.of(hit("c", "text", .1), hit("d", "text", .05)));
    assertThat(high.confidence()).isGreaterThan(.5);
    assertThat(low.confidence()).isLessThan(.3);
    assertThat(RagContext.fromHits("query", List.of()).confidence()).isZero();
    assertThat(RagContext.fromHits("query", List.of(hit("a", "text", 2))).confidence()).isEqualTo(1);
    assertThat(RagContext.fromHits("query", List.of(hit("a", "text", -.5))).confidence()).isZero();
  }

  private static RagCorpus.Hit hit(String path, String text, double score) {
    return new RagCorpus.Hit(chunk(path, text), score);
  }

  private static RagChunk chunk(String path, String text) {
    return new RagChunk(path, 1, 10, text);
  }
}
