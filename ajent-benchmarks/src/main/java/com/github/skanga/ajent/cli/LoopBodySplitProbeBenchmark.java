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

/** Java loop-body split for loop_body_split_probe.cpp's exact deep streaming shape. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class LoopBodySplitProbeBenchmark {
  @State(Scope.Thread)
  public static class Loop {
    @Param("8") public int backdropTurns;
    @Param("400") public int writeLines;
    private BenchmarkUiSupport.Fixture fixture;
    private Message live;
    private int frame;

    @Setup(Level.Iteration) public void setup() {
      var messages = new ArrayList<Message>();
      for (int turn = 0; turn < backdropTurns; turn++) {
        messages.add(BenchmarkUiSupport.user("turn " + turn + ": explain and write the file"));
        StringBuilder body = new StringBuilder();
        for (int paragraph = 0; paragraph < 6; paragraph++) {
          body.append(BenchmarkUiSupport.paragraph(paragraph));
        }
        messages.add(BenchmarkUiSupport.assistant(body.toString(), List.of(
            BenchmarkUiSupport.write("split-" + turn, writeLines,
                new ToolStatus.Done("wrote 400 lines")))));
      }
      messages.add(BenchmarkUiSupport.user(
          "now stream a very long answer explaining the whole design"));
      live = BenchmarkUiSupport.assistant("Opening the explanation.", List.of());
      messages.add(live);
      fixture = BenchmarkUiSupport.fixture(messages, 120, 50, true);
      fixture.render();
      frame = 0;
    }

    private void grow() {
      live = BenchmarkUiSupport.withText(live,
          live.text() + BenchmarkUiSupport.paragraph(frame++));
      BenchmarkUiSupport.replaceLast(fixture, live);
    }
  }

  @Benchmark public long visualHashProjection(Loop state) {
    state.grow();
    return InteractiveVisualHash.messageKey(state.live);
  }

  @Benchmark public void fullRenderGateAndWire(Loop state, Blackhole blackhole) {
    state.grow();
    state.fixture.render();
    blackhole.consume(state.fixture.ui().visualHash());
    blackhole.consume(state.fixture.terminal().bytes());
  }

  @Benchmark public void renderedFrameProjection(Loop state, Blackhole blackhole) {
    state.grow();
    state.fixture.render();
    blackhole.consume(state.fixture.ui().renderedText());
  }
}
