package com.github.skanga.ajent.cli;

import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.ToolStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/** JMH translation of long_session_bench.cpp's resume/render and live-tail phases. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class LongSessionProbeBenchmark {
  @State(Scope.Thread)
  public static class Session {
    /** The exact native A-I scenario table; each token represents one paired shape. */
    @Param({"A", "B", "C", "D", "E", "F", "G", "H", "I"}) public String scenario;
    private SessionBenchmarkSupport.Shape shape;
    private List<Message> messages;
    private BenchmarkUiSupport.Fixture warm;
    private BenchmarkUiSupport.Fixture midrun;

    @Setup(Level.Iteration) public void setup() {
      shape = SessionBenchmarkSupport.Shape.named(scenario);
      messages = SessionBenchmarkSupport.transcript(shape);
      warm = BenchmarkUiSupport.fixture(messages, 120, 800, false);
      warm.render();
      warm.renderAfter(TimeUnit.HOURS.toNanos(1));

      var activeMessages = new ArrayList<>(messages);
      activeMessages.add(BenchmarkUiSupport.user("continue the active run"));
      activeMessages.add(BenchmarkUiSupport.assistant("Working on the live tail.",
          List.of(SessionBenchmarkSupport.write("src/auth/live.cpp",
              Math.max(8, shape.writeLines()), new ToolStatus.Running("streaming")))));
      midrun = BenchmarkUiSupport.fixture(activeMessages, 120, 80, true);
      midrun.render();
    }
  }

  @State(Scope.Thread)
  public static class Streaming {
    @Param({"write", "prose"}) public String kind;
    @Param({"8", "100", "400", "800", "1500", "3000"}) public int liveLines;
    private BenchmarkUiSupport.Fixture fixture;

    @Setup(Level.Iteration) public void setup() {
      var messages = new ArrayList<>(SessionBenchmarkSupport.transcript(
          SessionBenchmarkSupport.Shape.writeShape(6, 300)));
      messages.add(BenchmarkUiSupport.user("continue the refactor"));
      Message live = "prose".equals(kind)
          ? BenchmarkUiSupport.assistant(SessionBenchmarkSupport.streamingProse(liveLines),
              List.of())
          : BenchmarkUiSupport.assistant(SessionBenchmarkSupport.prose(2), List.of(
              SessionBenchmarkSupport.write("src/auth/login.cpp", liveLines,
                  new ToolStatus.Running("streaming"))));
      messages.add(live);
      fixture = BenchmarkUiSupport.fixture(messages, 120, 80, true);
      fixture.render();
    }
  }

  @Benchmark public com.github.skanga.ajent.domain.Thread construct(Session state) {
    return SessionBenchmarkSupport.thread(state.shape);
  }

  @Benchmark public void renderKeys(Session state, Blackhole blackhole) {
    long key = 0;
    for (Message message : state.messages) key ^= InteractiveVisualHash.messageKey(message);
    blackhole.consume(key);
  }

  @Benchmark public void coldResumeRender(Session state, Blackhole blackhole) {
    var fixture = BenchmarkUiSupport.fixture(state.messages, 120, 800, false);
    fixture.render();
    blackhole.consume(fixture.ui().renderedText());
    blackhole.consume(fixture.ui().frozenBlocks());
  }

  @Benchmark public void warmResumeRender(Session state, Blackhole blackhole) {
    state.warm.render();
    blackhole.consume(state.warm.ui().visualHash());
  }

  @Benchmark public void boundedMidrunFrame(Session state, Blackhole blackhole) {
    state.midrun.renderAfter(100_000_000);
    blackhole.consume(state.midrun.ui().frozenRows());
    blackhole.consume(state.midrun.ui().frozenBlocks());
  }

  @Benchmark public void streamingFrame(Streaming state, Blackhole blackhole) {
    state.fixture.renderAfter(100_000_000);
    blackhole.consume(state.fixture.ui().renderedText());
    blackhole.consume(state.fixture.terminal().bytes());
  }
}
