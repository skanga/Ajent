package com.github.skanga.ajent.terminal.composer;

import com.github.skanga.ajent.terminal.input.TerminalKey;
import java.util.Objects;
import java.util.Optional;

/** Exact Ajent composer key-routing priorities, independent of terminal protocol. */
public final class ComposerKeyRouter {
  private ComposerKeyRouter() {}

  public enum Action {
    SUBMIT, NEWLINE, BACKSPACE, CURSOR_LEFT, CURSOR_RIGHT, CURSOR_WORD_LEFT,
    CURSOR_WORD_RIGHT, CURSOR_HOME, CURSOR_END, QUEUE_POP_LAST, QUEUE_PEEK_PREVIOUS,
    QUEUE_PEEK_NEXT, RECALL_QUEUED, HISTORY_PREVIOUS, HISTORY_NEXT, KILL_TO_LINE_END,
    KILL_TO_LINE_START, DELETE_WORD_BACK, DELETE_WORD_FORWARD, UNDO, REDO,
    PASTE_IMAGE
  }

  public sealed interface Result permits Result.Command, Result.Insert {
    record Command(Action action) implements Result {
      public Command { Objects.requireNonNull(action, "action"); }
    }

    record Insert(int codePoint) implements Result {
      public Insert {
        if (!Character.isValidCodePoint(codePoint)) {
          throw new IllegalArgumentException("invalid Unicode code point");
        }
      }
    }
  }

  public record State(boolean textEmpty, boolean hasQueued, boolean inHistory,
                      boolean hasHistory, boolean inQueuePeek) {}

  public static Optional<Result> route(State state, TerminalKey event) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(event, "event");
    if (event.key() instanceof TerminalKey.SpecialKey key) {
      return switch (key) {
        case ENTER -> command(event.modifiers().shift() || event.modifiers().alt()
            ? Action.NEWLINE : Action.SUBMIT);
        case BACKSPACE -> command(event.modifiers().alt() && state.textEmpty()
            && state.hasQueued() && !state.inQueuePeek()
            ? Action.QUEUE_POP_LAST : Action.BACKSPACE);
        case LEFT -> command(event.modifiers().ctrl()
            ? Action.CURSOR_WORD_LEFT : Action.CURSOR_LEFT);
        case RIGHT -> command(event.modifiers().ctrl()
            ? Action.CURSOR_WORD_RIGHT : Action.CURSOR_RIGHT);
        case HOME -> command(Action.CURSOR_HOME);
        case END -> command(Action.CURSOR_END);
        case UP -> up(state, event.modifiers());
        case DOWN -> down(state, event.modifiers());
        case ESCAPE, TAB, BACK_TAB, INSERT, DELETE, PAGE_UP, PAGE_DOWN,
             F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12 -> Optional.empty();
      };
    }
    var character = (TerminalKey.CharacterKey) event.key();
    int codePoint = character.codePoint();
    if (event.modifiers().ctrl() && !event.modifiers().alt()) {
      if (codePoint >= 1 && codePoint <= 26) codePoint = 'a' + codePoint - 1;
      Optional<Result> control = switch (codePoint) {
        case 'k' -> command(Action.KILL_TO_LINE_END);
        case 'u' -> command(Action.KILL_TO_LINE_START);
        case 'w' -> command(Action.DELETE_WORD_BACK);
        case 'z' -> command(event.modifiers().shift() ? Action.REDO : Action.UNDO);
        case 'y' -> command(Action.REDO);
        case 'v' -> command(Action.PASTE_IMAGE);
        default -> Optional.empty();
      };
      if (control.isPresent()) return control;
    }
    if (event.modifiers().alt() && !event.modifiers().ctrl()) {
      if (codePoint == 'v' || codePoint == 'V') return command(Action.PASTE_IMAGE);
      if (codePoint == 'd' || codePoint == 'D') return command(Action.DELETE_WORD_FORWARD);
    }
    return codePoint >= 0x20 ? Optional.of(new Result.Insert(codePoint)) : Optional.empty();
  }

  private static Optional<Result> up(State state, TerminalKey.Modifiers modifiers) {
    if (modifiers.alt() && state.hasQueued()) return command(Action.QUEUE_PEEK_PREVIOUS);
    if (state.textEmpty() && state.hasQueued()) return command(Action.RECALL_QUEUED);
    if (state.inHistory() || state.textEmpty() && state.hasHistory()) {
      return command(Action.HISTORY_PREVIOUS);
    }
    return Optional.empty();
  }

  private static Optional<Result> down(State state, TerminalKey.Modifiers modifiers) {
    if (modifiers.alt() && state.inQueuePeek()) return command(Action.QUEUE_PEEK_NEXT);
    return state.inHistory() ? command(Action.HISTORY_NEXT) : Optional.empty();
  }

  private static Optional<Result> command(Action action) {
    return Optional.of(new Result.Command(action));
  }
}
