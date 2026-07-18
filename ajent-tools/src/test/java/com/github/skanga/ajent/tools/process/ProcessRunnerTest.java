package com.github.skanga.ajent.tools.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProcessRunnerTest {
  @Test
  void outputActivityKeepsALongRunningChildAlivePastTheWallClockWindow(@TempDir Path root) {
    var snapshots = new ArrayList<String>();
    ProcessRunner.Result result = new ProcessRunner().argvWithProgress(
        javaCommand("active"), root, 8_192,
        Duration.ofMillis(350), snapshots::add);

    assertThat(result.started()).isTrue();
    assertThat(result.timedOut()).isFalse();
    assertThat(result.exitCode()).isZero();
    assertThat(result.output()).contains("tick-0", "tick-4");
    assertThat(snapshots).hasSizeGreaterThan(1);
    assertThat(snapshots.getLast()).isEqualTo(result.output());
    assertThat(snapshots).anySatisfy(snapshot ->
        assertThat(snapshot).contains("tick-0").doesNotContain("tick-4"));
  }

  @Test
  void silenceTriggersTheIdleWatchdogAfterPreviouslyCapturedOutput(@TempDir Path root) {
    ProcessRunner.Result result = new ProcessRunner().argv(javaCommand("idle"), root, 8_192,
        Duration.ofMillis(250));

    assertThat(result.started()).isTrue();
    assertThat(result.timedOut()).isTrue();
    assertThat(result.output()).contains("before-idle");
  }

  private static List<String> javaCommand(String mode) {
    String executable = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java")
        .toString();
    return List.of(executable, "-cp", System.getProperty("java.class.path"),
        Emitter.class.getName(), mode);
  }

  public static final class Emitter {
    public static void main(String[] arguments) throws Exception {
      if (arguments[0].equals("active")) {
        for (int index = 0; index < 5; index++) {
          System.out.println("tick-" + index);
          System.out.flush();
          java.lang.Thread.sleep(180);
        }
      } else {
        System.out.println("before-idle");
        System.out.flush();
        java.lang.Thread.sleep(5_000);
      }
    }
  }
}
