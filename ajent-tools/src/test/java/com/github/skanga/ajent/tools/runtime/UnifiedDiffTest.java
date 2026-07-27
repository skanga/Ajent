package com.github.skanga.ajent.tools.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class UnifiedDiffTest {
  @Test void computesNativeCoordinatesCountsPatchOrderingAndUnifiedRendering() {
    FileChange change = UnifiedDiff.compute(
        "src/a.txt", "one\ntwo\nthree\n", "one\nTWO\nthree\n");

    assertThat(change.added()).isEqualTo(1);
    assertThat(change.removed()).isEqualTo(1);
    assertThat(change.hunks()).singleElement().satisfies(hunk -> {
      assertThat(hunk.oldStart()).isEqualTo(1);
      assertThat(hunk.oldLength()).isEqualTo(4);
      assertThat(hunk.newStart()).isEqualTo(1);
      assertThat(hunk.newLength()).isEqualTo(4);
      assertThat(hunk.patch()).isEqualTo(" one\n-two\n+TWO\n three\n \n");
      assertThat(hunk.status()).isEqualTo(DiffHunk.Status.PENDING);
    });
    assertThat(UnifiedDiff.render(change)).isEqualTo(
        "--- a/src/a.txt\n+++ b/src/a.txt\n@@ -1,4 +1,4 @@\n"
            + " one\n-two\n+TWO\n three\n \n");
  }

  @Test void preservesExplicitFileProvenanceThroughHunkUpdates() {
    FileChange created = UnifiedDiff.compute("new.txt", "", "body", false);
    FileChange existing = UnifiedDiff.compute("empty.txt", "", "body", true);

    assertThat(created.existedBefore()).isFalse();
    assertThat(created.withHunks(created.hunks()).existedBefore()).isFalse();
    assertThat(existing.existedBefore()).isTrue();
    assertThat(existing.withHunks(existing.hunks()).existedBefore()).isTrue();
  }

  @Test void separatesDistantChangesButMergesChangesWithinSixContextRows() {
    String before = numbered(14);
    String far = before.replace("line-2", "far-2").replace("line-12", "far-12");
    String near = before.replace("line-2", "near-2").replace("line-7", "near-7");

    assertThat(UnifiedDiff.compute("far", before, far).hunks()).hasSize(2);
    assertThat(UnifiedDiff.compute("near", before, near).hunks()).hasSize(1);
  }

  @Test void acceptedHunkReconstructionHandlesAllNoneAndPartialSelections() {
    FileChange pending = UnifiedDiff.compute("x", numbered(14),
        numbered(14).replace("line-2", "new-2").replace("line-12", "new-12"));
    assertThat(UnifiedDiff.applyAccepted(pending)).isEqualTo(pending.before());

    var accepted = pending.hunks().stream()
        .map(hunk -> hunk.withStatus(DiffHunk.Status.ACCEPTED)).toList();
    FileChange all = pending.withHunks(accepted);
    assertThat(UnifiedDiff.applyAccepted(all)).isEqualTo(pending.after());

    var partial = new ArrayList<>(pending.hunks());
    partial.set(0, partial.getFirst().withStatus(DiffHunk.Status.ACCEPTED));
    partial.set(1, partial.get(1).withStatus(DiffHunk.Status.REJECTED));
    assertThat(UnifiedDiff.applyAccepted(pending.withHunks(partial)))
        .contains("new-2").contains("line-12").doesNotContain("new-12");
  }

  @Test void coversEmptySidesNoChangesAndTheNativeLargeMatrixFallback() {
    assertThat(UnifiedDiff.compute("same", "same", "same").hunks()).isEmpty();
    assertThat(UnifiedDiff.compute("new", "", "body")).satisfies(change -> {
      assertThat(change.added()).isEqualTo(1);
      assertThat(change.removed()).isEqualTo(1);
    });
    assertThat(UnifiedDiff.compute("gone", "body", "")).satisfies(change -> {
      assertThat(change.added()).isEqualTo(1);
      assertThat(change.removed()).isEqualTo(1);
    });

    String before = distinct("old", 2_450);
    String after = distinct("new", 2_450);
    FileChange large = UnifiedDiff.compute("large", before, after);
    assertThat(large.hunks()).hasSize(1);
    assertThat(large.added()).isEqualTo(2_450);
    assertThat(large.removed()).isEqualTo(2_450);
  }

  private static String numbered(int lines) { return distinct("line", lines); }

  private static String distinct(String prefix, int lines) {
    var result = new StringBuilder();
    for (int line = 1; line <= lines; line++) {
      if (line > 1) result.append('\n');
      result.append(prefix).append('-').append(line);
    }
    return result.toString();
  }
}
