package com.github.skanga.ajent.cli;

import com.github.skanga.ajent.core.persistence.ThreadLoadResult;
import com.github.skanga.ajent.core.persistence.ThreadStore;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.runtime.AgentState;
import com.github.skanga.ajent.terminal.JLineTerminalSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/** JMH translation of realthread_probe.cpp's load, cold resume, and warm-render paths. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class RealthreadProbeBenchmark {
  @State(Scope.Benchmark)
  public static class ThreadFile {
    /** Use {@code -p threadFile=C:\path\thread.json}; synthetic is deterministic and portable. */
    @Param("synthetic") public String threadFile;
    private Path temporaryDirectory;
    private Path path;
    private com.github.skanga.ajent.domain.Thread loaded;
    private InteractiveCommand.Ui warmUi;

    @Setup(Level.Trial) public void setup() throws IOException {
      path = "synthetic".equals(threadFile) ? syntheticThread() : Path.of(threadFile);
      ThreadLoadResult result = new ThreadStore(path.getParent()).load(path);
      if (!(result instanceof ThreadLoadResult.Success success)) {
        throw new IllegalArgumentException("failed to load thread: " + path);
      }
      loaded = success.thread();
      warmUi = ui(loaded);
      warmUi.render();
    }

    @TearDown(Level.Trial) public void tearDown() throws IOException {
      if (temporaryDirectory == null) return;
      try (var files = Files.walk(temporaryDirectory)) {
        for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(file);
        }
      }
    }

    private Path syntheticThread() throws IOException {
      temporaryDirectory = Files.createTempDirectory("ajent-realthread-benchmark-");
      var messages = new ArrayList<Message>();
      for (int turn = 0; turn < 120; turn++) {
        messages.add(new Message(Role.USER, "request " + turn, List.of(), List.of()));
        ToolUse tool = new ToolUse(new ToolCallId("tool-" + turn), new ToolName("bash"),
            Map.of("command", "inspect " + turn),
            new ToolStatus.Done(("output line " + turn + "\n").repeat(20)));
        messages.add(new Message(Role.ASSISTANT,
            "Completed tool-heavy turn " + turn + ". " + "detail ".repeat(30),
            List.of(), List.of(tool)));
      }
      var thread = new com.github.skanga.ajent.domain.Thread(
          new ThreadId("realthread-benchmark"), "benchmark", messages);
      var store = new ThreadStore(temporaryDirectory);
      if (!store.save(thread)) throw new IOException("failed to create synthetic thread");
      return temporaryDirectory.resolve("threads/realthread-benchmark.json");
    }
  }

  @Benchmark public ThreadLoadResult loadThreadFile(ThreadFile state) {
    return new ThreadStore(state.path.getParent()).load(state.path);
  }

  @Benchmark public void firstFullRender(ThreadFile state, Blackhole blackhole) {
    InteractiveCommand.Ui ui = ui(state.loaded);
    ui.render();
    blackhole.consume(ui.renderedText());
  }

  @Benchmark public void secondRenderWarm(ThreadFile state, Blackhole blackhole) {
    state.warmUi.render();
    blackhole.consume(state.warmUi.renderedText());
  }

  private static InteractiveCommand.Ui ui(com.github.skanga.ajent.domain.Thread thread) {
    return new InteractiveCommand.Ui(new BenchmarkTerminal(),
        new AtomicReference<>(AgentState.initial(thread)), new InteractiveCommand.PermissionGate());
  }

  private static final class BenchmarkTerminal implements InteractiveCommand.TerminalPort {
    @Override public JLineTerminalSession.Size size() {
      return new JLineTerminalSession.Size(200, 60);
    }

    @Override public void write(String value) { }
  }
}
