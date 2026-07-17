package com.github.skanga.ajent.provider.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class LineFramerTest {
  @Test
  void dispatchesOnlyCompleteLinesAcrossArbitraryByteBoundaries() {
    var lines = new ArrayList<String>();
    var framer = new LineFramer(8);
    framer.feed("one\r".getBytes(StandardCharsets.UTF_8), lines::add);
    framer.feed("\ntwo\npart".getBytes(StandardCharsets.UTF_8), lines::add);
    assertThat(lines).containsExactly("one", "two");
    framer.feed("ial\n".getBytes(StandardCharsets.UTF_8), lines::add);
    assertThat(lines).containsExactly("one", "two", "partial");
  }

  @Test
  void retainsSplitUtf8CodePointsUntilTheLineCompletes() {
    byte[] bytes = "中🙂\n".getBytes(StandardCharsets.UTF_8);
    var lines = new ArrayList<String>();
    var framer = new LineFramer();
    for (byte value : bytes) framer.feed(new byte[] {value}, lines::add);
    assertThat(lines).containsExactly("中🙂");
  }

  @Test
  void emptyLinesAreObservableAndCompactionDoesNotLoseTailBytes() {
    var lines = new ArrayList<String>();
    var framer = new LineFramer(3);
    framer.feed("\n\na\nb".getBytes(StandardCharsets.UTF_8), lines::add);
    framer.feed("\n".getBytes(StandardCharsets.UTF_8), lines::add);
    assertThat(lines).containsExactly("", "", "a", "b");
  }
}
