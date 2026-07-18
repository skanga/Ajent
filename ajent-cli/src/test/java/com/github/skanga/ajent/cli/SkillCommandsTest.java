package com.github.skanga.ajent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SkillCommandsTest {
  @TempDir Path base;

  @Test void explainsHowToInstallWhenNoSkillsExist() throws IOException {
    Path home = Files.createDirectories(base.resolve("home"));
    Path workspace = Files.createDirectories(base.resolve("work"));
    Execution result = run(new SkillCommands(home, workspace));
    assertThat(result.code()).isZero();
    assertThat(result.output()).isEqualTo("no skills installed.\n"
        + "add one: <project>/.agentty/skills/<name>/SKILL.md "
        + "(or ~/.agentty/skills/, .agents/, .claude/)\n");
  }

  @Test void listsMetadataResourcesVisibilityAndReturnsOneOnWarnings() throws IOException {
    Path home = Files.createDirectories(base.resolve("home"));
    Path workspace = Files.createDirectories(base.resolve("work"));
    Path clean = workspace.resolve(".agentty/skills/clean");
    write(clean.resolve("SKILL.md"), "---\nname: clean\ndescription: clean skill\n"
        + "disable-model-invocation: true\n---\nBODY\n");
    write(clean.resolve("references/info.md"), "info");
    Path bad = home.resolve(".agentty/skills/different");
    write(bad.resolve("SKILL.md"), "---\nname: Bad--Name-\n---\nBODY\n");

    Execution result = run(new SkillCommands(home, workspace));
    assertThat(result.code()).isEqualTo(1);
    assertThat(result.output()).contains(
        "clean                        project", clean.toRealPath().toString(),
        "    clean skill\n", "    resources: 1 file(s)\n",
        "    [disable-model-invocation — hidden from catalog]\n",
        "Bad--Name-", "user", "    warn: name has invalid characters",
        "2 skill(s)").contains("warning(s)\n");
  }

  private static Execution run(SkillCommands commands) {
    var bytes = new ByteArrayOutputStream();
    int code = commands.list(new PrintStream(bytes, true, StandardCharsets.UTF_8));
    return new Execution(code, bytes.toString(StandardCharsets.UTF_8));
  }
  private static void write(Path path, String body) throws IOException {
    Files.createDirectories(path.getParent()); Files.writeString(path, body);
  }
  private record Execution(int code, String output) {}
}
