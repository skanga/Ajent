package com.github.skanga.ajent.terminal.ui;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure provider picker, including Ajent's final virtual custom-host row. */
public final class ProviderPicker {
  private static final int PAGE_ROWS = 14;

  private ProviderPicker() {}

  public record Provider(String id, String label) {
    public Provider {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(label, "label");
    }
  }

  public enum Jump { HOME, END, PAGE_UP, PAGE_DOWN }

  public sealed interface Action permits SelectProvider, EnterCustomHost {}
  public record SelectProvider(Provider provider) implements Action {
    public SelectProvider { Objects.requireNonNull(provider, "provider"); }
  }
  public record EnterCustomHost() implements Action {}

  public record Selection(PickerState.OneAxis state, Optional<Action> action) {
    public Selection {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(action, "action");
    }
  }

  public static PickerState.OneAxis open(List<Provider> providers, String activeLabel) {
    Objects.requireNonNull(providers, "providers");
    Objects.requireNonNull(activeLabel, "activeLabel");
    int index = 0;
    for (int candidate = 0; candidate < providers.size(); candidate++) {
      if (providers.get(candidate).id().equals(activeLabel)) index = candidate;
    }
    return new PickerState.OpenAt(index, "");
  }

  public static PickerState.OneAxis move(
      PickerState.OneAxis state, List<Provider> providers, int delta) {
    if (!(state instanceof PickerState.OpenAt open)) return state;
    int size = providers.size() + 1;
    return new PickerState.OpenAt(Math.floorMod(open.index() + delta, size), open.query());
  }

  public static PickerState.OneAxis jump(
      PickerState.OneAxis state, List<Provider> providers, Jump where) {
    Objects.requireNonNull(where, "where");
    if (!(state instanceof PickerState.OpenAt open)) return state;
    int size = providers.size() + 1;
    int index = switch (where) {
      case HOME -> 0;
      case END -> size - 1;
      case PAGE_UP -> Math.max(0, open.index() - PAGE_ROWS);
      case PAGE_DOWN -> Math.min(size - 1, open.index() + PAGE_ROWS);
    };
    return new PickerState.OpenAt(index, open.query());
  }

  public static Selection select(PickerState.OneAxis state, List<Provider> providers) {
    Objects.requireNonNull(providers, "providers");
    if (!(state instanceof PickerState.OpenAt open)) return new Selection(state, Optional.empty());
    int size = providers.size() + 1;
    Optional<Action> action;
    if (open.index() < 0 || open.index() >= size) action = Optional.empty();
    else if (open.index() == providers.size()) action = Optional.of(new EnterCustomHost());
    else action = Optional.of(new SelectProvider(providers.get(open.index())));
    return new Selection(new PickerState.Closed(), action);
  }
}
