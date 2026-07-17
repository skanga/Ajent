package com.github.skanga.ajent.provider.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class SseFramerTest {
  @Test
  void groupsEventAndMultilineDataUsingSseRules() {
    var events = new ArrayList<SseFramer.Event>();
    var framer = new SseFramer();
    String wire = ": heartbeat\r\nevent: content_block_delta\r\n"
        + "data: first\r\ndata:  second\r\nunknown: ignored\r\n\r\n";
    byte[] bytes = wire.getBytes(StandardCharsets.UTF_8);
    framer.feed(java.util.Arrays.copyOfRange(bytes, 0, 13), events::add);
    framer.feed(java.util.Arrays.copyOfRange(bytes, 13, bytes.length), events::add);
    assertThat(events).containsExactly(
        new SseFramer.Event("content_block_delta", "first\nsecond"));
  }

  @Test
  void dispatchesDataOnlyAndEventOnlyFramesButNotBlankFrames() {
    var events = new ArrayList<SseFramer.Event>();
    var framer = new SseFramer();
    framer.feed("data: x\n\nevent: ping\n\n\n".getBytes(StandardCharsets.UTF_8), events::add);
    assertThat(events).containsExactly(
        new SseFramer.Event("", "x"), new SseFramer.Event("ping", ""));
  }

  @Test
  void overflowDropsWholeInflightEventAndResynchronizesAtBlankLine() {
    var events = new ArrayList<SseFramer.Event>();
    var framer = new SseFramer(16, 5);
    framer.feed("event: too_big\ndata: 123\ndata: 456\ndata: ignored\n\n"
        .getBytes(StandardCharsets.UTF_8), events::add);
    framer.feed("data: ok\n\n".getBytes(StandardCharsets.UTF_8), events::add);
    assertThat(events).containsExactly(new SseFramer.Event("", "ok"));
  }

  @Test
  void splitMultibytePayloadSurvivesByteByByteFeeds() {
    var events = new ArrayList<SseFramer.Event>();
    var framer = new SseFramer();
    for (byte value : "data: 中🙂\n\n".getBytes(StandardCharsets.UTF_8)) {
      framer.feed(new byte[] {value}, events::add);
    }
    assertThat(events).containsExactly(new SseFramer.Event("", "中🙂"));
  }
}
