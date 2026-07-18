package com.github.skanga.ajent.terminal;

import com.github.skanga.ajent.terminal.input.TerminalEvent;
import com.github.skanga.ajent.terminal.input.TerminalInputDecoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/** Owns Ajent's JLine raw inline-terminal lifecycle and exact mode escapes. */
public final class JLineTerminalSession implements AutoCloseable {
  public static final String ENTER_INLINE =
      "\u001b[?2004h\u001b[>1u\u001b[>4;2m\u001b[?25l";
  public static final String LEAVE_INLINE =
      "\u001b[>4m\u001b[<u\u001b[?2004l\u001b[?7h\u001b[?25h\u001b[0m\r\n";
  public static final String ENABLE_MOUSE =
      "\u001b[?1000h\u001b[?1002h\u001b[?1006h\u001b[?1007h\u001b[?1004h";
  public static final String DISABLE_MOUSE =
      "\u001b[?1004l\u001b[?1007l\u001b[?1006l\u001b[?1002l\u001b[?1000l";
  public static final String SYNC_START = "\u001b[?2026h";
  public static final String SYNC_END = "\u001b[?2026l";

  public record Size(int columns, int rows) {
    public Size {
      if (columns < 0 || rows < 0) throw new IllegalArgumentException("negative terminal size");
    }
  }

  @FunctionalInterface
  public interface SignalGuard extends AutoCloseable {
    @Override void close();
  }

  private final Terminal terminal;
  private final Attributes cooked;
  private final TerminalInputDecoder decoder = new TerminalInputDecoder();
  private boolean mouse;
  private boolean closed;

  private JLineTerminalSession(Terminal terminal) {
    this.terminal = Objects.requireNonNull(terminal, "terminal");
    cooked = terminal.enterRawMode();
    write(ENTER_INLINE);
  }

  public static JLineTerminalSession open() throws IOException {
    Terminal terminal = TerminalBuilder.builder().name("ajent").system(true)
        .encoding(StandardCharsets.UTF_8).ffm(true).dumb(true).build();
    try {
      return new JLineTerminalSession(terminal);
    } catch (RuntimeException exception) {
      terminal.close();
      throw exception;
    }
  }

  static JLineTerminalSession forTerminal(Terminal terminal) {
    return new JLineTerminalSession(terminal);
  }

  public Size size() {
    return new Size(Math.max(0, terminal.getWidth()), Math.max(0, terminal.getHeight()));
  }

  public Terminal.SignalHandler onResize(Consumer<Size> handler) {
    Objects.requireNonNull(handler, "handler");
    return terminal.handle(Terminal.Signal.WINCH, ignored -> handler.accept(size()));
  }

  public void enableMouse() {
    requireOpen();
    if (mouse) return;
    write(ENABLE_MOUSE);
    mouse = true;
  }

  public void disableMouse() {
    requireOpen();
    if (!mouse) return;
    write(DISABLE_MOUSE);
    mouse = false;
  }

  public List<TerminalEvent> read() throws IOException {
    requireOpen();
    byte[] bytes = new byte[4096];
    int count = terminal.input().read(bytes);
    return count < 0 ? List.of() : decoder.feed(java.util.Arrays.copyOf(bytes, count));
  }

  public List<TerminalEvent> flushEscape() {
    requireOpen();
    return decoder.flushEscape();
  }

  public void write(String value) {
    byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
    write(bytes, 0, bytes.length);
  }

  /** Writes one subprocess-output chunk without decoding and re-encoding it. */
  public void write(byte[] bytes, int offset, int length) {
    requireOpen();
    Objects.checkFromIndexSize(offset, length, Objects.requireNonNull(bytes, "bytes").length);
    try {
      terminal.output().write(bytes, offset, length);
      terminal.output().flush();
    } catch (IOException exception) {
      throw new java.io.UncheckedIOException(exception);
    }
  }

  /** Ignores parent INT/QUIT until the returned idempotent guard is closed. */
  public SignalGuard ignoreInterrupts() {
    requireOpen();
    Terminal.SignalHandler previousInterrupt =
        terminal.handle(Terminal.Signal.INT, Terminal.SignalHandler.SIG_IGN);
    final Terminal.SignalHandler previousQuit;
    try {
      previousQuit = terminal.handle(Terminal.Signal.QUIT, Terminal.SignalHandler.SIG_IGN);
    } catch (RuntimeException exception) {
      terminal.handle(Terminal.Signal.INT, previousInterrupt);
      throw exception;
    }
    var restored = new AtomicBoolean();
    return () -> {
      if (!restored.compareAndSet(false, true)) return;
      terminal.handle(Terminal.Signal.QUIT, previousQuit);
      terminal.handle(Terminal.Signal.INT, previousInterrupt);
    };
  }

  public void writeSynchronized(String frame) {
    write(SYNC_START + Objects.requireNonNull(frame, "frame") + SYNC_END);
  }

  /** Temporarily restores the host terminal for a user-requested local interactive action. */
  public <T> T suspend(Supplier<T> action) {
    requireOpen();
    Objects.requireNonNull(action, "action");
    boolean restoreMouse = mouse;
    if (mouse) disableMouse();
    write(LEAVE_INLINE);
    terminal.setAttributes(cooked);
    try {
      return action.get();
    } finally {
      terminal.enterRawMode();
      write(ENTER_INLINE);
      if (restoreMouse) enableMouse();
    }
  }

  @Override public void close() throws IOException {
    if (closed) return;
    try {
      if (mouse) {
        terminal.output().write(DISABLE_MOUSE.getBytes(StandardCharsets.UTF_8));
        mouse = false;
      }
      terminal.output().write(LEAVE_INLINE.getBytes(StandardCharsets.UTF_8));
      terminal.output().flush();
      terminal.setAttributes(cooked);
    } finally {
      closed = true;
      terminal.close();
    }
  }

  private void requireOpen() {
    if (closed) throw new IllegalStateException("terminal session is closed");
  }
}
