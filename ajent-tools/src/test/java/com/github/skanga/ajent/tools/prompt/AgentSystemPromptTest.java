package com.github.skanga.ajent.tools.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.tools.fs.WorkspaceSandbox;
import com.github.skanga.ajent.tools.memory.JsonlMemoryStore;
import com.github.skanga.ajent.tools.memory.MemoryStore;
import com.github.skanga.ajent.tools.skills.SkillEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSystemPromptTest {
  @Test
  void hostedPromptPortsTheNativeBehaviorAndWindowsEnvironment(@TempDir Path root)
      throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Path home = Files.createDirectories(root.resolve("home"));
    var prompt = fixture(workspace, home, "Windows 11").anthropic();

    assertThat(prompt)
        .startsWith("You are ajent, a terminal coding assistant. Act, don't ask.")
        .contains("<file-editing>", "use `edit`", "`write` is for creating NEW files",
            "NEVER shell out", "<shell>", "<output-formatting>", "<context-economy>",
            "<big-codebases>", "call `repo_map` FIRST", "<in-house-languages>",
            "call `search_docs`", "<memory-tools>", "MUST call the `remember` tool",
            "  os: Windows\n", "  shell: cmd.exe (Windows Command Prompt)\n",
            "  cwd: " + workspace, "Prefer native Windows equivalents", "powershell -c")
        .endsWith("</memory-tools>\n")
        .doesNotContain("<memory>", "<skills>");
  }

  @Test
  void appendsBoundedAuthoredLearnedAndSkillMemoryInNativeOrder(@TempDir Path root)
      throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Path home = Files.createDirectories(root.resolve("home"));
    Files.writeString(home.resolve("CLAUDE.md"), "user rule");
    Files.writeString(workspace.resolve("CLAUDE.md"), "project rule");
    Files.writeString(workspace.resolve("CLAUDE.local.md"), "local rule");
    Path skill = Files.createDirectories(workspace.resolve(".agents/skills/review"));
    Files.writeString(skill.resolve("SKILL.md"),
        "---\nname: review\ndescription: Review carefully\n---\nFull review body");
    var memory = new JsonlMemoryStore(home, workspace);
    memory.append(new MemoryStore.AppendRequest(
        "Use Maven reactor builds", "project", true, List.of("build"), ""));
    var sandbox = new WorkspaceSandbox(workspace, workspace, home);
    String prompt = new AgentSystemPrompt(
        workspace, home, memory, new SkillEngine(home, workspace, sandbox), "Linux").anthropic();

    assertThat(prompt).containsSubsequence(
        "<memory>", "<user-memory>\nuser rule", "<project-memory>\nproject rule",
        "<local-memory>\nlocal rule", "<learned-memory scope=\"project\">",
        "Use Maven reactor builds", "</memory>", "<skills>", "review — Review carefully",
        "</skills>");
    assertThat(prompt).doesNotContain("Full review body");
  }

  @Test
  void rejectsOversizedClaudeMemoryAndUsesMacShellNotes(@TempDir Path root) throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Path home = Files.createDirectories(root.resolve("home"));
    Files.writeString(home.resolve("CLAUDE.md"), "x".repeat(64 * 1024 + 1));

    String prompt = fixture(workspace, home, "Darwin").anthropic();

    assertThat(prompt).contains("  os: macOS (Darwin)\n", "  shell: sh\n",
        "`sw_vers` gives macOS version").doesNotContain("x".repeat(100));
  }

  private static AgentSystemPrompt fixture(
      Path workspace, Path home, String operatingSystem) {
    var sandbox = new WorkspaceSandbox(workspace, workspace, home);
    return new AgentSystemPrompt(workspace, home, new JsonlMemoryStore(home, workspace),
        new SkillEngine(home, workspace, sandbox), operatingSystem);
  }
}
