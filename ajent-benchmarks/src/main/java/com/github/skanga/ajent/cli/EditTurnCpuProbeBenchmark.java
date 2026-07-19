package com.github.skanga.ajent.cli;

import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
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

/** JMH translation of edit_turn_cpu_probe.cpp's settled/live/streaming edit matrix. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class EditTurnCpuProbeBenchmark {
  @State(Scope.Benchmark)
  public static class Turn {
    @Param({"settled", "one-running", "streaming-edit"}) public String scenario;
    @Param({"40", "60", "100", "200", "400", "1000", "6000"}) public int height;
    @Param("6") public int edits;
    @Param("80") public int linesPerHunk;
    private BenchmarkUiSupport.Fixture fixture;

    @Setup(Level.Trial) public void setup() {
      var tools = new ArrayList<ToolUse>();
      for (int index = 0; index < edits; index++) {
        boolean streaming = "streaming-edit".equals(scenario) && index == edits - 1;
        tools.add(BenchmarkUiSupport.edit("edit-" + index, index, linesPerHunk,
            streaming ? new ToolStatus.Running("") : new ToolStatus.Done("edited")));
      }
      if ("one-running".equals(scenario)) tools.add(BenchmarkUiSupport.bashRunning());
      boolean active = !"settled".equals(scenario);
      fixture = BenchmarkUiSupport.fixture(List.of(
          BenchmarkUiSupport.user("refactor these modules"),
          BenchmarkUiSupport.assistant("Applying the refactor across the modules.", tools)),
          120, height, active);
      fixture.render();
      fixture.render();
    }
  }

  @Benchmark public void rebuildFrame(Turn state, Blackhole blackhole) {
    state.fixture.render();
    blackhole.consume(state.fixture.terminal().bytes());
  }
}
