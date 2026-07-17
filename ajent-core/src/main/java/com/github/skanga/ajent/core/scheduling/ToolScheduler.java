package com.github.skanga.ajent.core.scheduling;

import com.github.skanga.ajent.core.scheduling.ToolEffects.Effect;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ToolScheduler {
  public record Decision(List<Integer> promote) {
    public Decision {
      promote = List.copyOf(promote);
    }
  }

  private ToolScheduler() {}

  public static Decision scheduleParallelBatch(List<ToolUse> batch) {
    var activeEffects = EnumSet.noneOf(Effect.class);
    var activePaths = new ArrayList<String>();
    var activeUnbounded = new boolean[] {false};

    for (var call : batch) {
      if (call.status() instanceof ToolStatus.Running) {
        note(call, activeEffects, activePaths, activeUnbounded);
      }
    }

    var promoted = new ArrayList<Integer>();
    for (int index = 0; index < batch.size(); index++) {
      var call = batch.get(index);
      if (!(call.status() instanceof ToolStatus.Pending)
          && !(call.status() instanceof ToolStatus.Approved)) {
        continue;
      }
      var wanted = ToolEffects.schedulingEffects(call.name().value());
      if (!canRun(call, wanted, activeEffects, activePaths, activeUnbounded[0])) {
        continue;
      }
      note(call, activeEffects, activePaths, activeUnbounded);
      promoted.add(index);
    }
    return new Decision(promoted);
  }

  private static boolean canRun(
      ToolUse call,
      Set<Effect> wanted,
      Set<Effect> activeEffects,
      List<String> activePaths,
      boolean activeUnbounded) {
    if (ToolEffects.isParallelSafe(activeEffects, wanted)) {
      return true;
    }
    if (wanted.contains(Effect.EXEC) || activeUnbounded) {
      return false;
    }
    var paths = paths(call);
    if (paths.isEmpty()) {
      return false;
    }
    return paths.stream().noneMatch(candidate ->
        activePaths.stream().anyMatch(active -> pathsOverlap(candidate, active)));
  }

  private static void note(
      ToolUse call,
      Set<Effect> activeEffects,
      List<String> activePaths,
      boolean[] activeUnbounded) {
    var effects = ToolEffects.schedulingEffects(call.name().value());
    activeEffects.addAll(effects);
    var paths = paths(call);
    if (effects.contains(Effect.EXEC)
        || (effects.contains(Effect.WRITE_FS) && paths.isEmpty())) {
      activeUnbounded[0] = true;
    }
    activePaths.addAll(paths);
  }

  private static List<String> paths(ToolUse call) {
    var paths = new ArrayList<String>();
    takePath(call.arguments(), "path", paths);
    takePath(call.arguments(), "file_path", paths);
    takePath(call.arguments(), "filepath", paths);
    takePath(call.arguments(), "filename", paths);
    var name = call.name().value();
    if ("grep".equals(name) || "glob".equals(name) || "list_dir".equals(name)) {
      takePath(call.arguments(), "dir", paths);
      takePath(call.arguments(), "directory", paths);
      takePath(call.arguments(), "root", paths);
    }
    return paths;
  }

  private static void takePath(Map<String, Object> arguments, String key, List<String> paths) {
    if (arguments.get(key) instanceof String path && !path.isEmpty()) {
      paths.add(path);
    }
  }

  static boolean pathsOverlap(String first, String second) {
    if (first.equals(second)) {
      return true;
    }
    return first.length() < second.length()
        ? isUnder(first, second)
        : isUnder(second, first);
  }

  private static boolean isUnder(String shorter, String longer) {
    return longer.length() > shorter.length()
        && longer.startsWith(shorter)
        && (shorter.endsWith("/") || longer.charAt(shorter.length()) == '/');
  }
}
