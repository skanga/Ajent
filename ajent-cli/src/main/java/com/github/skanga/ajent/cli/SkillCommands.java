package com.github.skanga.ajent.cli;

import com.github.skanga.ajent.tools.skills.SkillEngine;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;

/** Native-compatible `skills` inventory and spec-lint command. */
public final class SkillCommands {
  private final SkillEngine engine;

  public SkillCommands(Path home, Path workspace) {
    engine = new SkillEngine(Objects.requireNonNull(home, "home"),
        Objects.requireNonNull(workspace, "workspace"), null);
  }

  public static SkillCommands systemDefault() {
    return new SkillCommands(Path.of(System.getProperty("user.home")),
        Path.of("").toAbsolutePath());
  }

  public int list(PrintStream output) {
    var skills = engine.all();
    if (skills.isEmpty()) {
      output.print("no skills installed.\n"
          + "add one: <project>/.ajent/skills/<name>/SKILL.md "
          + "(or ~/.ajent/skills/, .agents/, .claude/)\n");
      return 0;
    }
    int warnings = 0;
    for (var skill : skills) {
      output.printf("%-28s %-8s %s%n", skill.name(), skill.source(), skill.directory());
      if (!skill.description().isEmpty()) output.print("    " + skill.description() + "\n");
      if (!skill.resources().isEmpty())
        output.print("    resources: " + skill.resources().size() + " file(s)\n");
      if (skill.userOnly())
        output.print("    [disable-model-invocation — hidden from catalog]\n");
      for (String diagnostic : engine.lint(skill)) {
        output.print("    warn: " + diagnostic + "\n"); warnings++;
      }
    }
    output.print(skills.size() + " skill(s), " + warnings + " warning(s)\n");
    return warnings == 0 ? 0 : 1;
  }
}
