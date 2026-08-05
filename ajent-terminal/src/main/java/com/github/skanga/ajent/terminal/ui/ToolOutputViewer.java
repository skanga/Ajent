package com.github.skanga.ajent.terminal.ui;

import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.ToolUse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Ajent's bounded, two-stage tool-output inspector state machine. */
public final class ToolOutputViewer {
  public static final int MAX_ENTRIES = 50;
  public static final int SNAPSHOT_BUDGET_BYTES = 4 * 1024 * 1024;

  private ToolOutputViewer() {}

  public record Metadata(String title, String detail) {
    public Metadata {
      Objects.requireNonNull(title, "title");
      Objects.requireNonNull(detail, "detail");
    }
  }

  public record Entry(
      String name, String title, String detail, String trailing, String output,
      boolean failed, ToolUse call) {
    public Entry {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(title, "title");
      Objects.requireNonNull(detail, "detail");
      Objects.requireNonNull(trailing, "trailing");
      Objects.requireNonNull(output, "output");
      Objects.requireNonNull(call, "call");
    }
  }

  public sealed interface State permits Closed, Open {}
  public record Closed() implements State {}

  public record Open(List<Entry> entries, int index, boolean viewing, int scrollY, int maxScrollY)
      implements State {
    public Open {
      entries = List.copyOf(entries);
      if (scrollY < 0 || maxScrollY < 0) throw new IllegalArgumentException("negative scroll");
    }
  }

  public record Transition(State state, String status, Optional<String> clipboard) {
    public Transition {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(clipboard, "clipboard");
    }

    public Transition(State state) { this(state, "", Optional.empty()); }
  }

  public static Transition open(List<Entry> entries) {
    Objects.requireNonNull(entries, "entries");
    return entries.isEmpty()
        ? new Transition(new Closed(), "no tool outputs to inspect yet", Optional.empty())
        : new Transition(new Open(entries, 0, false, 0, 0));
  }

  public static State close(State state) {
    Objects.requireNonNull(state, "state");
    if (state instanceof Open open && open.viewing()) {
      return new Open(open.entries(), open.index(), false, 0, open.maxScrollY());
    }
    return new Closed();
  }

  public static State withMaxScroll(State state, int maxScrollY) {
    if (!(state instanceof Open open)) return state;
    int maximum = Math.max(0, maxScrollY);
    return new Open(open.entries(), open.index(), open.viewing(),
        Math.min(open.scrollY(), maximum), maximum);
  }

  public static State move(State state, int delta) {
    if (!(state instanceof Open open)) return state;
    if (open.viewing()) {
      long target = (long) open.scrollY() + delta;
      int scroll = (int) Math.max(0, Math.min(open.maxScrollY(), target));
      return new Open(open.entries(), open.index(), true, scroll, open.maxScrollY());
    }
    if (open.entries().isEmpty()) return state;
    long target = (long) open.index() + delta;
    int index = (int) Math.max(0, Math.min(open.entries().size() - 1L, target));
    return new Open(open.entries(), index, false, open.scrollY(), open.maxScrollY());
  }

  public static State select(State state) {
    if (!(state instanceof Open open) || open.viewing()
        || open.index() < 0 || open.index() >= open.entries().size()) return state;
    return new Open(open.entries(), open.index(), true, 0, open.maxScrollY());
  }

  public static State step(State state, int delta) {
    if (!(state instanceof Open open) || !open.viewing() || open.entries().isEmpty()) return state;
    long target = (long) open.index() + delta;
    int index = (int) Math.max(0, Math.min(open.entries().size() - 1L, target));
    return index == open.index() ? state
        : new Open(open.entries(), index, true, 0, open.maxScrollY());
  }

  public static Transition copy(State state) {
    if (!(state instanceof Open open)
        || open.index() < 0 || open.index() >= open.entries().size()) {
      return new Transition(state);
    }
    return new Transition(state, "tool output copied to clipboard",
        Optional.of(open.entries().get(open.index()).output()));
  }

  public static List<Entry> collect(
      List<Message> messages, Function<ToolUse, Metadata> metadata) {
    Objects.requireNonNull(messages, "messages");
    Objects.requireNonNull(metadata, "metadata");
    var entries = new ArrayList<Entry>();
    int budget = SNAPSHOT_BUDGET_BYTES;
    for (int messageIndex = messages.size() - 1; messageIndex >= 0; messageIndex--) {
      List<ToolUse> calls = messages.get(messageIndex).toolCalls();
      for (int callIndex = calls.size() - 1; callIndex >= 0; callIndex--) {
        ToolUse call = calls.get(callIndex);
        if (!call.status().isTerminal()) continue;
        String output = call.status().output();
        if (output.isEmpty()) continue;
        if (entries.size() >= MAX_ENTRIES) return List.copyOf(entries);
        int bytes = output.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > budget) continue;
        budget -= bytes;
        Metadata labels = Objects.requireNonNull(metadata.apply(call), "metadata result");
        boolean failed = call.status().isError();
        entries.add(new Entry(call.name().value(), labels.title(), labels.detail(),
            trailing(call, failed, bytes), output, failed, call));
      }
    }
    return List.copyOf(entries);
  }

  private static String trailing(ToolUse call, boolean failed, int bytes) {
    var value = new StringBuilder(failed ? "failed" : "ok");
    long elapsedNanos = call.status().finishedNanos() - call.status().startedNanos();
    if (elapsedNanos >= 50_000_000L) {
      value.append(" · ").append(String.format(java.util.Locale.ROOT, "%.1fs",
          elapsedNanos / 1_000_000_000.0));
    }
    value.append(" · ");
    if (bytes >= 1024) value.append(bytes / 1024).append(" KB");
    else value.append(bytes).append(" B");
    return value.toString();
  }
}
