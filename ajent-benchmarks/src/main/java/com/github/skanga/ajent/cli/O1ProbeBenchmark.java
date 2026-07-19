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

/** JMH translation of o1_probe.cpp's settled, active, streaming, and resume matrices. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class O1ProbeBenchmark {
  @State(Scope.Thread)
  public static class SettledSession {
    @Param({"6:300", "6:800", "6:3000", "3:3000", "10:2000", "50:500",
        "200:500", "500:500"}) public String shape;
    private List<Message> messages;
    private BenchmarkUiSupport.Fixture warm;

    @Setup(Level.Iteration) public void setup() {
      String[] parts = shape.split(":");
      messages = SessionBenchmarkSupport.transcript(SessionBenchmarkSupport.Shape.writeShape(
          Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
      warm = BenchmarkUiSupport.fixture(messages, 120, 80, false);
      warm.render();
      warm.renderAfter(TimeUnit.HOURS.toNanos(1));
    }
  }

  @State(Scope.Thread)
  public static class ActiveEdits {
    @Param({"1", "5", "10", "20", "40", "80", "160", "320"}) public int edits;
    private BenchmarkUiSupport.Fixture fixture;
    private Message tail;
    private int frame;

    @Setup(Level.Iteration) public void setup() {
      var messages = new ArrayList<Message>();
      messages.add(BenchmarkUiSupport.user("do many edits"));
      for (int index = 0; index < edits; index++) {
        messages.add(BenchmarkUiSupport.assistant("", List.of(
            SessionBenchmarkSupport.edit("src/foo.cpp", 1))));
      }
      tail = BenchmarkUiSupport.assistant("more", List.of());
      messages.add(tail);
      fixture = BenchmarkUiSupport.fixture(messages, 120, 80, true);
      fixture.render();
      frame = 0;
    }
  }

  @State(Scope.Thread)
  public static class StreamingWrite {
    @Param({"50", "200", "800", "3000", "8000"}) public int lines;
    private BenchmarkUiSupport.Fixture fixture;

    @Setup(Level.Iteration) public void setup() {
      fixture = BenchmarkUiSupport.fixture(List.of(
          BenchmarkUiSupport.user("write a big file"),
          BenchmarkUiSupport.assistant("", List.of(SessionBenchmarkSupport.write(
              "src/foo.cpp", lines, new ToolStatus.Running("streaming"))))), 120, 80, true);
      fixture.render();
    }
  }

  @State(Scope.Thread)
  public static class StreamingText {
    @Param({"50", "200", "800", "2000", "5000"}) public int lines;
    private BenchmarkUiSupport.Fixture fixture;
    private Message live;
    private long previousBytes;

    @Setup(Level.Iteration) public void setup() {
      live = BenchmarkUiSupport.assistant(SessionBenchmarkSupport.longAnswer(lines), List.of());
      fixture = BenchmarkUiSupport.fixture(List.of(
          BenchmarkUiSupport.user("explain this in detail"), live), 120, 40, true);
      fixture.render();
      previousBytes = fixture.terminal().bytes();
    }
  }

  @State(Scope.Thread)
  public static class Resume {
    @Param({"50:5", "50:40", "200:80"}) public String shape;
    private List<Message> messages;

    @Setup(Level.Iteration) public void setup() {
      String[] parts = shape.split(":");
      int priorTurns = Integer.parseInt(parts[0]);
      int finalEdits = Integer.parseInt(parts[1]);
      messages = new ArrayList<>(SessionBenchmarkSupport.transcript(
          SessionBenchmarkSupport.Shape.writeShape(priorTurns, 40)));
      messages.add(BenchmarkUiSupport.user("do many edits"));
      for (int edit = 0; edit < finalEdits; edit++) {
        messages.add(BenchmarkUiSupport.assistant("", List.of(
            SessionBenchmarkSupport.edit("src/foo.cpp", 1))));
      }
    }
  }

  @Benchmark public void coldSettledFrame(SettledSession state, Blackhole blackhole) {
    var fixture = BenchmarkUiSupport.fixture(state.messages, 120, 80, false);
    fixture.render();
    blackhole.consume(fixture.ui().frozenRows());
  }

  @Benchmark public void warmSettledFrame(SettledSession state, Blackhole blackhole) {
    state.warm.render();
    blackhole.consume(state.warm.ui().visualHash());
  }

  @Benchmark public void growingActiveEditRun(ActiveEdits state, Blackhole blackhole) {
    state.tail = BenchmarkUiSupport.withText(state.tail, state.tail.text() + "x");
    BenchmarkUiSupport.replaceLast(state.fixture, state.tail);
    state.fixture.renderAfter(100_000_000);
    state.frame++;
    blackhole.consume(state.fixture.ui().frozenRows());
    blackhole.consume(state.fixture.terminal().bytes());
  }

  @Benchmark public void streamingWriteFrame(StreamingWrite state, Blackhole blackhole) {
    state.fixture.renderAfter(100_000_000);
    blackhole.consume(state.fixture.ui().renderedText());
  }

  @Benchmark public void streamingTextFrame(StreamingText state, Blackhole blackhole) {
    state.fixture.renderAfter(100_000_000);
    blackhole.consume(state.fixture.ui().renderedText());
  }

  @Benchmark public void streamingTextWireBytes(StreamingText state, Blackhole blackhole) {
    state.live = BenchmarkUiSupport.withText(state.live, state.live.text() + "x");
    BenchmarkUiSupport.replaceLast(state.fixture, state.live);
    state.fixture.renderAfter(100_000_000);
    long current = state.fixture.terminal().bytes();
    blackhole.consume(current - state.previousBytes);
    state.previousBytes = current;
  }

  @Benchmark public void boundedResumeFootprint(Resume state, Blackhole blackhole) {
    var fixture = BenchmarkUiSupport.fixture(state.messages, 120, 80, false);
    fixture.render();
    blackhole.consume(fixture.ui().frozenRows());
    blackhole.consume(fixture.ui().frozenBlocks());
  }
}
