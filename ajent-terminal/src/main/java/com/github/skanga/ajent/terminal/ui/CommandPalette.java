package com.github.skanga.ajent.terminal.ui;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Ajent's command catalog and immutable command-palette reducer. */
public final class CommandPalette {
  private CommandPalette() {}

  public enum Command {
    NEW_THREAD("New thread", "Start a fresh conversation"),
    REVIEW_CHANGES("Review changes", "Open diff review pane"),
    ACCEPT_ALL("Accept all changes", "Apply every pending hunk"),
    REJECT_ALL("Reject all changes", "Discard every pending hunk"),
    CYCLE_PROFILE("Cycle profile", "Write → Ask → Minimal"),
    OPEN_MODELS("Open model picker", "Switch the active model"),
    OPEN_PROVIDERS("Switch provider", "Choose the LLM backend (Anthropic, OpenAI, …)"),
    OPEN_THREADS("Open threads", "Browse saved conversations"),
    OPEN_PLAN("Open plan", "View task progress"),
    RUN_CODE_BLOCK("Run code block", "Run a fenced block from the last reply (Ctrl+G)"),
    INSPECT_TOOL_OUTPUTS("Inspect tool outputs", "Read the full output of any tool call (Ctrl+O)"),
    COMPACT_CONTEXT("Compact context", "Replace history with a structured summary"),
    REWIND_CHECKPOINT("Rewind to checkpoint", "Restore files + conversation to any earlier turn"),
    OPEN_LOGIN("Login", "Sign in via OAuth or API key"),
    QUIT("Quit", "Exit Ajent");

    private final String label;
    private final String description;

    Command(String label, String description) {
      this.label = label;
      this.description = description;
    }

    public String label() { return label; }
    public String description() { return description; }
  }

  public sealed interface State permits Closed, Open {}

  public record Closed() implements State {}

  public record Open(String query, int index) implements State {
    public Open {
      Objects.requireNonNull(query, "query");
    }

    public Open() {
      this("", 0);
    }
  }

  public record Transition(State state, Optional<Command> selected) {
    public Transition {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(selected, "selected");
    }
  }

  public static List<Command> filtered(String query) {
    Objects.requireNonNull(query, "query");
    return Arrays.stream(Command.values())
        .filter(command -> PickerState.fuzzyContains(command.label(), query))
        .toList();
  }

  public static State open() {
    return new Open();
  }

  public static State close(State state) {
    Objects.requireNonNull(state, "state");
    return new Closed();
  }

  public static State input(State state, int codePoint) {
    Objects.requireNonNull(state, "state");
    if (!(state instanceof Open open) || codePoint < 0 || codePoint >= 0x80) return state;
    return new Open(open.query() + (char) codePoint, 0);
  }

  public static State backspace(State state) {
    Objects.requireNonNull(state, "state");
    if (!(state instanceof Open open) || open.query().isEmpty()) return state;
    return new Open(open.query().substring(0, open.query().length() - 1), 0);
  }

  public static State move(State state, int delta) {
    Objects.requireNonNull(state, "state");
    if (!(state instanceof Open open)) return state;
    int size = filtered(open.query()).size();
    if (size == 0) return new Open(open.query(), 0);
    long moved = (long) open.index() + delta;
    int index = (int) Math.max(0L, Math.min(size - 1L, moved));
    return new Open(open.query(), index);
  }

  public static Transition select(State state) {
    Objects.requireNonNull(state, "state");
    if (!(state instanceof Open open)) return new Transition(state, Optional.empty());
    List<Command> matches = filtered(open.query());
    Optional<Command> selected = open.index() < 0 || open.index() >= matches.size()
        ? Optional.empty() : Optional.of(matches.get(open.index()));
    return new Transition(new Closed(), selected);
  }
}
