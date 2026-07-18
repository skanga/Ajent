package com.github.skanga.ajent.terminal.ui;

import com.github.skanga.ajent.core.workspace.WorkspaceMatcher;
import com.github.skanga.ajent.core.workspace.WorkspaceSymbol;
import com.github.skanga.ajent.domain.Attachment;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable #symbol picker over one cached workspace declaration snapshot. */
public final class SymbolPicker {
  private SymbolPicker() {}

  public sealed interface State permits Closed, Open {}
  public record Closed() implements State {}
  public record Open(List<WorkspaceSymbol> symbols, String query, int index) implements State {
    public Open {
      symbols = List.copyOf(symbols);
      query = Objects.requireNonNull(query, "query");
    }
  }
  public record Selection(State state, Optional<Attachment> attachment) {
    public Selection { attachment = Objects.requireNonNull(attachment, "attachment"); }
  }

  public static State open(List<WorkspaceSymbol> symbols) { return new Open(symbols, "", 0); }
  public static State close(State ignored) { return new Closed(); }

  public static State input(State state, int codePoint) {
    if (!(state instanceof Open open) || codePoint < 0x20 || codePoint >= 0x80) return state;
    return new Open(open.symbols(), open.query() + (char) codePoint, 0);
  }

  public static State backspace(State state) {
    if (!(state instanceof Open open)) return state;
    if (open.query().isEmpty()) return new Closed();
    return new Open(open.symbols(), open.query().substring(0, open.query().length() - 1), 0);
  }

  public static State move(State state, int delta) {
    if (!(state instanceof Open open)) return state;
    int size = matches(open).size();
    return new Open(open.symbols(), open.query(), size == 0 ? 0
        : Math.clamp(open.index() + delta, 0, size - 1));
  }

  public static Selection select(State state) {
    if (!(state instanceof Open open)) return new Selection(state, Optional.empty());
    List<Integer> matches = matches(open);
    if (open.index() < 0 || open.index() >= matches.size()) {
      return new Selection(new Closed(), Optional.empty());
    }
    WorkspaceSymbol symbol = open.symbols().get(matches.get(open.index()));
    return new Selection(new Closed(), Optional.of(new Attachment(
        Attachment.Kind.SYMBOL, new byte[0], symbol.path(), "", symbol.name(),
        symbol.lineNumber(), 0, 0)));
  }

  public static List<Integer> matches(Open open) {
    return WorkspaceMatcher.filterSymbols(open.symbols(), open.query());
  }
}
