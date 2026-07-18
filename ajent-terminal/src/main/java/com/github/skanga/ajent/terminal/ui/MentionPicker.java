package com.github.skanga.ajent.terminal.ui;

import com.github.skanga.ajent.core.workspace.WorkspaceMatcher;
import com.github.skanga.ajent.domain.Attachment;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable @file picker over one workspace snapshot. */
public final class MentionPicker {
  private MentionPicker() {}

  public sealed interface State permits Closed, Open {}
  public record Closed() implements State {}
  public record Open(List<String> files, String query, int index) implements State {
    public Open {
      files = List.copyOf(files);
      query = Objects.requireNonNull(query, "query");
    }
  }
  public record Selection(State state, Optional<Attachment> attachment) {
    public Selection { attachment = Objects.requireNonNull(attachment, "attachment"); }
  }

  public static State open(List<String> files) { return new Open(files, "", 0); }
  public static State close(State ignored) { return new Closed(); }

  public static State input(State state, int codePoint) {
    if (!(state instanceof Open open) || codePoint < 0x20 || codePoint >= 0x80) return state;
    return new Open(open.files(), open.query() + (char) codePoint, 0);
  }

  public static State backspace(State state) {
    if (!(state instanceof Open open)) return state;
    if (open.query().isEmpty()) return new Closed();
    return new Open(open.files(), open.query().substring(0, open.query().length() - 1), 0);
  }

  public static State move(State state, int delta) {
    if (!(state instanceof Open open)) return state;
    int size = matches(open).size();
    return new Open(open.files(), open.query(), size == 0 ? 0
        : Math.clamp(open.index() + delta, 0, size - 1));
  }

  public static Selection select(State state) {
    if (!(state instanceof Open open)) return new Selection(state, Optional.empty());
    List<Integer> matches = matches(open);
    if (open.index() < 0 || open.index() >= matches.size()) {
      return new Selection(new Closed(), Optional.empty());
    }
    String path = open.files().get(matches.get(open.index()));
    return new Selection(new Closed(), Optional.of(new Attachment(
        Attachment.Kind.FILE_REF, new byte[0], path, "", "", 0, 0, 0)));
  }

  public static List<Integer> matches(Open open) {
    return WorkspaceMatcher.filterFiles(open.files(), open.query());
  }
}
