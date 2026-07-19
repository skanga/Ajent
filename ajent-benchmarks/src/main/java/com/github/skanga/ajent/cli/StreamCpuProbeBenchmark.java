package com.github.skanga.ajent.cli;

import com.github.skanga.ajent.domain.Message;
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

/** JMH translation of stream_cpu_probe.cpp's deep-backdrop live-frame path. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class StreamCpuProbeBenchmark {
  @State(Scope.Thread)
  public static class Session {
    @Param({"prose", "edit-turn"}) public String shape;
    @Param("8") public int backdropTurns;
    @Param("400") public int backdropWriteLines;
    private BenchmarkUiSupport.Fixture fixture;
    private Message live;
    private int frame;

    @Setup(Level.Iteration) public void setup() {
      var messages = new ArrayList<Message>();
      for (int turn = 0; turn < backdropTurns; turn++) {
        messages.add(BenchmarkUiSupport.user("turn " + turn + ": explain and write the file"));
        StringBuilder prose = new StringBuilder();
        for (int paragraph = 0; paragraph < 6; paragraph++) {
          prose.append(BenchmarkUiSupport.paragraph(paragraph));
        }
        messages.add(BenchmarkUiSupport.assistant(prose.toString(), List.of(
            BenchmarkUiSupport.write("backdrop-" + turn, backdropWriteLines,
                new ToolStatus.Done("wrote 400 lines")))));
      }
      messages.add(BenchmarkUiSupport.user(
          "now stream a very long answer explaining the whole design"));
      live = "edit-turn".equals(shape)
          ? BenchmarkUiSupport.assistant("Applying the refactor across the modules.",
              List.of(BenchmarkUiSupport.bashRunning()))
          : BenchmarkUiSupport.assistant("Opening the explanation.", List.of());
      messages.add(live);
      fixture = BenchmarkUiSupport.fixture(messages, 80, 30, true);
      fixture.render();
      frame = 0;
    }
  }

  @Benchmark public void growingLiveFrame(Session state, Blackhole blackhole) {
    if ("edit-turn".equals(state.shape)) {
      var tools = new ArrayList<ToolUse>(state.live.toolCalls());
      if (tools.size() <= 6) {
        tools.add(tools.size() - 1, BenchmarkUiSupport.edit("live-edit-" + state.frame,
            state.frame, 120, new ToolStatus.Done("edited")));
        state.live = BenchmarkUiSupport.withTools(state.live, tools);
      }
    } else {
      state.live = BenchmarkUiSupport.withText(state.live,
          state.live.text() + BenchmarkUiSupport.paragraph(state.frame));
    }
    BenchmarkUiSupport.replaceLast(state.fixture, state.live);
    state.fixture.render();
    state.frame++;
    blackhole.consume(state.fixture.terminal().bytes());
  }
}
