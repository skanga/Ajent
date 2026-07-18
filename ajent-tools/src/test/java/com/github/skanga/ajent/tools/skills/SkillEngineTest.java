package com.github.skanga.ajent.tools.skills;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.tools.fs.WorkspaceSandbox;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SkillEngineTest {
  @TempDir Path base;
  private Path home;
  private Path workspace;
  private WorkspaceSandbox sandbox;
  private SkillEngine engine;

  @BeforeEach void setUp() throws IOException {
    home = Files.createDirectories(base.resolve("home"));
    workspace = Files.createDirectories(base.resolve("work"));
    sandbox = new WorkspaceSandbox(workspace, workspace, home);
    engine = new SkillEngine(home, workspace, sandbox);
  }

  @Test void discoversSixRootsWithProjectAndNativePrecedence() throws IOException {
    assertThat(engine.all()).isEmpty();
    assertThat(engine.catalogBlock()).isEmpty();
    skill(home.resolve(".agentty/skills/alpha"), "alpha", "user native alpha", "USER BODY");
    skill(home.resolve(".agents/skills/alpha"), "alpha", "interop alpha", "INTEROP BODY");
    skill(home.resolve(".claude/skills/claude-only"), "claude-only", "from claude dir", "CC BODY");
    skill(workspace.resolve(".agentty/skills/alpha"), "alpha", "project alpha", "PROJECT BODY");
    skill(workspace.resolve(".agents/skills/beta"), "beta", "project interop beta", "BETA BODY");

    assertThat(engine.find("alpha")).hasValueSatisfying(skill -> {
      assertThat(skill.source()).isEqualTo("project");
      assertThat(skill.body()).isEqualTo("PROJECT BODY");
      assertThat(skill.directory()).isAbsolute();
    });
    assertThat(engine.find("claude-only")).isPresent();
    assertThat(engine.find("beta")).isPresent();
  }

  @Test void parsesLenientFrontmatterMetadataAndBlockScalars() throws IOException {
    write(workspace.resolve(".agentty/skills/colons/SKILL.md"),
        "---\nname: colons\ndescription: Use when: things have colons\n---\nB\n");
    write(workspace.resolve(".agentty/skills/dirname-x/SKILL.md"),
        "---\nname: othername\ndescription: mismatch ok\n---\nB\n");
    write(workspace.resolve(".agentty/skills/bare/SKILL.md"),
        "Just a bare instruction doc.\nMore text.\n");
    write(workspace.resolve(".agentty/skills/full-meta/SKILL.md"), "---\nname: full-meta\n"
        + "description: has every optional field\ncompatibility: Requires python3\n"
        + "allowed-tools: bash read\nlicense: Apache-2.0\nmetadata:\n"
        + "  author: example-org\n  version: \"1.0\"\n---\nMETA BODY\n");
    write(workspace.resolve(".agentty/skills/folded/SKILL.md"),
        "---\nname: folded\ndescription: >-\n  First folded line\n  second folded line\n---\nFOLD BODY\n");
    write(workspace.resolve(".agentty/skills/literal/SKILL.md"),
        "---\nname: literal\ndescription: |\n  First literal line\n  second literal line\n"
            + "disable-model-invocation: yes\n---\nLITERAL BODY\n");

    assertThat(engine.find("colons").orElseThrow().description())
        .isEqualTo("Use when: things have colons");
    assertThat(engine.find("othername")).isPresent();
    assertThat(engine.find("dirname-x")).isEmpty();
    assertThat(engine.lint(engine.find("othername").orElseThrow()))
        .anyMatch(message -> message.contains("does not match parent directory"));
    assertThat(engine.find("bare").orElseThrow().description())
        .isEqualTo("Just a bare instruction doc.");
    Skill full = engine.find("full-meta").orElseThrow();
    assertThat(full.compatibility()).isEqualTo("Requires python3");
    assertThat(full.allowedTools()).isEqualTo("bash read");
    assertThat(full.license()).isEqualTo("Apache-2.0");
    assertThat(full.metadata()).containsExactly(new Skill.Metadata("author", "example-org"),
        new Skill.Metadata("version", "1.0"));
    assertThat(engine.find("folded").orElseThrow()).satisfies(skill -> {
      assertThat(skill.description()).isEqualTo("First folded line second folded line");
      assertThat(skill.body()).isEqualTo("FOLD BODY");
    });
    assertThat(engine.find("literal").orElseThrow()).satisfies(skill -> {
      assertThat(skill.description()).isEqualTo("First literal line\nsecond literal line");
      assertThat(skill.userOnly()).isTrue();
    });
  }

  @Test void hidesUserOnlySkillsFromCatalogButAllowsExplicitActivation() throws IOException {
    skill(workspace.resolve(".agentty/skills/visible"), "visible", "shown", "VISIBLE BODY");
    write(workspace.resolve(".agentty/skills/hidden/SKILL.md"),
        "---\nname: hidden\ndescription: user-explicit only\n"
            + "disable-model-invocation: true\n---\nHIDDEN BODY\n");
    assertThat(engine.find("hidden").orElseThrow().userOnly()).isTrue();
    assertThat(engine.catalogBlock()).contains("visible", "skill").doesNotContain("hidden");
    assertThat(engine.activationPayload(engine.find("hidden").orElseThrow()))
        .contains("<skill_content name=\"hidden\">", "HIDDEN BODY", "</skill_content>");
  }

  @Test void enumeratesResourcesAndReadAllowlistingNeverGrantsWrite() throws IOException {
    Path directory = home.resolve(".agentty/skills/with-res");
    skill(directory, "with-res", "bundles resources",
        "Run scripts/go.sh then read references/REF.md");
    write(directory.resolve("scripts/go.sh"), "#!/bin/sh\necho hi\n");
    write(directory.resolve("references/REF.md"), "deep reference\n");
    write(directory.resolve(".hidden/secret"), "skip");
    Skill skill = engine.find("with-res").orElseThrow();
    assertThat(skill.resources()).containsExactly("references/REF.md", "scripts/go.sh");
    assertThat(engine.activationPayload(skill)).contains("Skill directory: ",
        "<skill_resources>", "scripts/go.sh", "Resources are NOT loaded");
    Path reference = directory.resolve("references/REF.md");
    assertThat(sandbox.isReadable(reference)).isTrue();
    assertThat(sandbox.isWithin(reference)).isFalse();
    assertThat(sandbox.isReadable(home.resolve("unrelated.txt"))).isFalse();
  }

  @Test void deduplicatesActivationsAndReportsSpecDiagnostics() throws IOException {
    skill(workspace.resolve(".agentty/skills/clean"), "clean", "description", "BODY");
    assertThat(engine.noteActivated("alpha")).isTrue();
    assertThat(engine.noteActivated("alpha")).isFalse();
    assertThat(engine.noteActivated("beta")).isTrue();
    engine.resetActivations();
    assertThat(engine.noteActivated("alpha")).isTrue();
    assertThat(engine.lint(engine.find("clean").orElseThrow())).isEmpty();

    Skill bad = new Skill("Bad--Name-", "", "", "project", "", "", "", false,
        Path.of("different"), java.util.List.of(), java.util.List.of());
    assertThat(engine.lint(bad)).hasSizeGreaterThanOrEqualTo(3)
        .anyMatch(message -> message.contains("invalid characters"))
        .anyMatch(message -> message.contains("consecutive hyphens"))
        .anyMatch(message -> message.contains("description is missing"));
  }

  @Test void resolverReturnsRecoveryHintsPayloadAndAlreadyActiveSentinel() throws IOException {
    skill(workspace.resolve(".agentty/skills/alpha"), "alpha", "description", "BODY");
    var resolver = engine.resolver();
    assertThat(resolver.load("missing")).satisfies(resolution -> {
      assertThat(resolution.body()).isEmpty();
      assertThat(resolution.error()).contains("no skill named 'missing'", "available: alpha");
    });
    assertThat(resolver.load("alpha").body().orElseThrow()).contains("<skill_content", "BODY");
    assertThat(resolver.load("alpha").body().orElseThrow()).contains("already active");

    var empty = new SkillEngine(home.resolve("other-home"), workspace.resolve("other-work"), null);
    assertThat(empty.resolver().load("missing").error()).contains("no skills are installed");
  }

  @Test void lintCoversAllPublishedSizeAndShapeConstraints() {
    Skill bad = new Skill("-" + "a".repeat(64), "d".repeat(1025),
        ("line\n").repeat(501), "project", "c".repeat(501), "", "", false,
        Path.of("mismatch"), java.util.List.of(), java.util.List.of());
    assertThat(engine.lint(bad)).contains("name exceeds 64 characters",
        "name must not start or end with a hyphen", "description exceeds 1024 characters",
        "compatibility exceeds 500 characters")
        .anyMatch(message -> message.contains("spec recommends"));
  }

  @Test void skipsEmptyAndOversizedSkillFilesAndCapsResources() throws IOException {
    write(workspace.resolve(".agentty/skills/empty/SKILL.md"), "");
    write(workspace.resolve(".agentty/skills/huge/SKILL.md"), "x".repeat(SkillEngine.MAX_BODY_BYTES + 1));
    Path many = workspace.resolve(".agentty/skills/many");
    skill(many, "many", "resources", "BODY");
    for (int index = 0; index < 40; index++) write(many.resolve("r/" + index + ".txt"), "x");
    assertThat(engine.find("empty")).isEmpty();
    assertThat(engine.find("huge")).isEmpty();
    assertThat(engine.find("many").orElseThrow().resources()).hasSize(SkillEngine.MAX_RESOURCES);
  }

  private void skill(Path directory, String name, String description, String body) throws IOException {
    write(directory.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: " + description
        + "\n---\n" + body + "\n");
  }

  private void write(Path path, String body) throws IOException {
    Files.createDirectories(path.getParent());
    Files.writeString(path, body);
  }
}
