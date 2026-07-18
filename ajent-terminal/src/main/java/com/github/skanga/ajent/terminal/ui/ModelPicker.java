package com.github.skanga.ajent.terminal.ui;

import com.github.skanga.ajent.domain.Effort;
import com.github.skanga.ajent.domain.ModelCapabilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure model-picker reducer with filtered cursor semantics. */
public final class ModelPicker {
  private static final int PAGE_ROWS = 14;

  private ModelPicker() {}

  public record Model(String id, String displayName, boolean favorite) {
    public Model {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(displayName, "displayName");
    }

    public Model withFavorite(boolean value) { return new Model(id, displayName, value); }
  }

  public enum Jump { HOME, END, PAGE_UP, PAGE_DOWN }

  public record Selection(PickerState.OneAxis state, Optional<Model> model) {
    public Selection {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(model, "model");
    }
  }

  public record FavoriteResult(PickerState.OneAxis state, List<Model> models) {
    public FavoriteResult {
      Objects.requireNonNull(state, "state");
      models = List.copyOf(models);
    }
  }

  public static PickerState.OneAxis open(List<Model> models, String activeModel) {
    Objects.requireNonNull(models, "models");
    Objects.requireNonNull(activeModel, "activeModel");
    int index = 0;
    for (int candidate = 0; candidate < models.size(); candidate++) {
      if (models.get(candidate).id().equals(activeModel)) index = candidate;
    }
    return new PickerState.OpenAt(index, "");
  }

  public static PickerState.OneAxis close(PickerState.OneAxis ignored) {
    return new PickerState.Closed();
  }

  public static PickerState.OneAxis move(
      PickerState.OneAxis state, List<Model> models, int delta) {
    Objects.requireNonNull(state, "state");
    if (!(state instanceof PickerState.OpenAt open)) return state;
    List<Integer> visible = filteredIndices(models, open.query());
    if (visible.isEmpty()) return state;
    return new PickerState.OpenAt(Math.floorMod(open.index() + delta, visible.size()), open.query());
  }

  public static PickerState.OneAxis jump(
      PickerState.OneAxis state, List<Model> models, Jump where) {
    Objects.requireNonNull(where, "where");
    if (!(state instanceof PickerState.OpenAt open)) return state;
    int size = filteredIndices(models, open.query()).size();
    if (size == 0) return state;
    int index = switch (where) {
      case HOME -> 0;
      case END -> size - 1;
      case PAGE_UP -> Math.max(0, open.index() - PAGE_ROWS);
      case PAGE_DOWN -> Math.min(size - 1, open.index() + PAGE_ROWS);
    };
    return new PickerState.OpenAt(index, open.query());
  }

  public static PickerState.OneAxis input(
      PickerState.OneAxis state, List<Model> models, int codePoint) {
    if (!(state instanceof PickerState.OpenAt open)) return state;
    String query = open.query() + new String(Character.toChars(codePoint));
    int size = filteredIndices(models, query).size();
    int index = size == 0 ? 0 : Math.max(0, Math.min(size - 1, open.index()));
    return new PickerState.OpenAt(index, query);
  }

  public static PickerState.OneAxis backspace(
      PickerState.OneAxis state, List<Model> models) {
    if (!(state instanceof PickerState.OpenAt open) || open.query().isEmpty()) return state;
    int end = open.query().offsetByCodePoints(open.query().length(), -1);
    String query = open.query().substring(0, end);
    int size = filteredIndices(models, query).size();
    int index = size == 0 ? 0 : Math.max(0, Math.min(size - 1, open.index()));
    return new PickerState.OpenAt(index, query);
  }

  public static Selection select(PickerState.OneAxis state, List<Model> models) {
    Optional<Model> selected = selected(state, models);
    return new Selection(new PickerState.Closed(), selected);
  }

  public static FavoriteResult toggleFavorite(PickerState.OneAxis state, List<Model> models) {
    Objects.requireNonNull(models, "models");
    if (!(state instanceof PickerState.OpenAt open)) return new FavoriteResult(state, models);
    List<Integer> visible = filteredIndices(models, open.query());
    if (open.index() < 0 || open.index() >= visible.size()) return new FavoriteResult(state, models);
    int realIndex = visible.get(open.index());
    var changed = new ArrayList<>(models);
    Model model = changed.get(realIndex);
    changed.set(realIndex, model.withFavorite(!model.favorite()));
    return new FavoriteResult(state, changed);
  }

  public static Effort cycleEffort(
      PickerState.OneAxis state, List<Model> models, Effort current, int delta) {
    return selected(state, models)
        .map(model -> current.cycle(delta, ModelCapabilities.fromId(model.id())))
        .orElse(current);
  }

  public static List<Integer> filteredIndices(List<Model> models, String query) {
    Objects.requireNonNull(models, "models");
    Objects.requireNonNull(query, "query");
    var indices = new ArrayList<Integer>();
    for (int index = 0; index < models.size(); index++) {
      if (PickerState.fuzzyContains(models.get(index).displayName(), query)) indices.add(index);
    }
    return List.copyOf(indices);
  }

  private static Optional<Model> selected(PickerState.OneAxis state, List<Model> models) {
    if (!(state instanceof PickerState.OpenAt open)) return Optional.empty();
    List<Integer> visible = filteredIndices(models, open.query());
    return open.index() < 0 || open.index() >= visible.size() ? Optional.empty()
        : Optional.of(models.get(visible.get(open.index())));
  }
}
