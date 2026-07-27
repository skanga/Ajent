package com.github.skanga.ajent.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.github.skanga.ajent.terminal.input.TerminalEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;

final class JLineTerminalSessionTest {
  @Test void standaloneEscapeIsDeliveredWithoutWaitingForAnotherKey() throws Exception {
    var input = new PipedInputStream();
    var producer = new PipedOutputStream(input);
    var output = new ByteArrayOutputStream();
    var session = JLineTerminalSession.forTerminal(
        new DumbTerminal("test", "xterm-256color", input, output, StandardCharsets.UTF_8));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      producer.write(0x1b);
      producer.flush();

      var read = executor.submit(session::read);

      assertThat(read.get(500, TimeUnit.MILLISECONDS))
          .containsExactly(new TerminalEvent.Key(new com.github.skanga.ajent.terminal.input.TerminalKey(
              com.github.skanga.ajent.terminal.input.TerminalKey.SpecialKey.ESCAPE,
              com.github.skanga.ajent.terminal.input.TerminalKey.Modifiers.NONE)));
    } finally {
      producer.close();
      session.close();
    }
  }

  @Test void entersAlternateScreenReadsEventsAndRestoresInReverse() throws Exception {
    byte[] input = "x\u001b[A".getBytes(StandardCharsets.UTF_8);
    var output = new ByteArrayOutputStream();
    Terminal terminal = virtual(input, output);
    var session = JLineTerminalSession.forTerminal(terminal);

    assertThat(output.toString(StandardCharsets.UTF_8))
        .startsWith("\u001b[?1049h\u001b[H\u001b[2J")
        .isEqualTo(JLineTerminalSession.ENTER_INLINE);
    var events = new java.util.ArrayList<com.github.skanga.ajent.terminal.input.TerminalEvent>();
    for (int index = 0; index < input.length; index++) events.addAll(session.read());
    assertThat(events).hasSize(2);
    session.enableMouse();
    session.enableMouse();
    session.disableMouse();
    session.disableMouse();
    session.writeSynchronized("frame");
    session.close();
    session.close();

    assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo(
        JLineTerminalSession.ENTER_INLINE
            + JLineTerminalSession.ENABLE_MOUSE + JLineTerminalSession.DISABLE_MOUSE
            + JLineTerminalSession.SYNC_START + "frame" + JLineTerminalSession.SYNC_END
            + JLineTerminalSession.LEAVE_INLINE);
    assertThat(JLineTerminalSession.LEAVE_INLINE).contains("\u001b[?1049l");
    assertThatIllegalStateException().isThrownBy(() -> session.write("late"));
  }

  @Test void reportsSizeResizeAndEofAndCloseDisablesActiveMouse() throws Exception {
    var output = new ByteArrayOutputStream();
    Terminal terminal = virtual(new byte[0], output);
    terminal.setSize(Size.of(120, 40));
    var resized = new AtomicReference<JLineTerminalSession.Size>();
    var session = JLineTerminalSession.forTerminal(terminal);
    assertThat(session.size()).isEqualTo(new JLineTerminalSession.Size(120, 40));
    session.onResize(resized::set);
    terminal.raise(Terminal.Signal.WINCH);
    assertThat(resized.get()).isEqualTo(new JLineTerminalSession.Size(120, 40));
    assertThat(session.read()).isEmpty();
    assertThat(session.flushEscape()).isEmpty();
    session.enableMouse();
    session.close();
    assertThat(output.toString(StandardCharsets.UTF_8)).endsWith(
        JLineTerminalSession.DISABLE_MOUSE + JLineTerminalSession.LEAVE_INLINE);
  }

  @Test void substitutesAUsableViewportWhenTheNativeTerminalReportsZeroSize() throws Exception {
    Terminal terminal = virtual(new byte[0], new ByteArrayOutputStream());
    terminal.setSize(new Size(0, 0));
    var session = JLineTerminalSession.forTerminal(terminal);

    assertThat(session.size()).isEqualTo(new JLineTerminalSession.Size(80, 24));

    session.close();
  }

  @Test void rejectsNegativeLogicalSizes() {
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> new JLineTerminalSession.Size(-1, 2));
  }

  @Test void parsesTheOptInFixedSizeExecutableCharacterizationSeam() {
    assertThat(JLineTerminalSession.fixedSize("")).isNull();
    assertThat(JLineTerminalSession.fixedSize(" 80X24 "))
        .isEqualTo(new JLineTerminalSession.Size(80, 24));
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> JLineTerminalSession.fixedSize("80"));
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> JLineTerminalSession.fixedSize("80x0"));
  }

  @Test void suspendLeavesInlineModeRunsActionAndRestoresRawModesAndMouse() throws Exception {
    var output = new ByteArrayOutputStream();
    var session = JLineTerminalSession.forTerminal(virtual(new byte[0], output));
    session.enableMouse();
    assertThat(session.suspend(() -> {
      session.write("cooked action\n");
      return 42;
    })).isEqualTo(42);
    assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo(
        JLineTerminalSession.ENTER_INLINE + JLineTerminalSession.ENABLE_MOUSE
            + JLineTerminalSession.DISABLE_MOUSE + JLineTerminalSession.LEAVE_INLINE
            + "cooked action\n" + JLineTerminalSession.ENTER_INLINE
            + JLineTerminalSession.ENABLE_MOUSE);
    session.close();
  }

  @Test void writesRawTeeChunksAndTemporarilyIgnoresParentInterruptSignals() throws Exception {
    var output = new ByteArrayOutputStream();
    Terminal terminal = virtual(new byte[0], output);
    var interrupts = new java.util.concurrent.atomic.AtomicInteger();
    var quits = new java.util.concurrent.atomic.AtomicInteger();
    terminal.handle(Terminal.Signal.INT, ignored -> interrupts.incrementAndGet());
    terminal.handle(Terminal.Signal.QUIT, ignored -> quits.incrementAndGet());
    var session = JLineTerminalSession.forTerminal(terminal);

    session.write("012345".getBytes(StandardCharsets.UTF_8), 2, 3);
    JLineTerminalSession.SignalGuard guard = session.ignoreInterrupts();
    terminal.raise(Terminal.Signal.INT);
    terminal.raise(Terminal.Signal.QUIT);
    assertThat(interrupts).hasValue(0);
    assertThat(quits).hasValue(0);

    guard.close();
    guard.close();
    terminal.raise(Terminal.Signal.INT);
    terminal.raise(Terminal.Signal.QUIT);
    assertThat(interrupts).hasValue(1);
    assertThat(quits).hasValue(1);
    assertThat(output.toString(StandardCharsets.UTF_8))
        .isEqualTo(JLineTerminalSession.ENTER_INLINE + "234");
    session.close();
  }

  @Test void interactiveTerminalCanReadOneRawAcknowledgementKeyWhileSuspended() throws Exception {
    var output = new ByteArrayOutputStream();
    var session = JLineTerminalSession.forTerminal(virtual(new byte[] {'z'}, output));

    assertThat(session.interactive()).isTrue();
    assertThat(session.suspend(session::readSingleKey)).isEqualTo((int) 'z');

    session.close();
  }

  private static Terminal virtual(byte[] input, ByteArrayOutputStream output) throws Exception {
    return new DumbTerminal("test", "xterm-256color", new ByteArrayInputStream(input), output,
        StandardCharsets.UTF_8);
  }
}
