package com.github.skanga.ajent.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.github.skanga.ajent.terminal.input.TerminalEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;

final class JLineTerminalSessionTest {
  @Test void entersRawInlineModesReadsEventsAndRestoresInReverse() throws Exception {
    byte[] input = "x\u001b[A".getBytes(StandardCharsets.UTF_8);
    var output = new ByteArrayOutputStream();
    Terminal terminal = virtual(input, output);
    var session = JLineTerminalSession.forTerminal(terminal);

    assertThat(output.toString(StandardCharsets.UTF_8))
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
    assertThatIllegalStateException().isThrownBy(() -> session.write("late"));
  }

  @Test void reportsSizeResizeAndEofAndCloseDisablesActiveMouse() throws Exception {
    var output = new ByteArrayOutputStream();
    Terminal terminal = virtual(new byte[0], output);
    terminal.setSize(new Size(120, 40));
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

  @Test void rejectsNegativeLogicalSizes() {
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> new JLineTerminalSession.Size(-1, 2));
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

  private static Terminal virtual(byte[] input, ByteArrayOutputStream output) throws Exception {
    return new DumbTerminal("test", "xterm-256color", new ByteArrayInputStream(input), output,
        StandardCharsets.UTF_8);
  }
}
