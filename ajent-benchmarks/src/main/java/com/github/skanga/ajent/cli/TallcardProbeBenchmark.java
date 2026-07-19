package com.github.skanga.ajent.cli;

import com.github.skanga.ajent.domain.ToolStatus;
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

/** JMH translation of tallcard_probe.cpp's terminal-card cache regimes. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class TallcardProbeBenchmark {
  @State(Scope.Benchmark)
  public static class Card {
    @Param({"300:4000", "300:200", "3000:4000", "3000:3200", "3000:200",
        "8000:9000", "8000:200"})
    public String shape;
    private int lines;
    private int height;
    private BenchmarkUiSupport.Fixture warm;

    @Setup(Level.Trial) public void setup() {
      String[] parts = shape.split(":", 2);
      lines = Integer.parseInt(parts[0]);
      height = Integer.parseInt(parts[1]);
      warm = fixture(false);
      warm.render();
    }

    private BenchmarkUiSupport.Fixture fixture(boolean running) {
      var status = running ? new ToolStatus.Running("") : new ToolStatus.Done(
          "wrote " + lines + " lines");
      return BenchmarkUiSupport.fixture(List.of(
          BenchmarkUiSupport.user("write the files"),
          BenchmarkUiSupport.assistant("Writing file 0.", List.of(
              BenchmarkUiSupport.write("call-1", lines, status)))), 120, height, running);
    }
  }

  @Benchmark public void coldTerminalCard(Card state, Blackhole blackhole) {
    BenchmarkUiSupport.Fixture fixture = state.fixture(false);
    fixture.render();
    blackhole.consume(fixture.terminal().bytes());
  }

  @Benchmark public void warmTerminalCard(Card state, Blackhole blackhole) {
    state.warm.render();
    blackhole.consume(state.warm.terminal().bytes());
  }

  @Benchmark public void streamingWriteCard(Card state, Blackhole blackhole) {
    BenchmarkUiSupport.Fixture fixture = state.fixture(true);
    fixture.render();
    blackhole.consume(fixture.terminal().bytes());
  }
}
