package com.github.skanga.ajent.tools.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class HnswIndexTest {
  @Test
  void scalarVectorPrimitivesMatchThePinnedSimdInvariant() {
    assertThat(HnswIndex.dot(new float[] {1, 2, 3, 4, 5, 6, 7, 8},
        new float[] {8, 7, 6, 5, 4, 3, 2, 1})).isCloseTo(120, within(1e-4f));
    assertThat(HnswIndex.normalize(new float[] {3, 4}))
        .containsExactly(new float[] {.6f, .8f}, within(1e-4f));
  }

  @Test
  void recallsTheTrueNearestNeighborAtFive() {
    var random = new Random(1_234_567L);
    List<float[]> database = vectors(random, 500, 32);
    var index = new HnswIndex();
    index.build(ids(database.size()), database);

    assertThat(index.size()).isEqualTo(500);
    assertThat(index.dimension()).isEqualTo(32);
    assertThat(index.isEmpty()).isFalse();

    int hits = 0;
    for (int queryIndex = 0; queryIndex < 30; queryIndex++) {
      float[] query = vector(random, 32);
      int expected = trueNearest(database, query);
      if (index.search(query, 5, 100).stream().anyMatch(hit -> hit.id() == expected)) hits++;
    }
    assertThat(hits / 30.0).isGreaterThanOrEqualTo(0.8);
  }

  @Test
  void returnsAnIdenticalStoredVectorFirst() {
    List<float[]> database = List.of(new float[] {1, 0, 0}, new float[] {0, 1, 0},
        new float[] {0, 0, 1});
    var index = new HnswIndex();
    index.build(List.of(0, 1, 2), database);

    assertThat(index.search(database.get(1), 3)).first().satisfies(hit -> {
      assertThat(hit.id()).isEqualTo(1);
      assertThat(hit.similarity()).isCloseTo(1, within(1e-4f));
    });
  }

  @Test
  void nativeBinaryRoundTripPreservesSearchResults() {
    var random = new Random(424_242L);
    List<float[]> database = vectors(random, 50, 16);
    var original = new HnswIndex();
    original.build(ids(database.size()), database);

    byte[] binary = original.serialize();
    assertThat(binary).isNotEmpty();
    var restored = new HnswIndex();
    ByteBuffer cursor = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
    assertThat(restored.deserialize(cursor)).isTrue();
    assertThat(cursor.remaining()).isZero();
    assertThat(restored.size()).isEqualTo(original.size());
    assertThat(restored.dimension()).isEqualTo(original.dimension());

    for (int queryIndex = 0; queryIndex < 8; queryIndex++) {
      float[] query = vector(random, 16);
      assertThat(restored.search(query, 1, 100).getFirst().id())
          .isEqualTo(original.search(query, 1, 100).getFirst().id());
    }
  }

  @Test
  void handlesEmptySingleAndInvalidVectors() {
    var index = new HnswIndex();
    assertThat(index.isEmpty()).isTrue();
    assertThat(index.size()).isZero();
    assertThat(index.search(new float[] {1, 2, 3}, 5)).isEmpty();

    index.add(7, new float[] {.5f, .5f, .5f});
    index.add(8, new float[0]);
    index.add(9, new float[] {1, 2});
    assertThat(index.size()).isOne();
    assertThat(index.search(new float[] {.5f, .5f, .5f}, 5).getFirst().id()).isEqualTo(7);
    assertThat(index.search(new float[] {1, 2}, 1)).isEmpty();
    assertThat(index.search(new float[] {.5f, .5f, .5f}, 0)).isEmpty();
  }

  @Test
  void rejectsMalformedOrTruncatedNativeBlobsAtomically() {
    var index = new HnswIndex();
    index.add(1, new float[] {1, 0});
    byte[] valid = index.serialize();

    for (int length = 0; length < valid.length; length++) {
      var restored = new HnswIndex();
      assertThat(restored.deserialize(ByteBuffer.wrap(valid, 0, length)
          .order(ByteOrder.LITTLE_ENDIAN))).isFalse();
      assertThat(restored.isEmpty()).isTrue();
    }
    valid[0] = 0;
    assertThat(new HnswIndex().deserialize(ByteBuffer.wrap(valid)
        .order(ByteOrder.LITTLE_ENDIAN))).isFalse();
  }

  @Test
  void readsAHandBuiltNativeLittleEndianGraph() {
    ByteBuffer nativeBlob = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
    nativeBlob.putInt(0x484E5301); // HNS\1
    nativeBlob.putInt(3);          // dimension
    nativeBlob.putInt(0);          // max layer
    nativeBlob.putInt(0);          // entry node
    nativeBlob.putInt(1);          // node count
    nativeBlob.putInt(77);         // external chunk id
    nativeBlob.putInt(3).putFloat(1).putFloat(0).putFloat(0);
    nativeBlob.putInt(1);          // layer count
    nativeBlob.putInt(0);          // no neighbors
    nativeBlob.flip();

    var index = new HnswIndex();
    assertThat(index.deserialize(nativeBlob)).isTrue();
    assertThat(index.search(new float[] {1, 0, 0}, 1).getFirst())
        .isEqualTo(new HnswIndex.SearchHit(77, 1));
  }

  @Test
  void validatesConfigurationAndExposesTheSelectedValues() {
    var config = new HnswIndex.Config(4, 8, 20, 10, 1.2, 42);
    assertThat(new HnswIndex(config).config()).isEqualTo(config);
    assertThatIllegalArgumentException().isThrownBy(
        () -> new HnswIndex.Config(0, 8, 20, 10, 1, 1));
    assertThatIllegalArgumentException().isThrownBy(
        () -> new HnswIndex.Config(4, 0, 20, 10, 1, 1));
    assertThatIllegalArgumentException().isThrownBy(
        () -> new HnswIndex.Config(4, 8, 0, 10, 1, 1));
    assertThatIllegalArgumentException().isThrownBy(
        () -> new HnswIndex.Config(4, 8, 20, 0, 1, 1));
    assertThatIllegalArgumentException().isThrownBy(
        () -> new HnswIndex.Config(4, 8, 20, 10, Double.NaN, 1));
    assertThatIllegalArgumentException().isThrownBy(
        () -> new HnswIndex.Config(4, 8, 20, 10, 0, 1));
  }

  private static List<Integer> ids(int count) {
    var result = new ArrayList<Integer>(count);
    for (int index = 0; index < count; index++) result.add(index);
    return result;
  }

  private static List<float[]> vectors(Random random, int count, int dimension) {
    var result = new ArrayList<float[]>(count);
    for (int index = 0; index < count; index++) result.add(vector(random, dimension));
    return result;
  }

  private static float[] vector(Random random, int dimension) {
    float[] result = new float[dimension];
    for (int index = 0; index < dimension; index++) result[index] = (float) random.nextGaussian();
    return result;
  }

  private static int trueNearest(List<float[]> database, float[] query) {
    int best = 0;
    double similarity = -2;
    for (int index = 0; index < database.size(); index++) {
      double candidate = RagAlgorithms.cosine(query, database.get(index));
      if (candidate > similarity) {
        best = index;
        similarity = candidate;
      }
    }
    return best;
  }
}
