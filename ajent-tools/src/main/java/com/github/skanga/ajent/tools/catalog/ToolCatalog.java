package com.github.skanga.ajent.tools.catalog;

import com.github.skanga.ajent.tools.policy.Effect;
import com.github.skanga.ajent.tools.policy.EffectSet;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Closed, recall-ordered Ajent tool catalog. */
public final class ToolCatalog {
  private static final List<ToolSpec> ALL = List.of(
      spec("read", ToolKind.READ, 1, false, 20, 80_000, TruncationStrategy.HEAD),
      spec("edit", ToolKind.EDIT, 3, true, 20, 40_000, TruncationStrategy.HEAD),
      spec("write", ToolKind.WRITE, 2, true, 20, 40_000, TruncationStrategy.HEAD),
      spec("bash", ToolKind.BASH, 8, true, 0, 30_000, TruncationStrategy.TAIL),
      spec("grep", ToolKind.GREP, 1, false, 45, 30_000, TruncationStrategy.HEAD_TAIL),
      spec("glob", ToolKind.GLOB, 1, false, 30, 25_000, TruncationStrategy.HEAD),
      spec("list_dir", ToolKind.LIST_DIR, 1, false, 20, 25_000, TruncationStrategy.HEAD),
      spec("todo", ToolKind.TODO, 0, true, 5, 0, TruncationStrategy.HEAD),
      spec("web_fetch", ToolKind.WEB_FETCH, 4, false, 30, 30_000, TruncationStrategy.HEAD),
      spec("web_search", ToolKind.WEB_SEARCH, 4, false, 20, 25_000,
          TruncationStrategy.HEAD_TAIL),
      spec("find_definition", ToolKind.FIND_DEFINITION, 1, false, 30, 25_000,
          TruncationStrategy.HEAD_TAIL),
      spec("diagnostics", ToolKind.DIAGNOSTICS, 8, false, 0, 30_000,
          TruncationStrategy.TAIL),
      spec("git_status", ToolKind.GIT_STATUS, 1, false, 20, 30_000,
          TruncationStrategy.HEAD_TAIL),
      spec("git_diff", ToolKind.GIT_DIFF, 1, false, 20, 60_000,
          TruncationStrategy.HEAD_TAIL),
      spec("git_log", ToolKind.GIT_LOG, 1, false, 20, 30_000,
          TruncationStrategy.HEAD_TAIL),
      spec("git_commit", ToolKind.GIT_COMMIT, 2, true, 30, 0, TruncationStrategy.HEAD),
      spec("remember", ToolKind.REMEMBER, 2, false, 5, 2_000, TruncationStrategy.HEAD),
      spec("forget", ToolKind.FORGET, 2, false, 5, 2_000, TruncationStrategy.HEAD),
      spec("wipe_memory", ToolKind.WIPE, 2, false, 5, 2_000, TruncationStrategy.HEAD),
      spec("task", ToolKind.TASK, 8, false, 0, 40_000, TruncationStrategy.HEAD_TAIL),
      spec("skill", ToolKind.SKILL, 1, false, 10, 64_000, TruncationStrategy.HEAD),
      spec("search_docs", ToolKind.SEARCH_DOCS, 5, false, 60, 30_000,
          TruncationStrategy.HEAD_TAIL),
      spec("repo_map", ToolKind.REPO_MAP, 1, false, 30, 60_000, TruncationStrategy.HEAD));
  private static final Map<String, ToolSpec> BY_NAME;
  private static final Map<ToolKind, ToolSpec> BY_KIND;

  static {
    var names = new LinkedHashMap<String, ToolSpec>();
    var kinds = new EnumMap<ToolKind, ToolSpec>(ToolKind.class);
    for (ToolSpec spec : ALL) {
      names.put(spec.name(), spec);
      kinds.put(spec.kind(), spec);
    }
    BY_NAME = Map.copyOf(names);
    BY_KIND = Map.copyOf(kinds);
  }

  private ToolCatalog() {}

  public static List<ToolSpec> all() { return ALL; }

  public static Optional<ToolSpec> byName(String name) { return Optional.ofNullable(BY_NAME.get(name)); }

  public static ToolSpec byKind(ToolKind kind) { return BY_KIND.get(kind); }

  public static EffectSet schedulingEffects(ToolSpec spec) {
    return spec.kind() == ToolKind.TASK ? EffectSet.of(Effect.READ_FS, Effect.NET) : spec.effects();
  }

  private static ToolSpec spec(
      String name, ToolKind kind, int effects, boolean eager, long timeoutSeconds,
      int maxOutputCharacters, TruncationStrategy strategy) {
    return new ToolSpec(name, kind, new EffectSet(effects), eager,
        Duration.ofSeconds(timeoutSeconds), maxOutputCharacters, strategy);
  }
}
