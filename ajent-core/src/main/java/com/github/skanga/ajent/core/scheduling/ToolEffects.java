package com.github.skanga.ajent.core.scheduling;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class ToolEffects {
  public enum Effect { READ_FS, WRITE_FS, NET, EXEC }

  private static final Map<String, Set<Effect>> CATALOG = Map.ofEntries(
      Map.entry("read", effects(Effect.READ_FS)),
      Map.entry("edit", effects(Effect.READ_FS, Effect.WRITE_FS)),
      Map.entry("write", effects(Effect.WRITE_FS)),
      Map.entry("bash", effects(Effect.EXEC)),
      Map.entry("grep", effects(Effect.READ_FS)),
      Map.entry("glob", effects(Effect.READ_FS)),
      Map.entry("list_dir", effects(Effect.READ_FS)),
      Map.entry("todo", Set.of()),
      Map.entry("web_fetch", effects(Effect.NET)),
      Map.entry("web_search", effects(Effect.NET)),
      Map.entry("find_definition", effects(Effect.READ_FS)),
      Map.entry("diagnostics", effects(Effect.EXEC)),
      Map.entry("git_status", effects(Effect.READ_FS)),
      Map.entry("git_diff", effects(Effect.READ_FS)),
      Map.entry("git_log", effects(Effect.READ_FS)),
      Map.entry("git_commit", effects(Effect.WRITE_FS)),
      Map.entry("remember", effects(Effect.WRITE_FS)),
      Map.entry("forget", effects(Effect.WRITE_FS)),
      Map.entry("wipe_memory", effects(Effect.WRITE_FS)),
      Map.entry("task", effects(Effect.EXEC)),
      Map.entry("skill", effects(Effect.READ_FS)),
      Map.entry("search_docs", effects(Effect.READ_FS, Effect.NET)),
      Map.entry("repo_map", effects(Effect.READ_FS)));

  private ToolEffects() {}

  public static Set<Effect> permissionEffects(String toolName) {
    return CATALOG.getOrDefault(toolName, effects(Effect.EXEC));
  }

  public static Set<Effect> schedulingEffects(String toolName) {
    return "task".equals(toolName)
        ? effects(Effect.READ_FS, Effect.NET)
        : permissionEffects(toolName);
  }

  public static boolean isParallelSafe(Set<Effect> active, Set<Effect> wanted) {
    if (isExclusive(active) || isExclusive(wanted)) {
      return active.isEmpty();
    }
    return true;
  }

  private static boolean isExclusive(Set<Effect> effects) {
    return effects.contains(Effect.WRITE_FS) || effects.contains(Effect.EXEC);
  }

  private static Set<Effect> effects(Effect first, Effect... rest) {
    return Set.copyOf(EnumSet.of(first, rest));
  }
}
